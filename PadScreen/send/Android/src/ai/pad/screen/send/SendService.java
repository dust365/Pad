package ai.pad.screen.send;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

public class SendService extends Service {
    static final String ACTION_DISCONNECT = "ai.pad.screen.send.DISCONNECT";

    private static final String TAG = "PadScreenSend";
    private static final String CHANNEL = "cast";
    private static final int NOTE_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "投屏", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
        ((SendApp) getApplication()).hub().attachService(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        show();
        Hub hub = ((SendApp) getApplication()).hub();
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            hub.disconnectAll();
        } else {
            hub.consumeCapture();
            if (hub.sessionsEmpty() && !hub.projectionReady()) {
                stopIfIdle();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ((SendApp) getApplication()).hub().detachService(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    void show() {
        Notification notification = build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                Api34.start(this, NOTE_ID, notification);
            } else if (Build.VERSION.SDK_INT >= 29) {
                Api29.start(this, NOTE_ID, notification);
            } else {
                startForeground(NOTE_ID, notification);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "foreground", e);
            try {
                startForeground(NOTE_ID, notification);
            } catch (RuntimeException again) {
                Log.e(TAG, "foreground fallback", again);
            }
        }
    }

    void stopIfIdle() {
        Hub hub = ((SendApp) getApplication()).hub();
        if (!hub.sessionsEmpty() || hub.projectionReady()) {
            return;
        }
        stopForeground(true);
        stopSelf();
    }

    /** 三参数 startForeground 从 API 29 才有。类型常量按系统版本分开，旧系统不会去解析。 */
    static final class Api29 {
        static void start(Service service, int id, Notification notification) {
            service.startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        }
    }

    static final class Api34 {
        static void start(Service service, int id, Notification notification) {
            service.startForeground(
                    id,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        }
    }

    private Notification build() {
        Hub hub = ((SendApp) getApplication()).hub();
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(
                this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent disconnect = new Intent(this, SendService.class);
        disconnect.setAction(ACTION_DISCONNECT);
        PendingIntent disconnectIntent = PendingIntent.getService(
                this, 2, disconnect, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action action = new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "断开",
                disconnectIntent).build();
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle("副屏控制")
                .setContentText(hub.notificationText())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(content)
                .addAction(action)
                .build();
    }
}
