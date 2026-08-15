package com.screentextscan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Плавающая кнопка и накопление текста.
 *
 * ЖИЗНЕННЫЙ ЦИКЛ, ЗАДАННЫЙ ПОСТАНОВКОЙ ЗАДАЧИ
 *
 *   1. ВЫБОР ЗОНЫ. Сначала пользователь обводит область, откуда читать. Без
 *      этого в результат попадают шапка, панель навигации и кнопки — на
 *      живых экранах это половина строк.
 *   2. ЧТЕНИЕ. Появляется маленькая круглая кнопка «стоп». Она перетаскивается
 *      куда угодно и намеренно полупрозрачна, потому что обязана не мешать
 *      читать текст под собой.
 *   3. ГОТОВО. Та же кнопка РАСТЁТ и превращается в «Копировать» — отдельной
 *      второй кнопки нет, как и просили.
 *
 * ПОЧЕМУ ЭТО РАБОТАЕТ ТОЛЬКО В APK. Окно поверх чужих приложений создаётся
 * через WindowManager с типом TYPE_APPLICATION_OVERLAY и разрешением
 * SYSTEM_ALERT_WINDOW. Ни один инструмент вне приложения такого окна создать
 * не может — поэтому из скрипта задача и не решалась.
 */
public class OverlayService extends Service {

    public static final String ACTION_START = "com.screentextscan.START";
    public static final String ACTION_STOP_ALL = "com.screentextscan.STOP_ALL";

    private static final String CHANNEL = "sts_scan";
    private static final int NOTIF_ID = 41;

    /**
     * Пауза между опросами. 600 мс — компромисс: обход дерева стоит около
     * 50 мс, так что нагрузка мала, а листающий человек за это время не
     * успевает пролистать больше экрана текста.
     */
    private static final long POLL_MS = 600;

    /**
     * Сколько секунд без нового текста считаем окончанием чтения. Нужно как
     * страховка: если пользователь ушёл, сервис не должен висеть вечно.
     * Само чтение всё равно останавливается кнопкой — это ГЛАВНЫЙ способ.
     */
    private static final long IDLE_LIMIT_MS = 90_000;

    private WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TextAccumulator acc = new TextAccumulator();

    private ZoneSelectorView zoneView;
    private FrameLayout bubble;
    private TextView bubbleLabel;
    private WindowManager.LayoutParams bubbleParams;

    private Rect zone;
    private boolean scanning;
    private boolean finished;
    private long lastNewAt;
    private int screenW, screenH;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        startForeground(NOTIF_ID, buildNotification("Выберите зону чтения"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP_ALL.equals(action)) {
            stopEverything();
            return START_NOT_STICKY;
        }
        if (ScanAccessibilityService.get() == null) {
            Toast.makeText(this, "Сначала включите службу «Чтение с экрана» в настройках доступности",
                    Toast.LENGTH_LONG).show();
            stopEverything();
            return START_NOT_STICKY;
        }
        if (zoneView == null && bubble == null) showZoneSelector();
        return START_NOT_STICKY;
    }

    /* ==================================================================
       Шаг 1. Выбор зоны
       ================================================================== */

    private void showZoneSelector() {
        zoneView = new ZoneSelectorView(this);
        /*
         * Это окно ДОЛЖНО получать касания, поэтому FLAG_NOT_TOUCHABLE не
         * ставим. Но и фокус ему не нужен: FLAG_NOT_FOCUSABLE оставляет
         * клавиатуру и активное окно нижнего приложения в покое.
         */
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(zoneView, lp);

        zoneView.setListener(new ZoneSelectorView.Listener() {
            @Override
            public void onZoneChosen(Rect r) {
                zone = r;
                removeZoneView();
                startScanning();
            }

            @Override
            public void onWholeScreen() {
                zone = null;
                removeZoneView();
                startScanning();
            }

            @Override
            public void onAutoZone() {
                ScanAccessibilityService svc = ScanAccessibilityService.get();
                Rect auto = svc == null ? null : svc.autoZone(screenW, screenH);
                if (auto == null) {
                    Toast.makeText(OverlayService.this,
                            "Прокручиваемого содержимого не нашлось — читаю весь экран",
                            Toast.LENGTH_SHORT).show();
                }
                zone = auto;
                removeZoneView();
                startScanning();
            }

            @Override
            public void onCancel() {
                stopEverything();
            }
        });
    }

    private void removeZoneView() {
        if (zoneView != null) {
            try {
                wm.removeView(zoneView);
            } catch (IllegalArgumentException ignored) {
            }
            zoneView = null;
        }
    }

    /* ==================================================================
       Шаг 2. Чтение + плавающая кнопка
       ================================================================== */

