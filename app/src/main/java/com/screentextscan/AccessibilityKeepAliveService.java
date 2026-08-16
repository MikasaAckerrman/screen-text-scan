package com.screentextscan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Удерживает процесс службы доступности на прошивках vivo/OriginOS.
 *
 * Стандартный Android сам переподключает AccessibilityService после убийства
 * процесса. OriginOS при очистке карточки приложения иногда останавливает весь
 * пакет и снимает службу из активных. Foreground-service с START_STICKY и
 * stopWithTask=false не даёт очистителю принять обычный свайп за полный stop.
 */
public class AccessibilityKeepAliveService extends Service {

    private static final String CHANNEL = "sts_accessibility_keepalive";
    private static final int NOTIFICATION_ID = 42;
    private static final long CHECK_INTERVAL_MS = 60_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable permissionCheck = new Runnable() {
        @Override
        public void run() {
            if (!accessibilityEnabled()) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return;
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    public static void start(Context context) {
        if (!Permissions.isAccessibilityMasterOn(context)
                || !Permissions.isAccessibilityEnabled(context)) return;
        try {
            context.startForegroundService(new Intent(
                    context, AccessibilityKeepAliveService.class));
        } catch (RuntimeException ignored) {
            // Force-stop и некоторые OEM background-start запреты обязательны
            // для приложений. Следующий видимый запуск попробует снова.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!accessibilityEnabled()) {
            stopSelf();
            return;
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        handler.postDelayed(permissionCheck, CHECK_INTERVAL_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!accessibilityEnabled()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private boolean accessibilityEnabled() {
        return Permissions.isAccessibilityMasterOn(this)
                && Permissions.isAccessibilityEnabled(this);
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(
                NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Служба чтения", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Чтение с экрана включено")
                .setContentText("Служба защищена от остановки системой")
                .setSmallIcon(R.drawable.ic_tile)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(permissionCheck);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
