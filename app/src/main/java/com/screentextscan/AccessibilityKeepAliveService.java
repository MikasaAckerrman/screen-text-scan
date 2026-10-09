package com.screentextscan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
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
    /**
     * САМОЛЕЧЕНИЕ ПОСЛЕ ЗАГРУЗКИ (правило 09.10: «после перезагрузки
     * разрешение слетает»). OriginOS сбрасывает строку доступности;
     * KeepAliveReceiver стартует нас на BOOT_COMPLETED — через 12 с
     * (настройки системы оседают) восстанавливаем всё через
     * RootSelfGrant: прямая запись (WRITE_SECURE_SETTINGS, выдан
     * навсегда) → su (KernelSU) → Shizuku exec (сервер юзера всегда
     * поднимается сам). Ретраи: не вышло — через 30 с, до 5 попыток.
     */
    private final Runnable bootHeal = new Runnable() {
        private int attempts;

        @Override
        public void run() {
            new Thread(() -> {
                boolean ok;
                try {
                    ok = RootSelfGrant.ensureAll(AccessibilityKeepAliveService.this);
                } catch (Throwable t) {
                    ok = false;
                }
                final boolean fine = ok;
                handler.post(() -> {
                    if (fine || ++attempts >= 5) {
                        android.util.Log.d("ScreenTextScan",
                                "boot-heal: " + (fine ? "ок" : "не вышло, попыток " + attempts));
                        return;
                    }
                    handler.postDelayed(this, 30_000);
                });
            }, "sts-boot-heal").start();
        }
    };
    private final Runnable permissionCheck = new Runnable() {
        @Override
        public void run() {
            // Пока boot-heal работает — живём: он вернёт доступность.
            if (!healScheduled && !accessibilityEnabled()) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return;
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    public static void start(Context context) {
        // Загрузка/обновление: самолечение важнее «уже включено?» —
        // стартуем ДАЖЕ если строка слетела (boot-heal вернёт её).
        try {
            context.startForegroundService(new Intent(
                    context, AccessibilityKeepAliveService.class)
                    .putExtra("heal", true));
        } catch (RuntimeException ignored) {
            // Force-stop и некоторые OEM background-start запреты обязательны
            // для приложений. Следующий видимый запуск попробует снова.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        /*
         * Не выходим при выключенной доступности: интент с heal
         * приходит в onStartCommand ПОСЛЕ onCreate — на загрузке это
         * единственный путь восстановления. Останов — в onStartCommand
         * и permissionCheck, когда heal не активен.
         */
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: specialUse-сервис БЕЗ типа бросает
            // MissingForegroundServiceTypeException → краш процесса →
            // служба доступности помечается Crashed → не переподключается.
            startForeground(NOTIFICATION_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        handler.postDelayed(permissionCheck, CHECK_INTERVAL_MS);
    }

    private boolean pendingHeal;
    private boolean healScheduled;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra("heal", false)) {
            pendingHeal = true;
        }
        if (!pendingHeal && !accessibilityEnabled()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (pendingHeal && !healScheduled) {
            healScheduled = true;
            /*
             * 12 секунд: системные настройки оседают после загрузки
             * не мгновенно; раньше — heal писал бы строку, которую
             * OriginOS тут же перезатирал бы своей инициализацией.
             */
            handler.postDelayed(bootHeal, 12_000);
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
