package ai.pad.monitor;

import android.app.Activity;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.List;

/**
 * 用 Android 4.2 的旧相机接口采集画面，经局域网 HTTP 送出。
 * 预览留在平板上，方便对准。屏幕保持微亮，避免画面被系统停掉。
 */
public class MonitorActivity extends Activity implements SurfaceHolder.Callback {
    static final String TOKEN = "pad-local";
    private static final int PORT = 8080;
    private static final int TARGET_FRAME_MS = 150;
    private static final String TAG = "PadMonitor";

    private SurfaceView surfaceView;
    private TextView infoView;
    private Camera camera;
    private int cameraId = -1;
    private int previewWidth;
    private int previewHeight;
    private boolean previewStarted;
    private MjpegServer server;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private Thread encodeThread;
    private volatile boolean encoding;
    private final Object nv21Lock = new Object();
    private byte[] pendingNv21;
    private long lastEncodedAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.screenBrightness = 0.15f;
        getWindow().setAttributes(attrs);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff101010);

        infoView = new TextView(this);
        infoView.setTextColor(0xfff2f2f2);
        infoView.setTextSize(18);
        infoView.setPadding(24, 24, 24, 12);
        root.addView(infoView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button switchButton = new Button(this);
        switchButton.setText("切换镜头");
        switchButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                switchCamera();
            }
        });
        root.addView(switchButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        surfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(surfaceView, previewParams);
        setContentView(root);

        acquireLocks();
        startServer();
        startEncoder();
        showAddress();
    }

    private void acquireLocks() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "pad-monitor");
        wakeLock.acquire();
        WifiManager wifi = (WifiManager) getSystemService(WIFI_SERVICE);
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL, "pad-monitor");
        wifiLock.acquire();
    }

    private void startServer() {
        server = new MjpegServer(PORT, TOKEN);
        try {
            server.start();
            Log.i(TAG, "listening on " + PORT);
        } catch (Exception e) {
            Log.e(TAG, "server start failed", e);
            infoView.setText("端口 " + PORT + " 打开失败：" + e.getMessage());
            server = null;
        }
    }

    private void startEncoder() {
        encoding = true;
        encodeThread = new Thread(new Runnable() {
            public void run() {
                encodeLoop();
            }
        }, "jpeg-encode");
        encodeThread.start();
    }

    private void showAddress() {
        String ip = wifiIpv4();
        if (ip == null) {
            infoView.setText("Wi-Fi 还没有地址。连上后再打开一次。");
            return;
        }
        infoView.setText("浏览器打开\nhttp://" + ip + ":" + PORT + "/?k=" + TOKEN);
    }

    private void switchCamera() {
        int count = Camera.getNumberOfCameras();
        if (count <= 1) {
            return;
        }
        int next = cameraId + 1;
        if (next >= count) {
            next = 0;
        }
        releaseCamera();
        openCamera(next);
        startPreviewIfReady();
    }

    public void surfaceCreated(SurfaceHolder holder) {
        if (cameraId < 0) {
            cameraId = findBackCamera();
        }
        openCamera(cameraId);
        startPreviewIfReady();
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        startPreviewIfReady();
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        stopPreviewOnly();
    }

    private int findBackCamera() {
        int count = Camera.getNumberOfCameras();
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < count; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                return i;
            }
        }
        return 0;
    }

    private void openCamera(int id) {
        releaseCamera();
        try {
            camera = Camera.open(id);
            cameraId = id;
            Camera.Parameters params = camera.getParameters();
            Camera.Size size = chooseSize(params.getSupportedPreviewSizes());
            previewWidth = size.width;
            previewHeight = size.height;
            params.setPreviewSize(previewWidth, previewHeight);
            params.setPreviewFormat(ImageFormat.NV21);
            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes != null) {
                if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_FIXED)) {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED);
                }
            }
            try {
                camera.setParameters(params);
            } catch (Exception e) {
                Log.w(TAG, "setParameters failed", e);
            }
            applyDisplayOrientation();
            Log.i(TAG, "camera " + id + " preview " + previewWidth + "x" + previewHeight);
        } catch (Exception e) {
            Log.e(TAG, "camera open failed", e);
            infoView.setText("相机打开失败：" + e.getMessage());
            camera = null;
        }
    }

    private void applyDisplayOrientation() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        int degrees = 0;
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case android.view.Surface.ROTATION_90:
                degrees = 90;
                break;
            case android.view.Surface.ROTATION_180:
                degrees = 180;
                break;
            case android.view.Surface.ROTATION_270:
                degrees = 270;
                break;
            default:
                break;
        }
        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;
        } else {
            result = (info.orientation - degrees + 360) % 360;
        }
        camera.setDisplayOrientation(result);
    }

    private Camera.Size chooseSize(List<Camera.Size> sizes) {
        Camera.Size best = sizes.get(0);
        int bestScore = Integer.MAX_VALUE;
        for (int i = 0; i < sizes.size(); i++) {
            Camera.Size size = sizes.get(i);
            int pixels = size.width * size.height;
            int score = Math.abs(pixels - 640 * 480);
            if (pixels > 800 * 600) {
                score += 100000;
            }
            if (score < bestScore) {
                best = size;
                bestScore = score;
            }
        }
        return best;
    }

    private void startPreviewIfReady() {
        if (camera == null || previewStarted) {
            return;
        }
        try {
            camera.setPreviewDisplay(surfaceView.getHolder());
            int bufferSize = previewWidth * previewHeight * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
            for (int i = 0; i < 3; i++) {
                camera.addCallbackBuffer(new byte[bufferSize]);
            }
            camera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
                public void onPreviewFrame(byte[] data, Camera cam) {
                    if (data != null) {
                        long now = System.currentTimeMillis();
                        if (now - lastEncodedAt >= TARGET_FRAME_MS) {
                            byte[] copy = new byte[data.length];
                            System.arraycopy(data, 0, copy, 0, data.length);
                            synchronized (nv21Lock) {
                                pendingNv21 = copy;
                                nv21Lock.notifyAll();
                            }
                        }
                        cam.addCallbackBuffer(data);
                    }
                }
            });
            camera.startPreview();
            previewStarted = true;
            showAddress();
        } catch (Exception e) {
            Log.e(TAG, "startPreview failed", e);
            infoView.setText("预览启动失败：" + e.getMessage());
        }
    }

    private void stopPreviewOnly() {
        if (camera == null || !previewStarted) {
            previewStarted = false;
            return;
        }
        try {
            camera.setPreviewCallbackWithBuffer(null);
            camera.stopPreview();
        } catch (Exception ignored) {
        }
        previewStarted = false;
    }

    private void encodeLoop() {
        while (encoding) {
            byte[] nv21;
            int width;
            int height;
            synchronized (nv21Lock) {
                while (encoding && pendingNv21 == null) {
                    try {
                        nv21Lock.wait(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                nv21 = pendingNv21;
                pendingNv21 = null;
                width = previewWidth;
                height = previewHeight;
            }
            if (!encoding || nv21 == null || width <= 0 || height <= 0) {
                continue;
            }
            try {
                YuvImage image = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
                ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                image.compressToJpeg(new Rect(0, 0, width, height), 50, jpeg);
                if (server != null) {
                    server.updateFrame(jpeg.toByteArray());
                }
                lastEncodedAt = System.currentTimeMillis();
            } catch (Exception e) {
                Log.w(TAG, "jpeg failed", e);
            }
        }
    }

    private void releaseCamera() {
        stopPreviewOnly();
        if (camera != null) {
            camera.release();
            camera = null;
        }
    }

    private String wifiIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!"wlan0".equals(nic.getName())) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "ip lookup failed", e);
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        encoding = false;
        synchronized (nv21Lock) {
            nv21Lock.notifyAll();
        }
        releaseCamera();
        if (server != null) {
            server.stop();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        super.onDestroy();
    }
}
