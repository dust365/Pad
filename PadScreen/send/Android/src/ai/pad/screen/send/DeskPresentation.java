package ai.pad.screen.send;

import android.app.Presentation;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 挂在虚拟屏上的窗口。小米等系统不允许 startActivity 指定 launchDisplayId，
 * 所以桌面不做成另一个 Activity。
 */
final class DeskPresentation extends Presentation {
    private final Hub hub;
    private final String key;
    private ScrollView noteScroll;
    private TextView noteView;
    private WebView webView;
    private TextView placeholder;
    private String loadedUrl = "";
    private long downTime;
    private boolean down;
    private boolean closedByUs;
    private boolean webDestroyed;

    DeskPresentation(Context outerContext, Display display, Hub hub, String key) {
        super(outerContext, display);
        this.hub = hub;
        this.key = key;
        setCancelable(false);
    }

    String key() {
        return key;
    }

    void markClosed() {
        closedByUs = true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        getWindow().setAttributes(attrs);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        buildUi();
        setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface dialog) {
                destroyWeb();
                if (!closedByUs) {
                    hub.onDeskDestroyed(DeskPresentation.this);
                }
            }
        });
        hub.registerDesk(this);
    }

    void bind() {
        if (noteScroll == null) {
            return;
        }
        Session session = hub.session(key);
        if (session == null) {
            return;
        }
        if (session.page == Hub.PAGE_BROWSER) {
            noteScroll.setVisibility(View.GONE);
            if (session.url.length() == 0) {
                webView.setVisibility(View.GONE);
                placeholder.setText("在手机上输入网址");
                placeholder.setVisibility(View.VISIBLE);
            } else {
                placeholder.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                if (!session.url.equals(loadedUrl)) {
                    loadedUrl = session.url;
                    webView.loadUrl(session.url);
                }
            }
        } else {
            webView.setVisibility(View.GONE);
            placeholder.setVisibility(View.GONE);
            noteScroll.setVisibility(View.VISIBLE);
            showNote(hub.note());
        }
    }

    void showNote(String text) {
        if (noteView == null || text == null) {
            return;
        }
        if (text.equals(noteView.getText().toString())) {
            return;
        }
        noteView.setText(text);
        noteScroll.post(new Runnable() {
            public void run() {
                noteScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    void goBack() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        }
    }

    void dispatchTouch(int action, int x, int y) {
        if (closedByUs || !isShowing() || getWindow() == null) {
            return;
        }
        Session session = hub.session(key);
        if (session == null) {
            return;
        }
        View decor = getWindow().getDecorView();
        int viewWidth = decor.getWidth();
        int viewHeight = decor.getHeight();
        float px = x;
        float py = y;
        if (viewWidth > 0 && viewHeight > 0
                && (viewWidth != session.width || viewHeight != session.height)) {
            px = x * (float) viewWidth / (float) session.width;
            py = y * (float) viewHeight / (float) session.height;
        }
        int motion;
        long now = SystemClock.uptimeMillis();
        if (action == Wire.TOUCH_DOWN) {
            motion = MotionEvent.ACTION_DOWN;
            downTime = now;
            down = true;
        } else if (action == Wire.TOUCH_UP) {
            motion = MotionEvent.ACTION_UP;
            down = false;
        } else if (down) {
            motion = MotionEvent.ACTION_MOVE;
        } else {
            return;
        }
        MotionEvent event = MotionEvent.obtain(downTime, now, motion, px, py, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        decor.dispatchTouchEvent(event);
        event.recycle();
    }

    private void destroyWeb() {
        if (webDestroyed || webView == null) {
            return;
        }
        webDestroyed = true;
        android.view.ViewParent parent = webView.getParent();
        if (parent instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) parent).removeView(webView);
        }
        webView.setWebViewClient(null);
        webView.destroy();
        webView = null;
    }

    private void buildUi() {
        Context context = getContext();
        int match = FrameLayout.LayoutParams.MATCH_PARENT;
        int wrap = FrameLayout.LayoutParams.WRAP_CONTENT;
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.WHITE);

        noteScroll = new ScrollView(context);
        noteScroll.setFillViewport(true);
        noteView = new TextView(context);
        noteView.setTextColor(Color.BLACK);
        noteView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        int pad = dp(12);
        noteView.setPadding(pad, pad, pad, pad);
        noteView.setGravity(Gravity.TOP | Gravity.START);
        noteScroll.addView(noteView, new ScrollView.LayoutParams(match, wrap));

        webView = new WebView(context);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                placeholder.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    placeholder.setText("打不开这个网址");
                    placeholder.setVisibility(View.VISIBLE);
                }
            }
        });

        placeholder = new TextView(context);
        placeholder.setTextColor(Color.DKGRAY);
        placeholder.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setVisibility(View.GONE);

        root.addView(noteScroll, new FrameLayout.LayoutParams(match, match));
        root.addView(webView, new FrameLayout.LayoutParams(match, match));
        root.addView(placeholder, new FrameLayout.LayoutParams(match, match));
        setContentView(root);
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }
}
