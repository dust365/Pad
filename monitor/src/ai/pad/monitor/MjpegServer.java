package ai.pad.monitor;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;

/**
 * 在局域网提供一张查看页、一张单帧图和一路 MJPEG。
 * 访问必须带 ?k=TOKEN，避免同一 Wi-Fi 里的其他人直接打开。
 */
final class MjpegServer {
    private static final String BOUNDARY = "frame";
    private static final int MAX_CLIENTS = 3;

    private final int port;
    private final String token;
    private final Object frameLock = new Object();
    private final Object clientLock = new Object();

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private byte[] latestJpeg;
    private int frameSeq;
    private int clients;

    MjpegServer(int port, String token) {
        this.port = port;
        this.token = token;
    }

    int port() {
        return port;
    }

    void start() throws Exception {
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        running = true;
        acceptThread = new Thread(new Runnable() {
            public void run() {
                acceptLoop();
            }
        }, "mjpeg-accept");
        acceptThread.start();
    }

    void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        }
    }

    void updateFrame(byte[] jpeg) {
        synchronized (frameLock) {
            latestJpeg = jpeg;
            frameSeq++;
            frameLock.notifyAll();
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                synchronized (clientLock) {
                    if (clients >= MAX_CLIENTS) {
                        reject(socket);
                        continue;
                    }
                    clients++;
                }
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        try {
                            handle(socket);
                        } finally {
                            synchronized (clientLock) {
                                clients--;
                            }
                            try {
                                socket.close();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }, "mjpeg-client");
                t.start();
            } catch (Exception e) {
                if (running) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void reject(Socket socket) {
        try {
            writeText(socket, 503, "too many viewers");
            socket.close();
        } catch (Exception ignored) {
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(10000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "ISO-8859-1"));
            String request = reader.readLine();
            if (request == null || request.length() == 0) {
                return;
            }
            String line;
            while ((line = reader.readLine()) != null && line.length() != 0) {
            }
            String[] parts = request.split(" ");
            if (parts.length < 2) {
                writeText(socket, 400, "bad request");
                return;
            }
            String raw = parts[1];
            int q = raw.indexOf('?');
            String path = q >= 0 ? raw.substring(0, q) : raw;
            String query = q >= 0 ? raw.substring(q + 1) : "";
            if (!token.equals(queryValue(query, "k"))) {
                writeText(socket, 401, "unauthorized");
                return;
            }
            if ("/stream".equals(path)) {
                writeStream(socket);
            } else if ("/snap".equals(path)) {
                writeSnap(socket);
            } else {
                writePage(socket);
            }
        } catch (SocketException ignored) {
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void writePage(Socket socket) throws Exception {
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>U03H1</title><style>body{margin:0;background:#111;color:#eee;"
                + "font-family:sans-serif}img{width:100%;height:auto;background:#000}"
                + "p{margin:8px}</style></head><body><p>U03H1 局域网画面</p>"
                + "<img src=\"/stream?k=" + token + "\" alt=\"camera\">"
                + "</body></html>";
        byte[] body = html.getBytes("UTF-8");
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.0 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
        out.write(body);
        out.flush();
    }

    private void writeSnap(Socket socket) throws Exception {
        byte[] jpeg = waitJpeg(0, 3000);
        OutputStream out = socket.getOutputStream();
        if (jpeg == null) {
            writeText(socket, 503, "no frame yet");
            return;
        }
        out.write(("HTTP/1.0 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: "
                + jpeg.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                .getBytes("ISO-8859-1"));
        out.write(jpeg);
        out.flush();
    }

    private void writeStream(Socket socket) throws Exception {
        socket.setSoTimeout(0);
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.0 200 OK\r\nConnection: close\r\nCache-Control: no-cache\r\n"
                + "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n\r\n")
                .getBytes("ISO-8859-1"));
        int seen = 0;
        while (running) {
            byte[] jpeg;
            int seq;
            synchronized (frameLock) {
                while (running && frameSeq == seen) {
                    frameLock.wait(2000);
                }
                jpeg = latestJpeg;
                seq = frameSeq;
            }
            if (!running || jpeg == null) {
                continue;
            }
            seen = seq;
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            header.write(("--" + BOUNDARY + "\r\nContent-Type: image/jpeg\r\nContent-Length: "
                    + jpeg.length + "\r\n\r\n").getBytes("ISO-8859-1"));
            out.write(header.toByteArray());
            out.write(jpeg);
            out.write("\r\n".getBytes("ISO-8859-1"));
            out.flush();
        }
    }

    private byte[] waitJpeg(int previousSeq, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (frameLock) {
            while (latestJpeg == null || frameSeq == previousSeq) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return latestJpeg;
                }
                try {
                    frameLock.wait(left);
                } catch (InterruptedException e) {
                    return latestJpeg;
                }
            }
            return latestJpeg;
        }
    }

    private void writeText(Socket socket, int code, String message) throws Exception {
        byte[] body = message.getBytes("UTF-8");
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.0 " + code + "\r\nContent-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
                .getBytes("ISO-8859-1"));
        out.write(body);
        out.flush();
    }

    private static String queryValue(String query, String key) {
        String[] pairs = query.split("&");
        for (int i = 0; i < pairs.length; i++) {
            int eq = pairs[i].indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = decode(pairs[i].substring(0, eq));
            if (key.equals(name)) {
                return decode(pairs[i].substring(eq + 1));
            }
        }
        return "";
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