    private void startScanning() {
        acc.clear();
        scanning = true;
        finished = false;
        lastNewAt = System.currentTimeMillis();
        showBubble();
        updateNotification("Читаю. Листайте текст.");
        ui.postDelayed(poll, POLL_MS);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!scanning) return;
            ScanAccessibilityService svc = ScanAccessibilityService.get();
            if (svc != null) {
                List<ScanAccessibilityService.Line> lines =
                        svc.readScreen(zone, screenW, screenH, false);
                List<String> texts = new ArrayList<>(lines.size());
                for (ScanAccessibilityService.Line l : lines) {
                    /*
                     * Свой же интерфейс в результат попадать не должен.
                     * Кнопка нарисована в НАШЕМ окне, и если бы дерево до неё
                     * дотянулось, в тексте появились бы «Стоп» и «Копировать».
                     */
                    if ("Стоп".contentEquals(l.text) || "Копировать".contentEquals(l.text)) continue;
                    texts.add(l.text);
                }
                int added = acc.addAll(texts);
                if (added > 0) {
                    lastNewAt = System.currentTimeMillis();
                    updateBubbleCount();
                }
            }
            if (System.currentTimeMillis() - lastNewAt > IDLE_LIMIT_MS) {
                finishScanning();
                return;
            }
            ui.postDelayed(this, POLL_MS);
        }
    };

    private void showBubble() {
        bubble = new FrameLayout(this);
        bubbleLabel = new TextView(this);
        bubbleLabel.setTextColor(Color.WHITE);
        bubbleLabel.setTextSize(13f);
        bubbleLabel.setGravity(Gravity.CENTER);
        bubbleLabel.setText("Стоп");
        bubble.addView(bubbleLabel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        applyBubbleStyle(false);

        int size = dp(58);
        bubbleParams = new WindowManager.LayoutParams(
                size, size,
                overlayType(),
                /*
                 * FLAG_NOT_FOCUSABLE обязателен: с фокусом наше окно стало бы
                 * активным, и служба доступности начала бы читать ЕГО вместо
                 * приложения под ним. Плюс у пользователя пропала бы
                 * возможность листать текст.
                 */
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = screenW - size - dp(12);
        bubbleParams.y = screenH / 2;
        /*
         * Полупрозрачность — прямое требование «кнопка не должна мешать
         * читать текст, где бы она ни находилась». При перетаскивании
         * возвращаем полную видимость, чтобы палец видел, что тащит.
         */
        bubble.setAlpha(0.72f);
        wm.addView(bubble, bubbleParams);
        attachDragAndTap();
    }

    /** Круглая заливка. Красная при чтении, синяя когда готово копировать. */
    private void applyBubbleStyle(boolean done) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(done ? 22 : 29));
        bg.setColor(done ? Color.parseColor("#0A84FF") : Color.parseColor("#D7263D"));
        bg.setStroke(dp(1), Color.parseColor("#33FFFFFF"));
        bubble.setBackground(bg);
    }

    /**
     * Перетаскивание и нажатие на одном обработчике.
     *
     * Различаем по СМЕЩЕНИЮ, а не по времени: если считать нажатием любое
     * быстрое касание, то короткий рывок при перетаскивании остановил бы
     * чтение. Порог — 12 dp, примерно толщина пальца.
     */
    private void attachDragAndTap() {
        final int slop = dp(12);
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY;
            int origX, origY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = e.getRawX();
                        startY = e.getRawY();
                        origX = bubbleParams.x;
                        origY = bubbleParams.y;
                        moved = false;
                        bubble.setAlpha(1f);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - startX);
                        int dy = (int) (e.getRawY() - startY);
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) moved = true;
                        bubbleParams.x = clamp(origX + dx, 0, screenW - bubble.getWidth());
                        bubbleParams.y = clamp(origY + dy, 0, screenH - bubble.getHeight());
                        wm.updateViewLayout(bubble, bubbleParams);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        bubble.setAlpha(finished ? 0.95f : 0.72f);
                        if (!moved) onBubbleTap();
                        return true;
                }
                return false;
            }
        });
    }

    private void onBubbleTap() {
        if (scanning) {
            finishScanning();
        } else if (finished) {
            copyResult();
        }
    }

    /* ==================================================================
       Шаг 3. Остановка и копирование
       ================================================================== */

    private void finishScanning() {
        scanning = false;
        finished = true;
        ui.removeCallbacks(poll);

        // Кнопка РАСТЁТ и меняет назначение — второй кнопки нет по условию.
        int w = dp(150), h = dp(44);
        bubbleParams.width = w;
        bubbleParams.height = h;
        bubbleParams.x = clamp(bubbleParams.x, 0, Math.max(0, screenW - w));
        bubbleParams.y = clamp(bubbleParams.y - dp(6), 0, Math.max(0, screenH - h));
        applyBubbleStyle(true);
        bubbleLabel.setText("Копировать · " + acc.size());
        bubble.setAlpha(0.95f);
        wm.updateViewLayout(bubble, bubbleParams);

        saveToFile();
        updateNotification("Прочитано строк: " + acc.size());
    }

    private void copyResult() {
        String text = acc.text();
        if (text.isEmpty()) {
            Toast.makeText(this, "Текста не набралось", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Текст с экрана", text));
        Toast.makeText(this, "Скопировано строк: " + acc.size(), Toast.LENGTH_SHORT).show();
        stopEverything();
    }

    /**
     * Дублируем результат в файл. Буфер обмена вещь недолговечная: одно
     * копирование в другом приложении и текст потерян.
     */
    private void saveToFile() {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) return;
            java.io.File f = new java.io.File(dir, "screen-text.txt");
            try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(f), "UTF-8")) {
                w.write(acc.text());
            }
        } catch (java.io.IOException ignored) {
        }
    }

    private void updateBubbleCount() {
        if (!scanning || bubbleLabel == null) return;
        bubbleLabel.setText(String.valueOf(acc.size()));
    }

    private void stopEverything() {
        scanning = false;
        ui.removeCallbacks(poll);
        removeZoneView();
        if (bubble != null) {
            try {
                wm.removeView(bubble);
            } catch (IllegalArgumentException ignored) {
            }
            bubble = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopEverything();
        super.onDestroy();
    }

    /* ==================================================================
       Служебное
       ================================================================== */

    private static int overlayType() {
        // minSdk 26 — TYPE_APPLICATION_OVERLAY есть всегда, ветка для более
        // старых версий была бы недостижимым кодом.
        return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        // minSdk 26: канал нужен всегда, проверка версии была бы лишней.
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "Чтение с экрана", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        Intent stop = new Intent(this, OverlayService.class).setAction(ACTION_STOP_ALL);
        PendingIntent pi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Чтение с экрана")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_tile)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        (android.graphics.drawable.Icon) null, "Прекратить", pi).build())
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
