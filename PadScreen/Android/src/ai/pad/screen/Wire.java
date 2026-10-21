package ai.pad.screen;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 一帧：1 字节类型 + 4 字节大端长度 + 载荷。 */
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

    static void writeFrame(OutputStream out, int type, byte[] payload) throws IOException {
        int length = payload == null ? 0 : payload.length;
        byte[] header = new byte[5];
        header[0] = (byte) type;
        header[1] = (byte) ((length >>> 24) & 0xff);
        header[2] = (byte) ((length >>> 16) & 0xff);
        header[3] = (byte) ((length >>> 8) & 0xff);
        header[4] = (byte) (length & 0xff);
        synchronized (out) {
            out.write(header);
            if (length > 0) {
                out.write(payload);
            }
            out.flush();
        }
    }

    static byte[] readFrame(InputStream in, int[] typeOut) throws IOException {
        byte[] header = readFully(in, 5);
        typeOut[0] = header[0] & 0xff;
        int length = ((header[1] & 0xff) << 24)
                | ((header[2] & 0xff) << 16)
                | ((header[3] & 0xff) << 8)
                | (header[4] & 0xff);
        if (length < 0 || length > MAX_PAYLOAD) {
            throw new IOException("bad frame length " + length);
        }
        if (length == 0) {
            return new byte[0];
        }
        return readFully(in, length);
    }

    static byte[] hello(int width, int height, String name) throws IOException {
        byte[] text = name.getBytes("UTF-8");
        byte[] payload = new byte[4 + text.length];
        payload[0] = (byte) ((width >>> 8) & 0xff);
        payload[1] = (byte) (width & 0xff);
        payload[2] = (byte) ((height >>> 8) & 0xff);
        payload[3] = (byte) (height & 0xff);
        System.arraycopy(text, 0, payload, 4, text.length);
        return payload;
    }

    static byte[] touch(int action, int x, int y) {
        byte[] payload = new byte[5];
        payload[0] = (byte) action;
        payload[1] = (byte) ((x >>> 8) & 0xff);
        payload[2] = (byte) (x & 0xff);
        payload[3] = (byte) ((y >>> 8) & 0xff);
        payload[4] = (byte) (y & 0xff);
        return payload;
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(buffer, offset, length - offset);
            if (n < 0) {
                throw new EOFException();
            }
            offset += n;
        }
        return buffer;
    }
}
