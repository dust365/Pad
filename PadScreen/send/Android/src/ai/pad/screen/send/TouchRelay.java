package ai.pad.screen.send;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayList;

/**
 * 把平板上的点击和滑动模拟到手机屏幕。系统要求用户在无障碍设置里打开。
 * 手指几乎没动时，按按下的位置做一次短点击，避免被系统当成滑动把底部按键叫出来。
 */
public class TouchRelay extends AccessibilityService {
    private static final String TAG = "PadScreenSend";
    private static final int MAX_POINTS = 48;
    /** 手机像素。小于这个范围算点击，不跟着手指抖动走。 */
    private static final float TAP_SLOP = 56f;

    private static TouchRelay instance;

    private final ArrayList<float[]> points = new ArrayList<float[]>();
    private final Handler handler = new Handler();
    private long downTime;
    private boolean down;
    private boolean sent;
    private final Runnable tapIfHeld = new Runnable() {
        public void run() {
            if (!down || sent || points.isEmpty() || !settled()) {
                return;
            }
            sent = true;
            float[] origin = points.get(0);
            dispatchClick(origin[0], origin[1]);
        }
    };

    static boolean enabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null || enabled.length() == 0) {
            return false;
        }
        ComponentName name = new ComponentName(context, TouchRelay.class);
        return enabled.contains(name.flattenToString()) || enabled.contains(name.getClassName());
    }

    static boolean ready() {
        return instance != null;
    }

    static void openSettings(Activity activity) {
        ComponentName name = new ComponentName(activity, TouchRelay.class);
        if (Build.VERSION.SDK_INT >= 33) {
            Intent details = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
            details.putExtra(Intent.EXTRA_COMPONENT_NAME, name);
            details.setData(Uri.parse("package:" + activity.getPackageName()));
            try {
                activity.startActivity(details);
                return;
            } catch (ActivityNotFoundException ignored) {
                // 下面打开无障碍总列表。
            }
        }
        activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    static void deliver(int action, float x, float y) {
        TouchRelay current = instance;
        if (current != null) {
            current.onPadTouch(action, x, y);
        }
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        super.onServiceConnected();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tapIfHeld);
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    private void onPadTouch(int action, float x, float y) {
        if (action == Wire.TOUCH_DOWN) {
            handler.removeCallbacks(tapIfHeld);
            points.clear();
            points.add(new float[]{x, y});
            downTime = SystemClock.uptimeMillis();
            down = true;
            sent = false;
            // 松手事件如果被平板底部按键吃掉，到点仍按按下的位置点一下。
            handler.postDelayed(tapIfHeld, 280);
            return;
        }
        if (!down) {
            return;
        }
        if (action == Wire.TOUCH_MOVE) {
            remember(x, y);
            return;
        }
        if (action != Wire.TOUCH_UP) {
            return;
        }
        down = false;
        handler.removeCallbacks(tapIfHeld);
        remember(x, y);
        if (sent) {
            points.clear();
            return;
        }
        sent = true;
        long duration = SystemClock.uptimeMillis() - downTime;
        if (settled()) {
            float[] origin = points.get(0);
            dispatchClick(origin[0], origin[1]);
        } else {
            dispatchSwipe(duration);
        }
        points.clear();
    }

    private void remember(float x, float y) {
        float[] last = points.get(points.size() - 1);
        float dx = x - last[0];
        float dy = y - last[1];
        if (dx * dx + dy * dy < 9f) {
            return;
        }
        if (points.size() >= MAX_POINTS) {
            points.remove(1);
        }
        points.add(new float[]{x, y});
    }

    /**
     * 手指离开起点后如果停住，多半是平板导航栏把窗口挤了一下，仍当作点击。
     * 后面还在往前走，才是滑动。
     */
    private boolean settled() {
        if (points.isEmpty()) {
            return false;
        }
        float[] origin = points.get(0);
        int left = -1;
        float slop2 = TAP_SLOP * TAP_SLOP;
        for (int i = 1; i < points.size(); i++) {
            if (distance2(origin, points.get(i)) > slop2) {
                left = i;
                break;
            }
        }
        if (left < 0) {
            return true;
        }
        float[] jumped = points.get(left);
        for (int i = left + 1; i < points.size(); i++) {
            if (distance2(jumped, points.get(i)) > slop2) {
                return false;
            }
        }
        return true;
    }

    private static float distance2(float[] a, float[] b) {
        float dx = a[0] - b[0];
        float dy = a[1] - b[1];
        return dx * dx + dy * dy;
    }

    private void dispatchClick(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        path.lineTo(x + 1f, y + 1f);
        sendPath(path, 100, "tap " + (int) x + "," + (int) y);
    }

    private void dispatchSwipe(long duration) {
        if (points.size() < 2) {
            float[] only = points.get(0);
            dispatchClick(only[0], only[1]);
            return;
        }
        Path path = new Path();
        float[] first = points.get(0);
        path.moveTo(first[0], first[1]);
        for (int i = 1; i < points.size(); i++) {
            float[] point = points.get(i);
            path.lineTo(point[0], point[1]);
        }
        if (duration < 80) {
            duration = 80;
        } else if (duration > 600) {
            duration = 600;
        }
        float[] last = points.get(points.size() - 1);
        sendPath(path, duration, "swipe " + (int) last[0] + "," + (int) last[1]);
    }

    private void sendPath(Path path, long duration, String kind) {
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, duration);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        Log.i(TAG, kind);
        if (!dispatchGesture(gesture, new GestureResultCallback() {
            public void onCancelled(GestureDescription gestureDescription) {
                Log.w(TAG, "gesture cancelled");
            }
        }, handler)) {
            Log.w(TAG, "gesture rejected");
        }
    }
}
