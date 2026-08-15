package com.screentextscan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Плавающая кнопка и накопление текста.
 *
 * ЖИЗНЕННЫЙ ЦИКЛ
 *   1. ВЫБОР ЗОНЫ — сначала пользователь обводит область. Без этого в
 *      результат попадают шапка, панель навигации и кнопки.
 *   2. ЧТЕНИЕ — маленькая чёрно-белая кнопка со счётчиком и кольцом
 *      готовности. Перетаскивается, полупрозрачна, читать не мешает.
 *   3. ОСТАНОВКА — та же кнопка расширяется в «Копировать».
 *   4. ПРАВКА — открывается экран результата, где строки можно исправить,
 *      исключить и перевести.
 *
 * ПОЧЕМУ ЭТО РАБОТАЕТ ТОЛЬКО В APK: окно поверх чужих приложений создаётся
 * через WindowManager с типом TYPE_APPLICATION_OVERLAY и разрешением
 * SYSTEM_ALERT_WINDOW. Вне приложения такого окна создать нечем.
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
     * Через сколько без нового текста считаем, что видимый участок прочитан
     * полностью. Это НЕ остановка — это индикатор «всё взято» на кнопке.
     * 2.4 с ≈ четыре опроса: одного мало (пауза при листании бывает и
     * секунду), десяти много.
     */
    private static final long COMPLETE_AFTER_MS = 2400;

    /**
     * Полная остановка по простою — страховка от «пользователь ушёл».
     * Именно страховка: обычный способ закончить — нажать кнопку.
     */
    private static final long IDLE_LIMIT_MS = 180_000;

    private WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TextAccumulator acc = new TextAccumulator();

    private ZoneSelectorView zoneView;
    private ScanBubbleView bubble;
    private WindowManager.LayoutParams bubbleParams;

    private Rect zone;
    private boolean scanning;
    private boolean finished;
    private long lastNewAt;
    private int screenW, screenH;
    /** Ориентация, при которой выбиралась зона — нужна для пересчёта. */
    private int lastRotationW, lastRotationH;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        readScreenSize();
        startForeground(NOTIF_ID, buildNotification("Выберите зону чтения"));
    }

    /**
     * Размер экрана.
     *
     * ПОЧЕМУ НЕ ИЗ Resources. При многооконном режиме и на складных
     * устройствах Resources отдаёт размер НАШЕГО окна, а нужен весь экран:
     * окно наложения занимает его целиком, и координаты узлов тоже экранные.
     *
     * getDefaultDisplay/getRealMetrics устарели с API 30, поэтому на новых
     * версиях берём границы из WindowMetrics, а старый путь оставлен только
     * для 26–29, где WindowMetrics ещё нет.
     */
    private void readScreenSize() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.graphics.Rect b = wm.getCurrentWindowMetrics().getBounds();
            screenW = b.width();
            screenH = b.height();
        } else {
            readScreenSizeLegacy();
        }
    }

    @SuppressWarnings("deprecation")
    private void readScreenSizeLegacy() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
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

    /**
     * Поворот экрана.
     *
     * ЧТО ЗДЕСЬ ЛОМАЛОСЬ БЕЗ ЭТОГО. Зона задана в пикселях экрана. После
     * поворота 1260×2800 → 2800×1260 те же числа означают совсем другое
     * место: зона «низ страницы» превращается в узкую полосу за правым
     * краем, и текст перестаёт находиться вообще. Плюс кнопка остаётся по
     * старым координатам и уезжает за пределы экрана — её не достать.
     *
     * Пересчитываем пропорционально: точным это быть не может (текст
     * переверстался), но попасть примерно в ту же часть экрана лучше, чем
     * не попасть никуда.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int oldW = screenW, oldH = screenH;
        readScreenSize();
        if (oldW == screenW && oldH == screenH) return;

        if (zone != null) {
            zone = ZoneGeometry.rotate(zone, oldW, oldH, screenW, screenH);
        }
        if (zoneView != null) {
            // Селектор растянут на весь экран, ему хватит перерисовки,
            // но начальную рамку надо отдать уже пересчитанную.
            zoneView.setInitialZone(zone);
            zoneView.invalidate();
        }
        if (bubble != null && bubbleParams != null) {
            bubbleParams.x = ZoneGeometry.clamp(
                    Math.round(bubbleParams.x * (float) screenW / oldW),
                    0, Math.max(0, screenW - bubbleParams.width));
            bubbleParams.y = ZoneGeometry.clamp(
                    Math.round(bubbleParams.y * (float) screenH / oldH),
                    0, Math.max(0, screenH - bubbleParams.height));
            wm.updateViewLayout(bubble, bubbleParams);
        }
    }

    /* ==================================================================
       Шаг 1. Выбор зоны
       ================================================================== */

    private void showZoneSelector() {
        zoneView = new ZoneSelectorView(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                /*
                 * Это окно ДОЛЖНО получать касания, поэтому NOT_TOUCHABLE не
                 * ставим. Фокус ему не нужен: NOT_FOCUSABLE оставляет
                 * клавиатуру и активное окно нижнего приложения в покое.
                 * LAYOUT_NO_LIMITS — чтобы затемнение доходило до краёв под
                 * строкой состояния и панелью навигации.
                 */
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(zoneView, lp);

        // Подсказываем прошлым выбором: чаще всего читают то же самое место.
        Rect suggested = ZonePrefs.load(this, screenW, screenH);
        if (suggested != null) zoneView.setInitialZone(suggested);

        zoneView.setListener(new ZoneSelectorView.Listener() {
            @Override
            public void onZoneChosen(Rect r) {
                zone = r;
                ZonePrefs.save(OverlayService.this, r, screenW, screenH);
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
                    zone = null;
                    removeZoneView();
                    startScanning();
                } else {
                    /*
                     * Подобранную зону НЕ применяем сразу: показываем её в
                     * рамке, чтобы человек увидел, что предложено, и при
                     * желании подправил. Автоподбор ошибается — например,
                     * берёт список вместе с панелью вкладок.
                     */
                    zoneView.setInitialZone(auto);
                    Toast.makeText(OverlayService.this,
                            "Проверьте рамку и нажмите «Читать область»",
                            Toast.LENGTH_SHORT).show();
                }
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
       Шаг 2. Чтение
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
                    texts.add(l.text);
                }
                int added = acc.addAll(texts);
                if (added > 0) lastNewAt = System.currentTimeMillis();
            }

            long idle = System.currentTimeMillis() - lastNewAt;
            /*
             * Кольцо на кнопке: пустое сразу после нового текста, полное —
             * когда с видимого участка больше нечего брать. Это и есть
             * ответ на «есть ли индикатор, что всё скопировалось»: знать про
             * ещё не показанный текст индикатор не может, а про видимый —
             * может и показывает честно.
             */
            float readiness = Math.min(1f, (float) idle / COMPLETE_AFTER_MS);
            if (bubble != null) {
                bubble.setCount(acc.size());
                bubble.setReadiness(readiness, idle >= COMPLETE_AFTER_MS && acc.size() > 0);
            }

            if (idle > IDLE_LIMIT_MS) {
                finishScanning();
                return;
            }
            ui.postDelayed(this, POLL_MS);
        }
    };

    private void showBubble() {
        bubble = new ScanBubbleView(this);
        bubble.setState(ScanBubbleView.State.READING);
        bubble.setCount(0);

        int size = dp(62);
        bubbleParams = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                /*
                 * FLAG_NOT_FOCUSABLE обязателен: с фокусом наше окно стало бы
                 * активным, и служба доступности начала бы читать ЕГО вместо
                 * приложения под ним. Плюс пропала бы возможность листать.
                 */
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = screenW - size - dp(14);
        bubbleParams.y = screenH * 2 / 3;
        /*
         * Полупрозрачность — требование «кнопка не должна мешать читать,
         * где бы она ни находилась». При касании возвращаем полную
         * видимость, чтобы палец видел, что тащит.
         */
        bubble.setAlpha(0.78f);
        wm.addView(bubble, bubbleParams);
        attachDragAndTap();
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
                switch (e.getActionMasked()) {
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
                        bubbleParams.x = ZoneGeometry.clamp(origX + dx, 0,
                                Math.max(0, screenW - bubble.getWidth()));
                        bubbleParams.y = ZoneGeometry.clamp(origY + dy, 0,
                                Math.max(0, screenH - bubble.getHeight()));
                        wm.updateViewLayout(bubble, bubbleParams);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        bubble.setAlpha(finished ? 0.96f : 0.78f);
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
            openResult();
        }
    }

    /* ==================================================================
       Шаг 3. Остановка и результат
       ================================================================== */

    private void finishScanning() {
        scanning = false;
        finished = true;
        ui.removeCallbacks(poll);

        if (acc.size() == 0) {
            Toast.makeText(this, "Текста в выбранной области не нашлось", Toast.LENGTH_LONG).show();
            stopEverything();
            return;
        }

        // Кнопка РАСТЁТ и меняет назначение — второй кнопки нет по условию.
        int w = dp(190), h = dp(52);
        bubbleParams.width = w;
        bubbleParams.height = h;
        bubbleParams.x = ZoneGeometry.clamp(bubbleParams.x, 0, Math.max(0, screenW - w));
        bubbleParams.y = ZoneGeometry.clamp(bubbleParams.y, 0, Math.max(0, screenH - h));
        bubble.setState(ScanBubbleView.State.DONE);
        bubble.setCount(acc.keptSize());
        bubble.setAlpha(0.96f);
        wm.updateViewLayout(bubble, bubbleParams);

        saveToFile();
        updateNotification("Прочитано строк: " + acc.size() + ". Нажмите кнопку.");
    }

    /**
     * Открыть экран правки.
     *
     * Накопитель передаём через статическое поле, а не через Intent: текст с
     * экрана легко перевалит за лимит Binder-транзакции (около 1 МБ), и
     * приложение упало бы на большом документе.
     */
    private void openResult() {
        ResultActivity.pending = acc;
        startActivity(new Intent(this, ResultActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        // Кнопку убираем, сервис останавливаем: работа передана экрану.
        stopEverything();
    }

    /**
     * Дублируем результат в файл. Буфер обмена недолговечен: одно
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
        super.onDestroy();
    }

    /* ==================================================================
       Служебное
       ================================================================== */

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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
