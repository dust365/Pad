package ai.pad.screen.send;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

final class Hub {
    static final int PAGE_NOTE = 0;
    static final int PAGE_BROWSER = 1;

    private static final String TAG = "PadScreenSend";

    final Context app;
    final Handler main = new Handler(Looper.getMainLooper());
    final Finder finder;

    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<String, Session>();
    private final HashMap<String, DeskPresentation> desks = new HashMap<String, DeskPresentation>();
    private final File noteFile;
    private final Runnable saveNote = new Runnable() {
        public void run() {
            writeNote();
        }
    };

    private MainActivity ui;
    private SendService service;
    private String note = "";
    private String error;
    private boolean finderStarted;
    private MediaProjection projection;
    private int pendingCode;
    private Intent pendingData;
    private Finder.FoundPad pendingConnect;

    Hub(Context app) {
        this.app = app;
        finder = new Finder(this, app);
        noteFile = new File(app.getFilesDir(), "note.txt");
        note = readNote();
    }

    void ensureFinder() {
        if (!finderStarted) {
            finderStarted = true;
            finder.start();
        }
    }

    void setUi(MainActivity activity) {
        ui = activity;
    }

    void clearUi(MainActivity activity) {
        if (ui == activity) {
            ui = null;
        }
    }

    void attachService(SendService value) {
        service = value;
    }

    void detachService(SendService value) {
        if (service == value) {
            service = null;
        }
    }

    String note() {
        return note;
    }

    String error() {
        return error;
    }

    void showError(String message) {
        error = message;
        refreshUi();
    }

    void clearError() {
        error = null;
        refreshUi();
    }

    Session session(String key) {
        return sessions.get(key);
    }

    ArrayList<Session> sessions() {
        return new ArrayList<Session>(sessions.values());
    }

    boolean sessionsEmpty() {
        return sessions.isEmpty();
    }

    void onFinderChanged() {
        refreshUi();
    }

    void connect(Finder.FoundPad pad) {
        if (sessions.containsKey(pad.key())) {
            return;
        }
        error = null;
        Session session = new Session(this, pad);
        sessions.put(session.key, session);
        onSessionsChanged();
        session.start();
    }

    void disconnect(String key) {
        Session session = sessions.remove(key);
        if (session != null) {
            session.stop();
        }
        error = null;
        stopProjectionIfIdle();
        onSessionsChanged();
    }

    void disconnectAll() {
        ArrayList<Session> copy = new ArrayList<Session>(sessions.values());
        sessions.clear();
        for (int i = 0; i < copy.size(); i++) {
            copy.get(i).stop();
        }
        error = null;
        stopProjectionIfIdle();
        onSessionsChanged();
    }

    void fail(final Session session, final String message) {
        if (Looper.myLooper() != main.getLooper()) {
            main.post(new Runnable() {
                public void run() {
                    fail(session, message);
                }
            });
            return;
        }
        Session current = sessions.get(session.key);
        if (current != session) {
            session.stop();
            return;
        }
        sessions.remove(session.key);
        session.stop();
        String who = session.name == null || session.name.length() == 0 ? session.host : session.name;
        error = who + "：" + message;
        onSessionsChanged();
    }

    boolean projectionReady() {
        return projection != null;
    }

    MediaProjection projection() {
        return projection;
    }

    void prepareCapture(int code, Intent data, Finder.FoundPad pad) {
        pendingCode = code;
        pendingData = data;
        pendingConnect = pad;
        ensureService();
    }

