package ai.pad.screen.send;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** 一帧：1 字节类型 + 4 字节大端长度 + 载荷。和平板接收端一致。 */
final class Wire {
    static final int HELLO = 1;
    static final int JPEG = 2;
    static final int TOUCH = 3;
    static final int PATCH = 4;
    static final int TOUCH_DOWN = 0;
    static final int TOUCH_MOVE = 1;
    static final int TOUCH_UP = 2;
    static final int MAX_PAYLOAD = 2 * 1024 * 1024;

    private Wire() {
    }

    static byte[] frame(int type, byte[] payload) {
        int length = payload == null ? 0 : payload.length;
        byte[] data = new byte[5 + length];
        data[0] = (byte) type;
        data[1] = (byte) ((length >>> 24) & 0xff);
        data[2] = (byte) ((length >>> 16) & 0xff);
        data[3] = (byte) ((length >>> 8) & 0xff);
        data[4] = (byte) (length & 0xff);
        if (length > 0) {
            System.arraycopy(payload, 0, data, 5, length);
        }
        return data;
    }

    static Hello parseHello(byte[] payload) {
        if (payload == null || payload.length < 4) {
            return null;
        }
        int width = ((payload[0] & 0xff) << 8) | (payload[1] & 0xff);
        int height = ((payload[2] & 0xff) << 8) | (payload[3] & 0xff);
        String name = new String(payload, 4, payload.length - 4, StandardCharsets.UTF_8);
        return new Hello(width, height, name);
    }

    static Touch parseTouch(byte[] payload) {
        if (payload == null || payload.length < 5) {
            return null;
        }
        int action = payload[0] & 0xff;
        int x = ((payload[1] & 0xff) << 8) | (payload[2] & 0xff);
        int y = ((payload[3] & 0xff) << 8) | (payload[4] & 0xff);
        return new Touch(action, x, y);
    }

    static byte[] packPatch(int x, int y, int w, int h, byte[] jpeg) {
        byte[] payload = new byte[8 + jpeg.length];
        payload[0] = (byte) ((x >>> 8) & 0xff);
        payload[1] = (byte) (x & 0xff);
        payload[2] = (byte) ((y >>> 8) & 0xff);
        payload[3] = (byte) (y & 0xff);
        payload[4] = (byte) ((w >>> 8) & 0xff);
        payload[5] = (byte) (w & 0xff);
        payload[6] = (byte) ((h >>> 8) & 0xff);
        payload[7] = (byte) (h & 0xff);
        System.arraycopy(jpeg, 0, payload, 8, jpeg.length);
        return payload;
    }

    static byte[] jpeg(android.graphics.Bitmap bitmap, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)) {
            return null;
        }
        byte[] data = out.toByteArray();
        if (data.length <= 0 || data.length > MAX_PAYLOAD) {
            return null;
        }
        return data;
    }

    static final class Hello {
        final int width;
        final int height;
        final String name;

        Hello(int width, int height, String name) {
            this.width = width;
            this.height = height;
            this.name = name == null ? "" : name;
        }
    }

    static final class Touch {
        final int action;
        final int x;
        final int y;

        Touch(int action, int x, int y) {
            this.action = action;
            this.x = x;
            this.y = y;
        }
    }
}
