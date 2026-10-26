package ai.pad.screen.send;

import android.graphics.Bitmap;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 32 像素分块。超过 3 块或面积超过一半就改发整帧。
 * 有变化时大约 20 秒补一帧整图。原点在左上角，不翻转 Y。
 */
final class FrameEncoder {
    static final class Frame {
        final int type;
        final byte[] payload;

        Frame(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    private static final int TILE = 32;
    private static final int FULL_QUALITY = 86;
    private static final int PATCH_QUALITY = 92;
    private static final long KEYFRAME_MS = 20000;

    private byte[] previous = new byte[0];
    private long lastKeyframe;
    private Bitmap bitmap;

    List<Frame> make(byte[] packed, int width, int height) {
        Bitmap image = bitmap(packed, width, height);
        if (image == null) {
            return new ArrayList<Frame>();
        }
        if (previous.length != packed.length) {
            return full(image, packed);
        }
        List<Patch> regions = changed(packed, width, height);
        long now = SystemClock.elapsedRealtime();
        if (regions == null || (!regions.isEmpty() && now - lastKeyframe >= KEYFRAME_MS)) {
            return full(image, packed);
        }
        if (regions.isEmpty()) {
            return new ArrayList<Frame>();
        }
        List<Frame> frames = new ArrayList<Frame>();
        for (int i = 0; i < regions.size(); i++) {
            Patch region = regions.get(i);
            Bitmap tile = Bitmap.createBitmap(image, region.x, region.y, region.w, region.h);
            byte[] jpeg = Wire.jpeg(tile, PATCH_QUALITY);
            if (jpeg == null) {
                return full(image, packed);
            }
            frames.add(new Frame(Wire.PATCH, Wire.packPatch(region.x, region.y, region.w, region.h, jpeg)));
        }
        remember(packed);
        return frames;
    }

    void release() {
        if (bitmap != null) {
            bitmap.recycle();
            bitmap = null;
        }
        previous = new byte[0];
    }

    private List<Frame> full(Bitmap image, byte[] packed) {
        byte[] jpeg = Wire.jpeg(image, FULL_QUALITY);
        if (jpeg == null) {
            return new ArrayList<Frame>();
        }
        remember(packed);
        lastKeyframe = SystemClock.elapsedRealtime();
        ArrayList<Frame> frames = new ArrayList<Frame>();
        frames.add(new Frame(Wire.JPEG, jpeg));
        return frames;
    }

    private void remember(byte[] packed) {
        if (previous.length != packed.length) {
            previous = new byte[packed.length];
        }
        System.arraycopy(packed, 0, previous, 0, packed.length);
    }

    private Bitmap bitmap(byte[] packed, int width, int height) {
        if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
            if (bitmap != null) {
                bitmap.recycle();
            }
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        try {
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(packed));
        } catch (RuntimeException e) {
            return null;
        }
        return bitmap;
    }

    /** 变化太大时返回 null，调用方改发整帧。没有变化时返回空列表。 */
    private List<Patch> changed(byte[] pixels, int width, int height) {
        int stride = width * 4;
        int cols = (width + TILE - 1) / TILE;
        int rows = (height + TILE - 1) / TILE;
        boolean[] changed = new boolean[cols * rows];
        int changedCount = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int x = col * TILE;
                int y = row * TILE;
                int w = Math.min(TILE, width - x);
                int h = Math.min(TILE, height - y);
                boolean dirty = false;
                for (int line = y; line < y + h; line++) {
                    int offset = line * stride + x * 4;
                    if (!same(pixels, previous, offset, w * 4)) {
                        dirty = true;
                        break;
                    }
                }
                if (dirty) {
                    changed[row * cols + col] = true;
                    changedCount++;
                }
            }
        }
        if (changedCount == 0) {
            return new ArrayList<Patch>();
        }
        if (changedCount * TILE * TILE * 2 > width * height) {
            return null;
        }
        boolean[] seen = new boolean[changed.length];
        ArrayList<Patch> regions = new ArrayList<Patch>();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int start = row * cols + col;
                if (!changed[start] || seen[start]) {
                    continue;
                }
                ArrayList<int[]> stack = new ArrayList<int[]>();
                stack.add(new int[]{col, row});
                seen[start] = true;
                int minCol = col;
                int maxCol = col;
                int minRow = row;
                int maxRow = row;
                for (int index = 0; index < stack.size(); index++) {
                    int[] current = stack.get(index);
                    int[][] neighbors = new int[][]{
                            {current[0] - 1, current[1]},
                            {current[0] + 1, current[1]},
                            {current[0], current[1] - 1},
                            {current[0], current[1] + 1}
                    };
                    for (int n = 0; n < neighbors.length; n++) {
                        int nx = neighbors[n][0];
                        int ny = neighbors[n][1];
                        if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) {
                            continue;
                        }
                        int neighborIndex = ny * cols + nx;
                        if (!changed[neighborIndex] || seen[neighborIndex]) {
                            continue;
                        }
                        seen[neighborIndex] = true;
                        stack.add(new int[]{nx, ny});
                        minCol = Math.min(minCol, nx);
                        maxCol = Math.max(maxCol, nx);
                        minRow = Math.min(minRow, ny);
                        maxRow = Math.max(maxRow, ny);
                    }
                }
                int x = minCol * TILE;
                int y = minRow * TILE;
                int w = Math.min(width - x, (maxCol - minCol + 1) * TILE);
                int h = Math.min(height - y, (maxRow - minRow + 1) * TILE);
                if (w < 2 || h < 2) {
                    continue;
                }
                regions.add(new Patch(x, y, w, h));
                if (regions.size() > 3) {
                    return null;
                }
            }
        }
        int area = 0;
        for (int i = 0; i < regions.size(); i++) {
            Patch region = regions.get(i);
            area += region.w * region.h;
        }
        if (area * 2 > width * height) {
            return null;
        }
        return regions;
    }

    private static boolean same(byte[] left, byte[] right, int offset, int length) {
        for (int i = 0; i < length; i++) {
            if (left[offset + i] != right[offset + i]) {
                return false;
            }
        }
        return true;
    }

    private static final class Patch {
        final int x;
        final int y;
        final int w;
        final int h;

        Patch(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }
}
