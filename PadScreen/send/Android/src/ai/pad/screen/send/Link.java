package ai.pad.screen.send;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * 读线程只读，编码线程在 writeLock 里写。读的时候不拿写锁，避免和平板互相堵住。
 */
final class Link {
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();

    Link(String host, int port) throws IOException {
        Socket opened = new Socket();
        try {
            opened.setTcpNoDelay(true);
            opened.setSendBufferSize(128 * 1024);
            opened.connect(new InetSocketAddress(host, port), 4000);
        } catch (Exception e) {
            try {
                opened.close();
            } catch (IOException ignored) {
            }
            if (e instanceof SocketTimeoutException) {
                throw new IOException("连接 " + host + " 超时");
            }
            throw new IOException("连接 " + host + " 失败");
        }
        socket = opened;
        in = opened.getInputStream();
        out = opened.getOutputStream();
    }

    void writeFrame(int type, byte[] payload) throws IOException {
        byte[] frame = Wire.frame(type, payload);
        synchronized (writeLock) {
            out.write(frame);
            out.flush();
        }
    }

    byte[] readFrame(int[] typeOut) throws IOException {
        byte[] header = readFully(5);
        typeOut[0] = header[0] & 0xff;
        int length = ((header[1] & 0xff) << 24)
                | ((header[2] & 0xff) << 16)
                | ((header[3] & 0xff) << 8)
                | (header[4] & 0xff);
        if (length < 0 || length > Wire.MAX_PAYLOAD) {
            throw new IOException("帧长度无效");
        }
        if (length == 0) {
            return new byte[0];
        }
        return readFully(length);
    }

    void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private byte[] readFully(int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = in.read(buffer, offset, length - offset);
            if (count < 0) {
                throw new EOFException("连接已断开");
            }
            offset += count;
        }
        return buffer;
    }
}