    void consumeCapture() {
        Intent data = pendingData;
        if (data == null) {
            return;
        }
        int code = pendingCode;
        Finder.FoundPad pad = pendingConnect;
        pendingData = null;
        pendingConnect = null;
        if (projection != null) {
            if (pad != null) {
                connect(pad);
            }
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager) app.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            showError("这台手机不能投屏。");
            return;
        }
        final MediaProjection created;
        try {
            created = manager.getMediaProjection(code, data);
        } catch (RuntimeException e) {
            Log.e(TAG, "projection", e);
            showError("投屏没有开始：" + text(e));
            return;
        }
        if (created == null) {
            showError("没有拿到投屏授权。");
            return;
        }
        created.registerCallback(new MediaProjection.Callback() {
            public void onStop() {
                if (projection != created) {
                    return;
                }
                projection = null;
                if (!sessions.isEmpty()) {
                    disconnectAll();
                }
                showError("投屏已结束。");
            }
        }, main);
        projection = created;
        if (pad != null) {
            connect(pad);
        }
    }

    void onSessionsChanged() {
        if (sessions.isEmpty() && projection == null) {
            if (service != null) {
                service.stopIfIdle();
            }
        } else {
            ensureService();
            if (service != null) {
                service.show();
            }
        }
        refreshUi();
    }

    void setPage(String key, int page) {
        Session session = sessions.get(key);
        if (session == null) {
            return;
        }
        session.page = page;
        DeskPresentation desk = desks.get(key);
        if (desk != null) {
            desk.bind();
        }
        refreshUi();
    }

    void openUrl(String key, String raw) {
        Session session = sessions.get(key);
        if (session == null) {
            return;
        }
        String url = raw == null ? "" : raw.trim();
        if (url.length() == 0) {
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        session.page = PAGE_BROWSER;
        session.url = url;
        DeskPresentation desk = desks.get(key);
        if (desk != null) {
            desk.bind();
        }
        refreshUi();
    }

    void goBack(String key) {
        DeskPresentation desk = desks.get(key);
        if (desk != null) {
            desk.goBack();
        }
    }

    void setNote(String text) {
        if (text == null) {
            text = "";
        }
        if (text.equals(note)) {
            return;
        }
        note = text;
        ArrayList<DeskPresentation> copy = new ArrayList<DeskPresentation>(desks.values());
        for (int i = 0; i < copy.size(); i++) {
            copy.get(i).showNote(text);
        }
        main.removeCallbacks(saveNote);
        main.postDelayed(saveNote, 400);
    }

    Context launchContext() {
        return ui != null ? ui : app;
    }

    void registerDesk(DeskPresentation desk) {
        DeskPresentation previous = desks.put(desk.key(), desk);
        if (previous != null && previous != desk) {
            previous.markClosed();
            previous.dismiss();
        }
        desk.bind();
    }

    void onDeskDestroyed(DeskPresentation desk) {
        if (desks.get(desk.key()) != desk) {
            return;
        }
        desks.remove(desk.key());
        Session session = sessions.get(desk.key());
        if (session != null && !session.ended()) {
            fail(session, "副屏桌面已关闭");
        }
    }

    void rejectDesk(String key) {
        Session session = sessions.get(key);
        if (session != null) {
            fail(session, "虚拟屏没有留在副屏上");
        }
    }

    DeskPresentation desk(String key) {
        return desks.get(key);
    }

    DeskPresentation takeDesk(String key) {
        return desks.remove(key);
    }

    String notificationText() {
        ArrayList<String> live = new ArrayList<String>();
        ArrayList<String> connecting = new ArrayList<String>();
        ArrayList<Session> copy = sessions();
        for (int i = 0; i < copy.size(); i++) {
            Session session = copy.get(i);
            if (session.phase == Session.LIVE) {
                live.add(session.name);
            } else {
                connecting.add(session.name);
            }
        }
        if (!live.isEmpty()) {
            return "正在投送手机屏幕到 " + join(live);
        }
        if (!connecting.isEmpty()) {
            return "正在连接 " + join(connecting);
        }
        if (projection != null) {
            return "投屏授权还在";
        }
        return "副屏控制";
    }

    static String text(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        if (message == null || message.length() == 0) {
            return error.getClass().getSimpleName();
        }
        return message;
    }

    private void refreshUi() {
        if (ui != null) {
            ui.render();
        }
    }

    private void stopProjectionIfIdle() {
        if (!sessions.isEmpty()) {
            return;
        }
        MediaProjection current = projection;
        projection = null;
        if (current == null) {
            return;
        }
        try {
            current.stop();
        } catch (RuntimeException e) {
            Log.w(TAG, "stop projection", e);
        }
    }

    private void ensureService() {
        Intent intent = new Intent(app, SendService.class);
        app.startForegroundService(intent);
    }

    private String readNote() {
        if (!noteFile.isFile()) {
            return "";
        }
        try {
            FileInputStream in = new FileInputStream(noteFile);
            try {
                byte[] data = new byte[(int) noteFile.length()];
                int offset = 0;
                while (offset < data.length) {
                    int count = in.read(data, offset, data.length - offset);
                    if (count < 0) {
                        break;
                    }
                    offset += count;
                }
                return new String(data, 0, offset, StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "read note", e);
            return "";
        }
    }

    private void writeNote() {
        try {
            FileOutputStream out = new FileOutputStream(noteFile);
            try {
                out.write(note.getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "write note", e);
        }
    }

    private static String join(ArrayList<String> parts) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                builder.append("、");
            }
            builder.append(parts.get(i));
        }
        return builder.toString();
    }
}
