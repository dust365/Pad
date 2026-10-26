package ai.pad.screen.send;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class Session {
    static final int CONNECTING = 0;
    static final int LIVE = 1;

    private static final String TAG = "PadScreenSend";

    final Hub hub;
    final String key;
    final String host;
    final int port;
    String name;
    int width;
    int height;
    int page = Hub.PAGE_NOTE;
    String url = "";
    int phase = CONNECTING;

    private volatile boolean stopped;
    private volatile boolean sending;
    private volatile Link link;
    private ImageReader reader;
    private VirtualDisplay display;
    private HandlerThread imageThread;
    private Handler imageHandler;
    private ExecutorService encoder;
    private FrameEncoder frames;
    private long lastAccept;
    private int displayId = Display.INVALID_DISPLAY;
    private volatile boolean drew;
    private boolean listening;
    private boolean finger;
    private boolean toldTouch;
    private int displayGeneration;
    private volatile Fit fit;
    private Bitmap source;
    private Bitmap output;
    private Canvas outputCanvas;
    private byte[] outputBytes;
    private final Paint scalePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dest = new Rect();
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        public void onDisplayAdded(int displayId) {
        }

        public void onDisplayRemoved(int displayId) {
        }

        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                relayout();
            }
        }
    };

    Session(Hub hub, Finder.FoundPad pad) {
        this.hub = hub;
        host = pad.host;
        port = pad.port;
        key = pad.key();
        name = pad.name;
    }

    void start() {
        Thread thread = new Thread(new Runnable() {
            public void run() {
                readLoop();
            }
        }, "pad-read-" + host);
        thread.start();
    }

    void stop() {
        if (Looper.myLooper() != hub.main.getLooper()) {
            hub.main.post(new Runnable() {
                public void run() {
                    stop();
                }
            });
            return;
        }
        if (stopped) {
            return;
        }
        stopped = true;
        Link current = link;
        link = null;
        if (current != null) {
            current.close();
        }
        if (encoder != null) {
            encoder.shutdownNow();
            try {
                encoder.awaitTermination(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            encoder = null;
        }
        if (frames != null) {
            frames.release();
            frames = null;
        }
        if (listening) {
            DisplayManager displays = (DisplayManager) hub.app.getSystemService(Context.DISPLAY_SERVICE);
            if (displays != null) {
                displays.unregisterDisplayListener(displayListener);
            }
            listening = false;
        }
        displayGeneration++;
        if (display != null) {
            display.release();
            display = null;
        }
        if (imageHandler != null) {
            final ImageReader currentReader = reader;
            reader = null;
            imageHandler.post(new Runnable() {
                public void run() {
                    if (currentReader != null) {
                        currentReader.close();
                    }
                    recycleBitmaps();
                }
            });
            imageThread.quitSafely();
            imageHandler = null;
            imageThread = null;
        } else if (reader != null) {
            reader.close();
            reader = null;
        }
        DeskPresentation desk = hub.takeDesk(key);
        if (desk != null) {
            desk.markClosed();
            desk.dismiss();
        }
    }

    boolean ended() {
        return stopped;
    }

    int displayId() {
        return displayId;
    }

    private void readLoop() {
        try {
            Link current = new Link(host, port);
            link = current;
            int[] type = new int[1];
            byte[] payload = current.readFrame(type);
            if (type[0] != Wire.HELLO) {
                throw new IOException("平板没有完成握手");
            }
            final Wire.Hello hello = Wire.parseHello(payload);
            if (hello == null) {
                throw new IOException("平板握手无效");
            }
            if (hello.width < 2 || hello.height < 2 || hello.width > 4096 || hello.height > 4096) {
                throw new IOException("平板分辨率无效");
            }
            hub.main.post(new Runnable() {
                public void run() {
                    onHello(hello);
                }
            });
            while (!stopped) {
                payload = current.readFrame(type);
                if (type[0] == Wire.TOUCH) {
                    final Wire.Touch touch = Wire.parseTouch(payload);
                    if (touch != null) {
                        hub.main.post(new Runnable() {
                            public void run() {
                                dispatchTouch(touch);
                            }
                        });
                    }
                }
            }
        } catch (Exception e) {
            if (!stopped) {
                final String message = Hub.text(e);
                hub.main.post(new Runnable() {
                    public void run() {
                        hub.fail(Session.this, message);
                    }
                });
            }
        }
    }

    private void onHello(Wire.Hello hello) {
        if (stopped) {
            return;
        }
        width = hello.width;
        height = hello.height;
        if (hello.name.length() > 0) {
            name = hello.name;
        }
        try {
            openDisplay();
        } catch (RuntimeException e) {
            Log.e(TAG, "display", e);
            String message = e instanceof SecurityException
                    ? "系统没有允许投屏"
                    : Hub.text(e);
            hub.fail(this, message);
            return;
        } catch (Exception e) {
            Log.e(TAG, "display", e);
            hub.fail(this, Hub.text(e));
            return;
        }
        phase = LIVE;
        Log.i(TAG, "握手 " + name + " " + width + "x" + height + " " + host);
        hub.onSessionsChanged();
    }

    private void openDisplay() throws IOException {
        imageThread = new HandlerThread("pad-image-" + port);
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());
        frames = new FrameEncoder();
        encoder = Executors.newSingleThreadExecutor();
        DisplayManager displays = (DisplayManager) hub.app.getSystemService(Context.DISPLAY_SERVICE);
        if (displays == null) {
            throw new IOException("无法投屏");
        }
        if (!listening) {
            displays.registerDisplayListener(displayListener, hub.main);
            listening = true;
        }
        MediaProjection projection = hub.projection();
        if (projection == null) {
            throw new IOException("没有投屏授权");
        }
        Fit next = measure(width, height);
        if (next == null) {
            throw new IOException("读不到手机屏幕尺寸");
        }
        openMirror(projection, next);
    }

    private void relayout() {
        if (stopped || reader == null) {
            return;
        }
        Fit next = measure(width, height);
        Fit current = fit;
        if (next == null || current == null) {
            return;
        }
        if (next.phoneW == current.phoneW && next.phoneH == current.phoneH) {
            return;
        }
        MediaProjection projection = hub.projection();
        if (projection == null) {
            hub.fail(this, "投屏授权已结束");
            return;
        }
        try {
            openMirror(projection, next);
        } catch (RuntimeException e) {
            Log.e(TAG, "relayout", e);
            hub.fail(this, Hub.text(e));
        } catch (IOException e) {
            Log.e(TAG, "relayout", e);
            hub.fail(this, Hub.text(e));
        }
    }

    private void openMirror(MediaProjection projection, Fit next) throws IOException {
        displayGeneration++;
        final int generation = displayGeneration;
        VirtualDisplay previousDisplay = display;
        display = null;
        final ImageReader previousReader = reader;
        reader = null;
        if (previousDisplay != null) {
            previousDisplay.release();
        }
        if (previousReader != null && imageHandler != null) {
            imageHandler.post(new Runnable() {
                public void run() {
                    previousReader.close();
                }
            });
        }
        fit = next;
        drew = false;
        reader = ImageReader.newInstance(next.contentW, next.contentH, PixelFormat.RGBA_8888, 3);
        // 监听必须在创建虚拟屏之前装上。否则前几帧把队列占满，之后再也不会回调，平板一直黑屏。
        final ImageReader currentReader = reader;
        currentReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            public void onImageAvailable(ImageReader imageReader) {
                if (imageReader != reader) {
                    Image dropped = imageReader.acquireLatestImage();
                    if (dropped != null) {
                        dropped.close();
                    }
                    return;
                }
                onImage(imageReader);
            }
        }, imageHandler);
        int dpi = hub.app.getResources().getDisplayMetrics().densityDpi;
        if (dpi < 10) {
            dpi = 160;
        }
        display = projection.createVirtualDisplay(
                "PadScreen-" + name,
                next.contentW,
                next.contentH,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(),
                new VirtualDisplay.Callback() {
                    public void onStopped() {
                        if (stopped || generation != displayGeneration) {
                            return;
                        }
                        hub.fail(Session.this, "投屏已停止");
                    }
                },
                hub.main);
        if (display == null || display.getDisplay() == null) {
            throw new IOException("无法投屏");
        }
        displayId = display.getDisplay().getDisplayId();
        if (displayId == Display.INVALID_DISPLAY || displayId == Display.DEFAULT_DISPLAY) {
            throw new IOException("无法投屏");
        }
        Log.i(TAG, "mirror " + next.phoneW + "x" + next.phoneH
                + " -> " + next.contentW + "x" + next.contentH
                + " at " + next.offX + "," + next.offY
                + " on " + width + "x" + height);
    }

    private Fit measure(int padW, int padH) {
        DisplayManager displays = (DisplayManager) hub.app.getSystemService(Context.DISPLAY_SERVICE);
        Display display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null || padW < 2 || padH < 2) {
            return null;
        }
        Point size = new Point();
        display.getRealSize(size);
        if (size.x < 2 || size.y < 2) {
            return null;
        }
        double scale = Math.min(padW / (double) size.x, padH / (double) size.y);
        int contentW = Math.max(2, (int) Math.round(size.x * scale));
        int contentH = Math.max(2, (int) Math.round(size.y * scale));
        contentW &= ~1;
        contentH &= ~1;
        if (contentW < 2) {
            contentW = 2;
        }
        if (contentH < 2) {
            contentH = 2;
        }
        if (contentW > padW) {
            contentW = padW & ~1;
        }
        if (contentH > padH) {
            contentH = padH & ~1;
        }
        return new Fit(size.x, size.y, contentW, contentH, (padW - contentW) / 2, (padH - contentH) / 2);
    }

    private void onImage(ImageReader imageReader) {
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null || stopped || sending) {
                return;
            }
            Fit current = fit;
            if (current == null) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastAccept < 66) {
                return;
            }
            int sourceWidth = image.getWidth();
            int sourceHeight = image.getHeight();
            byte[] packed = pack(image, sourceWidth, sourceHeight);
            if (packed == null) {
                return;
            }
            if (!drew) {
                if (unset(packed)) {
                    return;
                }
                drew = true;
                Log.i(TAG, "first frame");
            }
            byte[] frame = compose(packed, sourceWidth, sourceHeight, current);
            if (frame == null) {
                return;
            }
            ExecutorService pool = encoder;
            if (pool == null) {
                return;
            }
            sending = true;
            lastAccept = now;
            final byte[] copy = frame;
            pool.execute(new Runnable() {
                public void run() {
                    encodeAndSend(copy);
                }
            });
        } catch (RuntimeException e) {
            sending = false;
            if (!stopped) {
                Log.w(TAG, "image", e);
            }
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private void encodeAndSend(byte[] packed) {
        try {
            Link current = link;
            FrameEncoder encoderFrames = frames;
            if (stopped || current == null || encoderFrames == null) {
                return;
            }
            List<FrameEncoder.Frame> list = encoderFrames.make(packed, width, height);
            for (int i = 0; i < list.size(); i++) {
                if (stopped) {
                    return;
                }
                FrameEncoder.Frame frame = list.get(i);
                current.writeFrame(frame.type, frame.payload);
            }
        } catch (Exception e) {
            if (!stopped) {
                final String message = Hub.text(e);
                hub.main.post(new Runnable() {
                    public void run() {
                        hub.fail(Session.this, message);
                    }
                });
            }
        } finally {
            sending = false;
        }
    }

    /** 虚拟屏还没写出画面时，缓冲区是全 0。 */
    private static boolean unset(byte[] packed) {
        int step = Math.max(4, packed.length / 64);
        step -= step % 4;
        if (step < 4) {
            step = 4;
        }
        for (int i = 0; i + 3 < packed.length; i += step) {
            if ((packed[i] | packed[i + 1] | packed[i + 2] | packed[i + 3]) != 0) {
                return false;
            }
        }
        return true;
    }

    private byte[] pack(Image image, int imageWidth, int imageHeight) {
        if (imageWidth < 2 || imageHeight < 2) {
            return null;
        }
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        byte[] packed = new byte[imageWidth * imageHeight * 4];
        int start = buffer.position();
        try {
            if (pixelStride == 4) {
                for (int y = 0; y < imageHeight; y++) {
                    buffer.position(start + y * rowStride);
                    buffer.get(packed, y * imageWidth * 4, imageWidth * 4);
                }
            } else {
                for (int y = 0; y < imageHeight; y++) {
                    for (int x = 0; x < imageWidth; x++) {
                        buffer.position(start + y * rowStride + x * pixelStride);
                        buffer.get(packed, (y * imageWidth + x) * 4, Math.min(4, pixelStride));
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "pack", e);
            return null;
        }
        return packed;
    }

    private byte[] compose(byte[] packed, int sourceWidth, int sourceHeight, Fit current) {
        if (source == null || source.getWidth() != sourceWidth || source.getHeight() != sourceHeight) {
            if (source != null) {
                source.recycle();
            }
            source = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888);
        }
        source.copyPixelsFromBuffer(ByteBuffer.wrap(packed));
        if (output == null || output.getWidth() != width || output.getHeight() != height) {
            if (output != null) {
                output.recycle();
            }
            output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            outputCanvas = new Canvas(output);
            outputBytes = new byte[width * height * 4];
        }
        outputCanvas.drawColor(Color.BLACK);
        dest.set(current.offX, current.offY, current.offX + current.contentW, current.offY + current.contentH);
        outputCanvas.drawBitmap(source, null, dest, scalePaint);
        ByteBuffer pixels = ByteBuffer.wrap(outputBytes);
        output.copyPixelsToBuffer(pixels);
        byte[] copy = new byte[outputBytes.length];
        System.arraycopy(outputBytes, 0, copy, 0, outputBytes.length);
        return copy;
    }

    private void recycleBitmaps() {
        if (source != null) {
            source.recycle();
            source = null;
        }
        outputCanvas = null;
        if (output != null) {
            output.recycle();
            output = null;
        }
        outputBytes = null;
    }

    private void dispatchTouch(Wire.Touch touch) {
        if (stopped) {
            return;
        }
        Fit current = fit;
        if (current == null || current.contentW < 2 || current.contentH < 2) {
            return;
        }
        boolean inside = touch.x >= current.offX && touch.y >= current.offY
                && touch.x < current.offX + current.contentW
                && touch.y < current.offY + current.contentH;
        if (touch.action == Wire.TOUCH_DOWN) {
            if (!inside) {
                return;
            }
            finger = true;
        } else if (!finger) {
            return;
        } else if (touch.action == Wire.TOUCH_UP) {
            finger = false;
        }
        if (!TouchRelay.ready()) {
            if (!toldTouch) {
                toldTouch = true;
                hub.showError("画面已经投上。要在平板上点手机，请先打开无障碍服务。");
            }
            return;
        }
        int x = touch.x;
        int y = touch.y;
        if (x < current.offX) {
            x = current.offX;
        } else if (x >= current.offX + current.contentW) {
            x = current.offX + current.contentW - 1;
        }
        if (y < current.offY) {
            y = current.offY;
        } else if (y >= current.offY + current.contentH) {
            y = current.offY + current.contentH - 1;
        }
        float screenX = (x - current.offX) * (float) current.phoneW / (float) current.contentW;
        float screenY = (y - current.offY) * (float) current.phoneH / (float) current.contentH;
        float maxX = current.phoneW - 2f;
        float maxY = current.phoneH - 2f;
        if (maxX < 0f) {
            maxX = 0f;
        }
        if (maxY < 0f) {
            maxY = 0f;
        }
        if (screenX < 0f) {
            screenX = 0f;
        } else if (screenX > maxX) {
            screenX = maxX;
        }
        if (screenY < 0f) {
            screenY = 0f;
        } else if (screenY > maxY) {
            screenY = maxY;
        }
        TouchRelay.deliver(touch.action, screenX, screenY);
    }

    /** 手机整屏按比例放进平板，多出来的边是黑的。 */
    private static final class Fit {
        final int phoneW;
        final int phoneH;
        final int contentW;
        final int contentH;
        final int offX;
        final int offY;

        Fit(int phoneW, int phoneH, int contentW, int contentH, int offX, int offY) {
            this.phoneW = phoneW;
            this.phoneH = phoneH;
            this.contentW = contentW;
            this.contentH = contentH;
            this.offX = offX;
            this.offY = offY;
        }
    }
}
