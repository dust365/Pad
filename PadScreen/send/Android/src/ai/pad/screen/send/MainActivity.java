package ai.pad.screen.send;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_NOTIFY = 1;
    private static final int REQ_CAPTURE = 2;

    private Hub hub;
    private TextView errorView;
    private TextView statusView;
    private Button rescanButton;
    private LinearLayout deviceList;
    private Button touchButton;
    private TextView touchStatus;
    private Finder.FoundPad pendingPad;
    private boolean waitingForAccessibility;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hub = ((SendApp) getApplication()).hub();
        hub.ensureFinder();
        buildUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        hub.setUi(this);
        render();
        if (waitingForAccessibility && pendingPad != null && TouchRelay.enabled(this)) {
            waitingForAccessibility = false;
            Finder.FoundPad waiting = pendingPad;
            pendingPad = null;
            connectPad(waiting);
        }
    }

    @Override
    protected void onStop() {
        hub.clearUi(this);
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIFY) {
            return;
        }
        Finder.FoundPad pad = pendingPad;
        pendingPad = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && pad != null) {
            connectPad(pad);
        } else {
            hub.showError("需要允许通知，投屏才能在后台保持。");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) {
            return;
        }
        Finder.FoundPad pad = pendingPad;
        pendingPad = null;
        if (resultCode != RESULT_OK || data == null) {
            hub.showError("需要允许投屏，平板才能看到手机画面。");
            return;
        }
        hub.prepareCapture(resultCode, data, pad);
    }

    void render() {
        if (errorView == null) {
            return;
        }
        String error = hub.error();
        if (error == null || error.length() == 0) {
            errorView.setVisibility(View.GONE);
        } else {
            errorView.setText(error);
            errorView.setVisibility(View.VISIBLE);
        }
        boolean scanning = hub.finder.scanning();
        List<Finder.FoundPad> pads = hub.finder.pads();
        ArrayList<Session> sessions = hub.sessions();
        if (pads.isEmpty() && sessions.isEmpty()) {
            statusView.setText(scanning ? "正在扫描…" : "没有发现设备");
        } else if (!pads.isEmpty()) {
            statusView.setText("已发现 " + pads.size() + " 台");
        } else {
            statusView.setText("已连接 " + sessions.size() + " 台");
        }
        rescanButton.setText(scanning ? "正在扫描…" : "重新扫描");
        rescanButton.setEnabled(!scanning);
        boolean touch = TouchRelay.enabled(this);
        touchButton.setVisibility(touch ? View.GONE : View.VISIBLE);
        touchStatus.setVisibility(View.VISIBLE);
        if (touch) {
            touchStatus.setTextColor(0xFF1B6B3A);
            touchStatus.setText("无障碍已打开。平板上的点击和滑动会送到这台手机。");
        } else {
            touchStatus.setTextColor(0xFFB00020);
            touchStatus.setText("无障碍未打开。平板要点这台手机，需要先打开这项服务。");
        }
        renderDevices(pads, sessions);
    }

    private void connectPad(Finder.FoundPad pad) {
        if (!TouchRelay.enabled(this)) {
            pendingPad = pad;
            waitingForAccessibility = true;
            hub.showError("要用平板操作这台手机，请先打开无障碍服务「副屏控制」。");
            TouchRelay.openSettings(this);
            return;
        }
        waitingForAccessibility = false;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingPad = pad;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
            return;
        }
        if (!hub.projectionReady()) {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (manager == null) {
                hub.showError("这台手机不能投屏。");
                return;
            }
            pendingPad = pad;
            startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE);
            return;
        }
        hub.connect(pad);
    }

    private void renderDevices(List<Finder.FoundPad> pads, ArrayList<Session> sessions) {
        deviceList.removeAllViews();
        HashSet<String> listed = new HashSet<String>();
        for (int i = 0; i < pads.size(); i++) {
            Finder.FoundPad pad = pads.get(i);
            listed.add(pad.key());
            addDeviceRow(pad.name, pad.host, hub.session(pad.key()), pad);
        }
        for (int i = 0; i < sessions.size(); i++) {
            Session session = sessions.get(i);
            if (!listed.contains(session.key)) {
                addDeviceRow(session.name, session.host, session, null);
            }
        }
    }

    private void addDeviceRow(String name, String host, final Session session, final Finder.FoundPad pad) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (session == null) {
            Button connect = new Button(this);
            connect.setText(name + "  " + host);
            connect.setAllCaps(false);
            connect.setOnClickListener(new View.OnClickListener() {
                public void onClick(View view) {
                    connectPad(pad);
                }
            });
            row.addView(connect, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            TextView label = new TextView(this);
            String state = session.phase == Session.LIVE ? "已连接" : "连接中";
            label.setText(name + "  " + host + "  " + state);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(label, labelParams);
            Button disconnect = new Button(this);
            disconnect.setText("断开");
            disconnect.setAllCaps(false);
            disconnect.setOnClickListener(new View.OnClickListener() {
                public void onClick(View view) {
                    hub.disconnect(session.key);
                }
            });
            row.addView(disconnect, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        deviceList.addView(row, params);
    }

    private void buildUi() {
        int match = ViewGroup.LayoutParams.MATCH_PARENT;
        int wrap = ViewGroup.LayoutParams.WRAP_CONTENT;
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("副屏控制");
        title.setTextColor(Color.BLACK);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        root.addView(title, new LinearLayout.LayoutParams(match, wrap));

        TextView hint = new TextView(this);
        hint.setText("连接后，平板显示这台手机正在看的整个屏幕，四周留黑边。");
        hint.setTextColor(Color.DKGRAY);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(match, wrap);
        hintParams.topMargin = dp(8);
        root.addView(hint, hintParams);

        touchStatus = new TextView(this);
        touchStatus.setText("无障碍未打开。平板要点这台手机，需要先打开这项服务。");
        touchStatus.setTextColor(0xFFB00020);
        touchStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams touchStatusParams = new LinearLayout.LayoutParams(match, wrap);
        touchStatusParams.topMargin = dp(12);
        root.addView(touchStatus, touchStatusParams);

        touchButton = new Button(this);
        touchButton.setText("打开无障碍服务");
        touchButton.setAllCaps(false);
        touchButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                hub.clearError();
                TouchRelay.openSettings(MainActivity.this);
            }
        });
        LinearLayout.LayoutParams touchParams = new LinearLayout.LayoutParams(match, wrap);
        touchParams.topMargin = dp(8);
        root.addView(touchButton, touchParams);

        errorView = new TextView(this);
        errorView.setTextColor(0xFFB00020);
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        errorView.setVisibility(View.GONE);
        LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(match, wrap);
        errorParams.topMargin = dp(8);
        root.addView(errorView, errorParams);

        statusView = new TextView(this);
        statusView.setText("正在扫描…");
        statusView.setTextColor(Color.BLACK);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(match, wrap);
        statusParams.topMargin = dp(12);
        root.addView(statusView, statusParams);

        rescanButton = new Button(this);
        rescanButton.setText("重新扫描");
        rescanButton.setAllCaps(false);
        rescanButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                hub.clearError();
                hub.finder.rescan();
            }
        });
        LinearLayout.LayoutParams rescanParams = new LinearLayout.LayoutParams(match, wrap);
        rescanParams.topMargin = dp(8);
        root.addView(rescanButton, rescanParams);

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        root.addView(deviceList, new LinearLayout.LayoutParams(match, wrap));

        scroll.addView(root, new ScrollView.LayoutParams(match, wrap));
        setContentView(scroll);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
