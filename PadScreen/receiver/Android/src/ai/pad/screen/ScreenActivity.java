package ai.pad.screen;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 在局域网里广播本机型号，接收发送端发来的 JPEG，并把单指左键触控送回去。
 * Android 4.2 的 NsdServiceInfo 不能写 TXT，名字放在服务名和 HELLO 帧里。
 * 分辨率用屏幕真实像素，白色 U03H1 是 1024×600，黑色 K701BC 是 800×480。
 * 整帧只在画面大变时发送；平时只解码变化区域，避免 A20 软解整屏。
 */
public class ScreenActivity extends Activity {
    static final int PORT = 8090;
    private int panelWidth = 1024;
    private int panelHeight = 600;
    private String padName = "Pad";
    private static final String TAG = "PadScreen";
    private static final long MOVE_INTERVAL_MS = 40;

    private ScreenView screenView;
    private TextView statusView;
    private View cover;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private int savedScreenTimeout = -1;
    private int savedWifiSleep = -1;
    private boolean settingsChanged;
    private ServerSocket serverSocket;
    private Socket client;
    private OutputStream clientOut;
    private volatile boolean running;
    private long lastMoveAt;
    private boolean gesture;
    private float gestureOriginRawX;
    private float gestureOriginRawY;
    private int gestureOriginX;
    private int gestureOriginY;
    private int gestureWidth;
    private int gestureHeight;
    private Bitmap screenBitmap;
    private Bitmap spareBitmap;
    private Canvas screenCanvas;
    private Bitmap screenCanvasBitmap;
    private final BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
    private final Paint blitPaint = new Paint(0);
    private final Rect dstRect = new Rect();
    private final Object bitmapLock = new Object();
    private final Object queueLock = new Object();
    private final ArrayDeque<DrawOp> queue = new ArrayDeque<DrawOp>();
    private final DrawOp[] pool = new DrawOp[3];
    private int poolCount;
    private final Runnable drainPosted = new Runnable() {
        public void run() {
            drainQueue();
        }
    };
    private final Handler uiHandler = new Handler();
    private final Runnable hideUiOnce = new Runnable() {
        public void run() {
            hideSystemUi();
        }
    };
    private final Runnable keepNavHidden = new Runnable() {
        public void run() {
            hideSystemUi();
            uiHandler.postDelayed(this, 500);
        }
    };
    private int fullCount;
    private int patchCount;
    private long decodeNs;
    private long lastStatAt;

    private static final class DrawOp {
        boolean full;
        Bitmap bitmap;
        int x;
        int y;
        int w;
        int h;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.screenBrightness = 0.55f;
        getWindow().setAttributes(attrs);

