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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
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

    /**
     * Окно подтверждения одиночного тапа. Копирование по тапу срабатывает
     * не мгновенно, а после этого окна: если второй тап пришёл быстрее —
     * это двойной тап, то есть завершение чтения. Двойной тап и делает
     * закрытие плавающего окна недоступным одному случайному касанию.
     */
    private static final long TAP_CONFIRM_MS = 280;

    private WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TextAccumulator acc = new TextAccumulator();

    private ZoneSelectorView zoneView;
    private ScanBubbleView bubble;
    private WindowManager.LayoutParams bubbleParams;
    /**
     * Окно касаний шарика. Маленькое, ровно под кнопку — база как у
     * copy as file. Раньше касания ловило окно эффектов 200dp: весь этот
     * квадрат перехватывал скролл приложения (текст «не обновлялся при
     * прокрутке»), а перетаскивание упиралось в кромку экрана на
     * полквадрата раньше края.
     */
    private View bubbleTouch;
    private WindowManager.LayoutParams touchParams;

    private Rect zone;
    private boolean scanning;
    private long lastNewAt;
    private int screenW, screenH;
    /** Пакет приложения, которое читаем. При смене — останавливаем скан. */
    private String scanningPackage;
    /**
     * Кеш лаунчера: resolveActivity — это IPC в PackageManager, и звать его
     * каждые 600 мс в poll() незачем. Лаунчер за время сессии не меняется,
     * считаем один раз и держим; null = ещё не вычислен.
     */
    private String cachedLauncherPackage;

    /** Живой экземпляр сервиса; экран результата берёт из него снапшот. */
    private static OverlayService instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        readScreenSize();
        startForeground(NOTIF_ID, buildNotification("Выберите зону чтения"));
    }

    /**
     * Снапшот живого накопителя для экрана результата, открываемого тапом
     * по уведомлению во время чтения: экран получает замороженную копию,
     * сервис продолжает копить дальше независимо от него.
     */
    static TextAccumulator liveSnapshot() {
        OverlayService s = instance;
        return (s != null && s.scanning && s.acc.size() > 0) ? s.snapshotAcc() : null;
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
        // Проверяем СИСТЕМНУЮ НАСТРОЙКУ, а не живой экземпляр службы:
        // после полного выхода процесс убит, и служба привязывается не
        // мгновенно. poll ниже подхватит её, как только система подключит.
        if (!Permissions.isAccessibilityEnabled(this)
                || !Permissions.isAccessibilityMasterOn(this)) {
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
            // Окно касаний следует за окном эффектов: круг один и тот же.
            if (bubbleTouch != null && touchParams != null) {
                int off = (bubbleParams.width - touchParams.width) / 2;
                touchParams.x = bubbleParams.x + off;
                touchParams.y = bubbleParams.y + off;
                try {
                    wm.updateViewLayout(bubbleTouch, touchParams);
                } catch (IllegalArgumentException ignored) {
                }
            }
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
        lastNewAt = System.currentTimeMillis();
        scanningPackage = null;
        showBubble();
        updateNotification("Читаю. Листайте текст. Тап=копировать, двойной=результат.");
        ui.postDelayed(poll, POLL_MS);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!scanning) return;
            ScanAccessibilityService svc = ScanAccessibilityService.get();
            if (svc != null) {
                String currentPkg = svc.getActiveWindowPackage();

                if (currentPkg == null) {
                    // Служба ещё не подключилась — ждём, не останавливаем.
                } else if ("com.screentextscan".equals(currentPkg)) {
                    // Наш overlay — не считаем сменой окна.
                } else if (isLauncherPackage(currentPkg)) {
                    // Пользователь вышел на главный экран — убрать шарик.
                    stopEverything();
                    return;
                } else {
                    if (scanningPackage == null) {
                        scanningPackage = currentPkg;
                    } else if (!scanningPackage.equals(currentPkg)) {
                        // Пакет сменился — копируем накопленное и продолжаем.
                        if (acc.size() > 0) {
                            copyToClipboard();
                            acc.clear();
                        }
                        scanningPackage = currentPkg;
                    }
                }

                List<ScanAccessibilityService.Line> lines =
                        svc.readScreen(zone, screenW, screenH, true);
                /*
                 * Порядок чтения. Служба доступности обходит дерево по
                 * вложенности элементов, а не сверху вниз — без сортировки
                 * строки копировались бы вперемешку. ReadingOrder.sort
                 * раскладывает их по строкам-полосам сверху вниз, слева
                 * направо, а виртуальные (WebView, координаты от начала
                 * документа) выносит отдельной группой в конец, чтобы они не
                 * перемешались с экранными.
                 */
                List<ReadingOrder.Item> items = new ArrayList<>(lines.size());
                for (ScanAccessibilityService.Line l : lines) {
                    items.add(new ReadingOrder.Item(l.text, l.bounds, l.virtual));
                }
                List<String> texts = ReadingOrder.sort(items);
                int added = acc.addAll(texts);
                if (added > 0) {
                    lastNewAt = System.currentTimeMillis();
                    // Визуальный эффект всасывания — частицы стягиваются к кругу.
                    if (bubble != null) bubble.pulseNewText();
                }
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
                stopEverything();
                return;
            }
            ui.postDelayed(this, POLL_MS);
        }
    };

    private void showBubble() {
        /*
         * ДВА ОКНА вместо одного большого.
         *
         * Раньше круг 62dp жил в окне 200dp, которое нужно для искр и
         * burst, — и весь квадрат был хитбоксом: свайп, начатый рядом с
         * шариком, до приложения не доходил (экран не прокручивался —
         * опрос не видел нового текста), а шарик нельзя было подтащить
         * к краю: клэмп был по размеру окна эффектов.
         *
         * База как у copy as file: касания ловит ТОЛЬКО окно-кнопка;
         * эффекты живут отдельным окном с FLAG_NOT_TOUCHABLE — картинку
         * рисует, касаний не забирает. Оба окна двигаются синхронно.
         */
        bubble = new ScanBubbleView(this);
        bubble.setCount(0);

        // Окно эффектов: 200dp, круг 62dp в центре, искры и burst целиком.
        int viewSize = dp(200);
        bubbleParams = new WindowManager.LayoutParams(
                viewSize, viewSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        // Сдвигаем так, чтобы круг 62dp был у правого края как раньше
        int circleSize = dp(62);
        bubbleParams.x = screenW - circleSize - dp(14) - (viewSize - circleSize) / 2;
        bubbleParams.y = screenH * 2 / 3 - (viewSize - circleSize) / 2;
        bubble.setAlpha(0.82f);
        wm.addView(bubble, bubbleParams);

        // Окно касаний: сама кнопка с небольшим запасом на палец.
        // FLAG_NOT_FOCUSABLE (как у copy as file) включает не-модальность:
        // мимо этого окна касания сразу уходят в приложение под ним.
        int touchSize = dp(70);
        bubbleTouch = new View(this);
        touchParams = new WindowManager.LayoutParams(
                touchSize, touchSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        touchParams.gravity = Gravity.TOP | Gravity.START;
        int off = (viewSize - touchSize) / 2;
        touchParams.x = bubbleParams.x + off;
        touchParams.y = bubbleParams.y + off;
        wm.addView(bubbleTouch, touchParams);
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
        // Визуал эффекта, захваченный на момент установки слушателя:
        // сравнение с полем bubble — проверка живости (жест может
        // до-летать к уже снятым окнам, поле к этому моменту null).
        final ScanBubbleView fx = bubble;
        bubbleTouch.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY;
            int origFxX, origFxY, origTx, origTy;
            boolean moved;
            boolean longPressHandled;
            Runnable longPressCallback;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                final boolean live = (fx == bubble && bubbleTouch == v);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = e.getRawX();
                        startY = e.getRawY();
                        origFxX = bubbleParams.x;
                        origFxY = bubbleParams.y;
                        origTx = touchParams.x;
                        origTy = touchParams.y;
                        moved = false;
                        longPressHandled = false;
                        if (live) {
                            fx.onPress();
                            fx.setAlpha(1f);
                            // Всегда запускаем таймер 2.5с → убрать шарик
                            fx.startLongPressAnim();
                        }
                        longPressCallback = () -> {
                            if (fx == bubble && bubbleTouch == v) {
                                longPressHandled = true;
                                fx.cancelLongPressAnim();
                                vibrate(true);
                                fx.pop(() -> removeSilently());
                            }
                        };
                        ui.postDelayed(longPressCallback, 2500);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - startX);
                        int dy = (int) (e.getRawY() - startY);
                        /*
                         * Движение ОТМЕНЯЕТ таймер зажатия. Раньше он не
                         * отменялся, и «нажал и повёл» убивало шарик через
                         * 2,5 с посреди перетаскивания.
                         */
                        if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                            moved = true;
                            if (longPressCallback != null) {
                                ui.removeCallbacks(longPressCallback);
                                longPressCallback = null;
                            }
                            if (live) fx.cancelLongPressAnim();
                        }
                        if (!moved || !live) return true;
                        // Окно касаний клэмпим по экрану (как copy as file);
                        // окно эффектов держим так, чтобы круг оставался
                        // в центре окна касаний — оно с NO_LIMITS, может
                        // выходить за край, так что шарик теперь доезжает
                        // до самой кромки.
                        int ts = v.getWidth();
                        touchParams.x = ZoneGeometry.clamp(origTx + dx, 0,
                                Math.max(0, screenW - ts));
                        touchParams.y = ZoneGeometry.clamp(origTy + dy, 0,
                                Math.max(0, screenH - ts));
                        int off = (bubbleParams.width - ts) / 2;
                        bubbleParams.x = touchParams.x - off;
                        bubbleParams.y = touchParams.y - off;
                        try {
                            wm.updateViewLayout(bubbleTouch, touchParams);
                            wm.updateViewLayout(bubble, bubbleParams);
                        } catch (IllegalArgumentException ignored) {
                            // Жест долетел до уже снятых окон — не двигаем.
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (longPressCallback != null) {
                            ui.removeCallbacks(longPressCallback);
                            longPressCallback = null;
                        }
                        if (live) {
                            fx.onRelease();
                            fx.setAlpha(0.82f);
                            fx.cancelLongPressAnim();
                        }
                        /*
                         * CANCEL приходит при снятии окна изнутри — тапом
                         * он не считается. Тап — это UP без сдвига и без
                         * сработавшего зажатия.
                         */
                        if (e.getActionMasked() == MotionEvent.ACTION_UP
                                && !moved && !longPressHandled && live) {
                            handleTap(fx);
                        }
                        return true;
                }
                return false;
            }
        });
    }

    /**
     * Отложенный одиночный тап (поле сервиса: handleTap() работает с ним
     * из метода, а не из слушателя). Пока колбэк висит в очереди, быстрый
     * второй тап отменяет его и превращает пару в двойной тап.
     */
    private Runnable pendingSingleTap;

    /**
     * Тап по шарику с учётом двойного.
     *
     * ОДИНОЧНЫЙ тап (после окна подтверждения) — скопировать накопленное
     * и продолжить чтение. ДВОЙНОЙ — завершить чтение и открыть экран
     * результата. Так закрытие плавающего окна требует двух тапов и не
     * достаётся одним случайным касанием.
     */
    private void handleTap(final ScanBubbleView bv) {
        if (pendingSingleTap != null) {
            // Второй тап в окне подтверждения → двойной тап.
            ui.removeCallbacks(pendingSingleTap);
            pendingSingleTap = null;
            finalizeScan();
            return;
        }
        pendingSingleTap = () -> {
            pendingSingleTap = null;
            // Шарик мог быть снят за окно ожидания — тогда копировать некому.
            if (bv == bubble) onBubbleTap();
        };
        ui.postDelayed(pendingSingleTap, TAP_CONFIRM_MS);
    }

    /**
     * Двойной тап: закончить чтение и показать результат.
     *
     * Накопленное не выбрасывается: строки уходят на экран результата, где
     * их можно поправить, вычеркнуть, перевести и скопировать. Это тот
     * экран, который MainActivity обещает текстом, но который раньше
     * не открывался ниоткуда.
     */
    private void finalizeScan() {
        if (!scanning) return;
        int lines = acc.keptSize();
        if (lines > 0) {
            vibrate(false);
            ResultActivity.pending = snapshotAcc();
            // Экран результата теперь держит текст; автокопия в
            // stopEverything() для этого пути не нужна и не сработает.
            acc.clear();
        } else {
            Toast.makeText(this, "Прочитанного текста не было",
                    Toast.LENGTH_SHORT).show();
        }
        if (bubble != null) {
            bubble.pop(() -> {
                stopEverything();
                openResult();
            });
        } else {
            stopEverything();
            openResult();
        }
    }

    /** Экран результата поверх текущего приложения. */
    private void openResult() {
        if (ResultActivity.pending == null) return;
        Intent i = new Intent(this, ResultActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    /**
     * Замороженная копия накопителя для экрана результата. Копия, а не
     * ссылка: открытие результата из уведомления не должно связывать экран
     * с живым накопителем, в который poll() продолжает добавлять строки.
     */
    private TextAccumulator snapshotAcc() {
        TextAccumulator copy = new TextAccumulator();
        copy.addAll(acc.lines());
        return copy;
    }

    /**
     * Долгое нажатие: убрать плавающее окно БЕЗ экрана результата.
     *
     * И здесь накопленное не теряется — уходит в буфер обмена с тостом:
     * текст не должен пропасть ни на одном пути выхода.
     */
    private void removeSilently() {
        if (scanning && acc.size() > 0) {
            copyToClipboard();
            acc.clear();
        }
        stopEverything();
    }

    /** Тап по шарику: копировать накопленный текст + продолжить чтение. */
    private void onBubbleTap() {
        if (scanning && acc.size() > 0) {
            vibrate(false);
            bubble.flashCopy();
            copyToClipboard();
            // Очищаем аккумулятор — новый текст будет копироваться отдельно.
            acc.clear();
            lastNewAt = System.currentTimeMillis();
        } else if (scanning) {
            Toast.makeText(this, "Текста пока нет — листайте дальше",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /* ==================================================================
       Копирование
       ================================================================== */

    /**
     * Копировать накопленный текст в буфер обмена.
     * Не останавливает скан — пользователь может продолжить чтение.
     */
    private void copyToClipboard() {
        saveToFile();
        String text = acc.text();
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                "ScreenTextScan", text));
        Toast.makeText(this,
                "Скопировано: " + acc.keptSize() + " " + lineWord(acc.keptSize()),
                Toast.LENGTH_SHORT).show();
    }

    private static String lineWord(int n) {
        int t = n % 100;
        if (t >= 11 && t <= 14) return "строк";
        switch (n % 10) {
            case 1: return "строка";
            case 2: case 3: case 4: return "строки";
            default: return "строк";
        }
    }

    /**
     * Качественная тактильная отдача.
     * @param strong true для долгого зажатия (сильнее), false для копирования (легче).
     */
    private void vibrate(boolean strong) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.vibrate(VibrationEffect.createPredefined(
                    strong ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK));
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(
                    strong ? 30 : 15, strong ? 180 : 120));
        }
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
        /*
         * Неявный конец сессии (таймаут тишины, выход на лаунчер, кнопка
         * «Прекратить») раньше просто выбрасывал накопленное — получалось
         * «прочитал, а скопировалось не всё». Теперь любой путь выхода
         * кладёт остаток в буфер обмена.
         */
        if (scanning && acc.size() > 0) {
            copyToClipboard();
            acc.clear();
        }
        scanning = false;
        scanningPackage = null;
        ui.removeCallbacks(poll);
        removeZoneView();
        removeBubbleViews();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** Проверить, является ли пакет домашним экраном (лаунчером). */
    private boolean isLauncherPackage(String pkg) {
        if (pkg == null) return false;
        // Кеш: resolveActivity — IPC в PackageManager, poll() звал его
        // каждые 600 мс. Лаунчер не меняется за сессию.
        if (cachedLauncherPackage == null) {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri =
                    getPackageManager().resolveActivity(home, 0);
            cachedLauncherPackage = (ri != null && ri.activityInfo != null)
                    ? ri.activityInfo.packageName : "";
        }
        return pkg.equals(cachedLauncherPackage);
    }

    @Override
    public void onDestroy() {
        instance = null;
        scanning = false;
        ui.removeCallbacks(poll);
        removeZoneView();
        removeBubbleViews();
        super.onDestroy();
    }

    /**
     * Снять оба окна шарика: окно эффектов и окно касаний. try/catch —
     * окно могли снять раньше (или жест ещё держит его): падать на этом
     * нельзя.
     */
    private void removeBubbleViews() {
        if (bubble != null) {
            try {
                wm.removeView(bubble);
            } catch (IllegalArgumentException ignored) {
            }
            bubble = null;
        }
        if (bubbleTouch != null) {
            try {
                wm.removeView(bubbleTouch);
            } catch (IllegalArgumentException ignored) {
            }
            bubbleTouch = null;
        }
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

        /*
         * Тап по самому уведомлению раньше ничего не делал — у него не было
         * contentIntent. Теперь: есть накопленный текст → экран результата
         * (снапшот, сервис продолжает читать), нет → настройки приложения.
         */
        Intent content = (acc.size() > 0)
                ? new Intent(this, ResultActivity.class)
                : new Intent(this, MainActivity.class);
        content.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent contentPi = PendingIntent.getActivity(this, 2, content,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Чтение с экрана")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_tile)
                .setOngoing(true)
                .setContentIntent(contentPi)
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