        readPanel();
        blitPaint.setFilterBitmap(false);
        blitPaint.setDither(false);
        decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;
        decodeOptions.inDither = false;
        decodeOptions.inScaled = false;
        decodeOptions.inMutable = true;
        decodeOptions.inPreferQualityOverSpeed = true;
        screenBitmap = Bitmap.createBitmap(panelWidth, panelHeight, Bitmap.Config.ARGB_8888);
        spareBitmap = Bitmap.createBitmap(panelWidth, panelHeight, Bitmap.Config.ARGB_8888);
        for (int i = 0; i < pool.length; i++) {
            pool[i] = new DrawOp();
        }
        poolCount = pool.length;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xff000000);
        screenView = new ScreenView(this);
        screenView.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                return handleTouch(event);
            }
        });
        root.addView(screenView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        statusView = new TextView(this);
        statusView.setTextColor(0xfff2f2f2);
        statusView.setTextSize(22);
        statusView.setGravity(Gravity.CENTER);
        statusView.setText(waitingText());
        root.addView(statusView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        View anchor = new View(this);
        anchor.setBackgroundColor(0xff000000);
        setContentView(anchor);
        // 系统栏是 2019，普通窗口盖不住。2024 在它上面，画面按真实像素铺满，底部按键不再占高度。
        if (!attachCover(root)) {
            setContentView(root);
            hideSystemUi();
        }
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(
                new View.OnSystemUiVisibilityChangeListener() {
                    public void onSystemUiVisibilityChange(int visibility) {
                        if (cover != null) {
                            return;
                        }
                        if ((visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0
                                || (visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                            // 在这次回调里直接改标记会被系统丢掉，所以推迟到下一轮。
                            uiHandler.removeCallbacks(hideUiOnce);
                            uiHandler.post(hideUiOnce);
                        }
                    }
                });
        keepScreenAwake();

        boostCpu();
        acquireLocks();
        holdAwakeSettings();
        startServer();
        registerService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        keepScreenAwake();
        holdAwakeSettings();
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }
        uiHandler.removeCallbacks(keepNavHidden);
        if (cover == null) {
            uiHandler.post(keepNavHidden);
        }
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(keepNavHidden);
        uiHandler.removeCallbacks(hideUiOnce);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            if (cover == null) {
                hideSystemUi();
            }
            keepScreenAwake();
        }
    }

    private void keepScreenAwake() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
    }

    /** 这台 4.2 会忽略窗口常亮。改系统息屏和 Wi-Fi 休眠，退出副屏时改回去。 */
    private void holdAwakeSettings() {
        try {
            if (!settingsChanged) {
                savedScreenTimeout = Settings.System.getInt(
                        getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 60000);
                savedWifiSleep = Settings.System.getInt(
                        getContentResolver(), Settings.System.WIFI_SLEEP_POLICY,
                        Settings.System.WIFI_SLEEP_POLICY_DEFAULT);
                settingsChanged = true;
            }
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, Integer.MAX_VALUE);
            Settings.System.putInt(getContentResolver(), Settings.System.WIFI_SLEEP_POLICY,
                    Settings.System.WIFI_SLEEP_POLICY_NEVER);
        } catch (Exception e) {
            Log.w(TAG, "keep awake settings failed", e);
        }
    }

    private void restoreAwakeSettings() {
        if (!settingsChanged) {
            return;
        }
        try {
            if (savedScreenTimeout > 0) {
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, savedScreenTimeout);
            }
            if (savedWifiSleep >= 0) {
                Settings.System.putInt(getContentResolver(), Settings.System.WIFI_SLEEP_POLICY, savedWifiSleep);
            }
        } catch (Exception e) {
            Log.w(TAG, "restore awake settings failed", e);
        }
        settingsChanged = false;
    }

    /** 盖住底部系统栏。这一层高于系统栏，点屏幕不会把它再叫出来。 */
    private boolean attachCover(View root) {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) {
            return false;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                panelWidth,
                panelHeight,
                2024,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE);
        lp.gravity = Gravity.LEFT | Gravity.TOP;
        lp.x = 0;
        lp.y = 0;
        lp.screenBrightness = 0.55f;
        try {
            wm.addView(root, lp);
            cover = root;
            Log.i(TAG, "cover " + panelWidth + "x" + panelHeight);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "cover window failed", e);
            return false;
        }
    }

    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LOW_PROFILE);
    }

    private void readPanel() {
        Display display = getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        int wide = size.x;
        int high = size.y;
        if (high > wide) {
            int swap = wide;
            wide = high;
            high = swap;
        }
        if (wide >= 2 && high >= 2) {
            panelWidth = wide;
            panelHeight = high;
        }
        String model = Build.MODEL;
        if (model != null && model.length() > 0) {
            padName = model;
        }
        Log.i(TAG, "panel " + padName + " " + panelWidth + "x" + panelHeight);
    }

    private String waitingText() {
        return "等待连接\n" + padName + "\n" + panelWidth + "×" + panelHeight;
    }

    private void boostCpu() {
        writeSysfs("/sys/devices/system/cpu/cpu1/online", "1");
        writeSysfs("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor", "performance");
        writeSysfs("/sys/devices/system/cpu/cpu1/cpufreq/scaling_governor", "performance");
    }

    private void writeSysfs(String path, String value) {
        FileWriter writer = null;
        try {
            writer = new FileWriter(path);
            writer.write(value);
        } catch (Exception ignored) {
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void acquireLocks() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP
                        | PowerManager.ON_AFTER_RELEASE,
                "pad-screen");
        wakeLock.acquire();
        WifiManager wifi = (WifiManager) getSystemService(WIFI_SERVICE);
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pad-screen");
        wifiLock.acquire();
        multicastLock = wifi.createMulticastLock("pad-screen");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();
    }

    private void startServer() {
        running = true;
        Thread thread = new Thread(new Runnable() {
            public void run() {
                acceptLoop();
            }
        }, "pad-accept");
        thread.start();
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(PORT);
            serverSocket.setReuseAddress(true);
            Log.i(TAG, "listening " + PORT);
        } catch (Exception e) {
            Log.e(TAG, "bind failed", e);
            showStatus("端口 " + PORT + " 打开失败");
            return;
        }
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                boolean taken;
                synchronized (this) {
                    taken = client != null;
                    if (!taken) {
                        client = socket;
                    }
                }
                if (taken) {
                    socket.close();
                    continue;
                }
                Thread worker = new Thread(new Runnable() {
                    public void run() {
                        serve(socket);
                    }
                }, "pad-client");
                worker.start();
            } catch (Exception e) {
                if (running) {
                    Log.w(TAG, "accept failed", e);
                }
            }
        }
    }

    private void serve(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            socket.setReceiveBufferSize(256 * 1024);
            socket.setPerformancePreferences(0, 2, 0);
            synchronized (this) {
                clientOut = socket.getOutputStream();
            }
            Wire.writeFrame(clientOut, Wire.HELLO, Wire.hello(panelWidth, panelHeight, padName));
            showStatus("已连接");
            InputStream in = socket.getInputStream();
            int[] type = new int[1];
            while (running) {
                byte[] payload = Wire.readFrame(in, type);
                if (type[0] == Wire.JPEG) {
                    showFull(payload);
                } else if (type[0] == Wire.PATCH) {
                    showPatch(payload);
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "client closed", e);
        } finally {
            synchronized (this) {
                if (client == socket) {
                    client = null;
                    clientOut = null;
                }
            }
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            showStatus(waitingText());
        }
    }

    private void showFull(byte[] jpeg) {
        Bitmap decoded;
        synchronized (bitmapLock) {
            decoded = decode(jpeg, 0, jpeg.length, spareBitmap);
            if (decoded == null) {
                return;
            }
            if (decoded == spareBitmap) {
                spareBitmap = null;
            }
        }
        final Bitmap bitmap = decoded;
        final CountDownLatch applied = new CountDownLatch(1);
        runOnUiThread(new Runnable() {
            public void run() {
                Bitmap previous = screenBitmap;
                screenBitmap = bitmap;
                screenView.setFrame(bitmap);
                statusView.setVisibility(View.GONE);
                screenView.invalidate();
                synchronized (bitmapLock) {
                    if (previous != null && previous != screenBitmap) {
                        spareBitmap = previous;
                    }
                }
                applied.countDown();
            }
        });
        try {
            applied.await(400, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        noteFrame(true);
    }

    private void showPatch(byte[] payload) {
        if (payload.length < 8 || screenBitmap == null) {
            return;
        }
        int x = u16(payload, 0);
        int y = u16(payload, 2);
        int w = u16(payload, 4);
        int h = u16(payload, 6);
        if (w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > panelWidth || y + h > panelHeight) {
            return;
        }
        Bitmap tile = decode(payload, 8, payload.length - 8, null);
        if (tile == null) {
            return;
        }
        DrawOp op = borrow();
        if (op == null) {
            tile.recycle();
            return;
        }
        op.full = false;
        op.bitmap = tile;
        op.x = x;
        op.y = y;
        op.w = w;
        op.h = h;
        synchronized (queueLock) {
            queue.addLast(op);
        }
        runOnUiThread(drainPosted);
        noteFrame(false);
    }

    private DrawOp borrow() {
        synchronized (queueLock) {
            long deadline = System.currentTimeMillis() + 250;
            while (poolCount == 0) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return null;
                }
                try {
                    queueLock.wait(left);
                } catch (InterruptedException e) {
                    return null;
                }
            }
            poolCount--;
            return pool[poolCount];
        }
    }

    private void drainQueue() {
        while (true) {
            DrawOp op;
            synchronized (queueLock) {
                op = queue.pollFirst();
                if (op == null) {
                    return;
                }
            }
            if (!op.full && screenBitmap != null && !screenBitmap.isRecycled()) {
                if (screenCanvas == null || screenCanvasBitmap != screenBitmap) {
                    screenCanvas = new Canvas(screenBitmap);
                    screenCanvasBitmap = screenBitmap;
                }
                screenCanvas.drawBitmap(op.bitmap, op.x, op.y, blitPaint);
                screenView.invalidatePatch(op.x, op.y, op.w, op.h);
                statusView.setVisibility(View.GONE);
            }
            if (op.bitmap != null && !op.full) {
                op.bitmap.recycle();
            }
            op.bitmap = null;
            synchronized (queueLock) {
                pool[poolCount++] = op;
                queueLock.notifyAll();
            }
        }
    }

    private Bitmap decode(byte[] data, int offset, int length, Bitmap reuse) {
        decodeOptions.inBitmap = reuse;
        long start = System.nanoTime();
        Bitmap bitmap;
        try {
            bitmap = BitmapFactory.decodeByteArray(data, offset, length, decodeOptions);
        } catch (IllegalArgumentException e) {
            decodeOptions.inBitmap = null;
            bitmap = BitmapFactory.decodeByteArray(data, offset, length, decodeOptions);
        } finally {
            decodeOptions.inBitmap = null;
        }
        decodeNs += System.nanoTime() - start;
        return bitmap;
    }

    private void noteFrame(boolean full) {
        if (full) {
            fullCount++;
        } else {
            patchCount++;
        }
        long now = System.currentTimeMillis();
        if (lastStatAt == 0) {
            lastStatAt = now;
        }
        if (now - lastStatAt < 3000) {
            return;
        }
        long ms = decodeNs / 1000000L;
        Log.i(TAG, "full=" + fullCount + " patch=" + patchCount + " decodeMs=" + ms);
        fullCount = 0;
        patchCount = 0;
        decodeNs = 0;
        lastStatAt = now;
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private boolean handleTouch(MotionEvent event) {
        int action;
        int masked = event.getActionMasked();
        if (masked == MotionEvent.ACTION_DOWN) {
            action = Wire.TOUCH_DOWN;
            gesture = true;
            gestureWidth = screenView.getWidth();
            gestureHeight = screenView.getHeight();
            gestureOriginRawX = event.getRawX();
            gestureOriginRawY = event.getRawY();
            gestureOriginX = scaleToPanel(event.getX(), gestureWidth, panelWidth);
            gestureOriginY = scaleToPanel(event.getY(), gestureHeight, panelHeight);
        } else if (masked == MotionEvent.ACTION_MOVE) {
            long now = System.currentTimeMillis();
            if (now - lastMoveAt < MOVE_INTERVAL_MS) {
                return true;
            }
            lastMoveAt = now;
            action = Wire.TOUCH_MOVE;
        } else if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) {
            action = Wire.TOUCH_UP;
        } else {
            return true;
        }
        if (!gesture || gestureWidth <= 0 || gestureHeight <= 0) {
            return true;
        }
        // 用按下时的屏幕坐标做原点。导航栏弹出把窗口挤矮时，同一点不会变成滑动。
        int x = gestureOriginX + Math.round((event.getRawX() - gestureOriginRawX) * panelWidth / (float) gestureWidth);
        int y = gestureOriginY + Math.round((event.getRawY() - gestureOriginRawY) * panelHeight / (float) gestureHeight);
        if (action == Wire.TOUCH_UP) {
            gesture = false;
        }
        if (x < 0) {
            x = 0;
        } else if (x >= panelWidth) {
            x = panelWidth - 1;
        }
        if (y < 0) {
            y = 0;
        } else if (y >= panelHeight) {
            y = panelHeight - 1;
        }
        sendTouch(action, x, y);
        return true;
    }

    private static int scaleToPanel(float viewPosition, int viewSpan, int panelSpan) {
        if (viewSpan <= 0) {
            return 0;
        }
        return (int) (viewPosition * panelSpan / viewSpan);
    }

    private void sendTouch(int action, int x, int y) {
        OutputStream out;
        synchronized (this) {
            out = clientOut;
        }
        if (out == null) {
            return;
        }
        try {
            Wire.writeFrame(out, Wire.TOUCH, Wire.touch(action, x, y));
        } catch (Exception e) {
            Log.w(TAG, "touch send failed", e);
        }
    }

    private void registerService() {
        nsdManager = (NsdManager) getSystemService(NSD_SERVICE);
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(padName);
        info.setServiceType("_padscreen._tcp.");
        info.setPort(PORT);
        registrationListener = new NsdManager.RegistrationListener() {
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                Log.i(TAG, "registered " + serviceInfo.getServiceName());
            }

            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.e(TAG, "nsd failed " + errorCode);
                showStatus("局域网广播失败 " + errorCode);
            }

            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            }

            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            }
        };
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener);
    }

    private void showStatus(final String text) {
        runOnUiThread(new Runnable() {
            public void run() {
                statusView.setText(text);
                statusView.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(keepNavHidden);
        uiHandler.removeCallbacks(hideUiOnce);
        running = false;
        if (nsdManager != null && registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener);
            } catch (Exception ignored) {
            }
        }
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        }
        synchronized (this) {
            try {
                if (client != null) {
                    client.close();
                }
            } catch (Exception ignored) {
            }
        }
        if (cover != null) {
            try {
                ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(cover);
            } catch (Exception ignored) {
            }
            cover = null;
        }
        restoreAwakeSettings();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        super.onDestroy();
    }

    private final class ScreenView extends View {
        private Bitmap bitmap;

        ScreenView(Context context) {
            super(context);
        }

        void setFrame(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        void invalidatePatch(int x, int y, int w, int h) {
            int viewWidth = getWidth();
            int viewHeight = getHeight();
            if (viewWidth == panelWidth && viewHeight == panelHeight) {
                invalidate(x, y, x + w, y + h);
                return;
            }
            if (viewWidth <= 0 || viewHeight <= 0) {
                invalidate();
                return;
            }
            int left = x * viewWidth / panelWidth;
            int top = y * viewHeight / panelHeight;
            int right = (x + w) * viewWidth / panelWidth;
            int bottom = (y + h) * viewHeight / panelHeight;
            invalidate(left, top, right, bottom);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (bitmap == null) {
                return;
            }
            int viewWidth = getWidth();
            int viewHeight = getHeight();
            if (viewWidth == bitmap.getWidth() && viewHeight == bitmap.getHeight()) {
                canvas.drawBitmap(bitmap, 0, 0, blitPaint);
            } else {
                dstRect.set(0, 0, viewWidth, viewHeight);
                canvas.drawBitmap(bitmap, null, dstRect, blitPaint);
            }
        }
    }
}
