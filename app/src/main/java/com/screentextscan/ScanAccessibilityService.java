package com.screentextscan;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Источник текста.
 *
 * ЧТО ЭТО НЕ ЕСТЬ: это не распознавание картинки. Служба доступности отдаёт
 * строки из дерева элементов приложения — те самые, что нарисованы на экране.
 * Отсюда три следствия, определяющие всю конструкцию:
 *
 *   • точность на настоящем тексте абсолютная: ошибок распознавания не бывает
 *     в принципе, потому что распознавания нет;
 *   • один обход дерева стоит порядка 50 мс, значит экран можно опрашивать
 *     несколько раз в секунду и накапливать текст, пока человек листает;
 *   • элементы ЗА границей экрана в дереве обычно отсутствуют — система
 *     отдаёт то, что отрисовано. Поэтому длинный текст читается только
 *     прокруткой, и накопление обязательно.
 *
 * Служба не отслеживает события: подписка на них давала бы всплеск вызовов
 * при каждой анимации. Вместо этого OverlayService сам опрашивает её по
 * таймеру — расход предсказуем и не зависит от того, насколько «болтливо»
 * читаемое приложение.
 */
public class ScanAccessibilityService extends AccessibilityService {

    /** Живой экземпляр. Сервис системный, конструировать его сами не можем. */
    private static volatile ScanAccessibilityService instance;

    public static ScanAccessibilityService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        android.util.Log.d("ScreenTextScan", "a11y onServiceConnected");
        AccessibilityKeepAliveService.start(this);
        // Перепривязка сбрасывает подписку к значениям из XML. Если скан
        // шёл в этот момент — вернуть полную подписку, иначе кэш снова
        // замёрзнет и poll перечитывает один и тот же снимок.
        if (wantFullEvents) applySubscription(this, true);
    }

    /**
     * Полная подписка на события — на время чтения экрана.
     *
     * ЗАЧЕМ. Дерево доступности отдаётся из клиентского кэша, и кэш
     * инвалидируют СОБЫТИЯ, а не наши опросы. Диагностика 25.09 это
     * подтвердила: poll при прокрутке чата возвращал «lines=5» двенадцать
     * раз подряд — статичный снимок момента открытия окна. Подписка
     * «только смена окна» (XML) кэш при прокрутке не трогает: летят
     * content-changed и scrolled, которых мы не получали.
     *
     * ПОТОМ ОБЯЗАТЕЛЬНО МИНИМАЛЬНАЯ: события content-changed идут из всех
     * приложений круглосуточно, постоянная подписка зря будила бы процесс
     * и съедала батарею. setServiceInfo действует сразу; wantFullEvents
     * хранит желание, чтобы onServiceConnected мог его вернуть.
     */
    private static volatile boolean wantFullEvents = false;

    /** Включить/выключить полную подписку (вызывает OverlayService). */
    public static void setScanSubscription(boolean full) {
        wantFullEvents = full;
        ScanAccessibilityService s = instance;
        if (s != null) applySubscription(s, full);
    }

    private static void applySubscription(ScanAccessibilityService s, boolean full) {
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = s.getServiceInfo();
            if (info == null) return;
            info.eventTypes = full
                    ? (AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                        | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                        | AccessibilityEvent.TYPE_VIEW_SCROLLED)
                    : AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            s.setServiceInfo(info);
        } catch (RuntimeException ignored) {
            // Служба перепривязывается — подписку вернёт onServiceConnected.
        }
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        android.util.Log.d("ScreenTextScan", "a11y onUnbind (система отвязала службу)");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Намеренно пусто: см. комментарий к классу — читаем по запросу.
    }

    @Override
    public void onInterrupt() {
    }

    /** Одна найденная строка вместе с местом, где она стоит. */
    public static class Line {
        public final String text;
        public final Rect bounds;
        /** true, если координаты вне экрана (WebView считает от начала документа). */
        public final boolean virtual;

        Line(String text, Rect bounds, boolean virtual) {
            this.text = text;
            this.bounds = bounds;
            this.virtual = virtual;
        }
    }

    /**
     * Обойти дерево активного окна и собрать текст.
     *
     * ПРО recycle(): начиная с API 33 он объявлен устаревшим — узлы
     * освобождает сборщик мусора. Не вызываем его специально: на обходе в
     * несколько тысяч узлов каждые 600 мс лишних утечек это не создаёт, а
     * вызов устаревшего метода мог бы упасть на будущих версиях.
     *
     * @param zone       если задана — берём только строки, чей центр внутри;
     *                   null означает «весь экран»
     * @param screenW    ширина экрана, нужна для распознавания виртуальных координат
     * @param screenH    высота экрана
     * @param includeDesc брать ли contentDescription. У иконок он дублирует
     *                   назначение кнопки («Уведомление TikTok») и в режиме
     *                   чтения статьи только мусорит, но у картинок с подписью
     *                   это единственный источник текста.
     */
    public List<Line> readScreen(Rect zone, int screenW, int screenH, boolean includeDesc) {
        List<Line> out = new ArrayList<>();
        AccessibilityNodeInfo root = readableRoot();
        if (root == null) return out;
        visitedNodes = 0;
        collect(root, zone, screenW, screenH, includeDesc, out, 0);
        return out;
    }

    /**
     * Корень окна, из которого нужно читать.
     *
     * РАНЬШЕ был только getRootInActiveWindow() — и это источник бага
     * «прочитал один экран и замолчал»: активным становится окно, которым
     * пользователь коснулся ПОСЛЕДНИМ. Тап по шарику — и «активное окно»
     * уже наш оверлей: poll честно обходил собственное пустое дерево, и
     * чтение стояло, пока пользователь снова не коснётся приложения.
     *
     * Теперь: активное окно не нашего пакета — читаем его (как раньше);
     * наше или отсутствует — спускаемся по getWindows() до первого чужого
     * видимого окна: это и есть приложение ПОД нашими оверлеями. В
     * запасном пути пропускаем клавиатуру и SystemUI: статус-бар всегда
     * сверху и иначе воровал бы выбор; открытая шторка ловится первым
     * путём — она активна, пока ею пользуются.
     */
    private AccessibilityNodeInfo readableRoot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            CharSequence p = root.getPackageName();
            if (p == null || !p.toString().equals(getPackageName())) return root;
        }
        List<android.view.accessibility.AccessibilityWindowInfo> ws = getWindows();
        if (ws != null) {
            // Проход 1: активные/фокусированные чужие окна — не зависят от
            // порядка списка. Проход 2: верхнее чужое окно по z-порядку.
            for (int pass = 0; pass < 2; pass++) {
                for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
                    if (w == null) continue;
                    if (pass == 0 && !(w.isActive() || w.isFocused())) continue;
                    int t = w.getType();
                    if (t == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null) continue;
                    CharSequence p = r.getPackageName();
                    if (p == null || p.toString().equals(getPackageName())) continue;
                    if ("com.android.systemui".contentEquals(p)) continue;
                    return r;
                }
            }
        }
        return root; // может быть null — окно ещё не готово, ждём
    }

    /**
     * Потолок числа узлов на один обход. Обычный экран — единицы тысяч
     * узлов, но WebView-страница без лимита отдаёт десятки тысяч, и poll()
     * каждые 600 мс превращался в секундные фризы главного потока. 4000 —
     * с запасом над любым реальным экраном.
     */
    private static final int MAX_NODES = 4000;
    private int visitedNodes;

    /**
     * Пакет окна, из которого читаем. Нужен, чтобы остановить сканирование,
     * когда пользователь покинул приложение — иначе poll продолжит читать
     * домашний экран и соберёт все иконки и виджеты.
     */
    public String getActiveWindowPackage() {
        AccessibilityNodeInfo root = readableRoot();
        if (root == null) return null;
        CharSequence pkg = root.getPackageName();
        return pkg == null ? null : pkg.toString();
    }

    /**
     * Рекурсивный обход. Глубина ограничена: у некоторых приложений дерево
     * зацикливается на самоссылающихся узлах, и без предела обход не
     * заканчивается.
     */
    private void collect(AccessibilityNodeInfo node, Rect zone,
                         int screenW, int screenH, boolean includeDesc,
                         List<Line> out, int depth) {
        if (node == null || depth > 60) return;
        if (++visitedNodes > MAX_NODES) return;

        CharSequence cs = node.getText();
        String text = cs == null ? null : cs.toString().trim();
        /*
         * contentDescription — только у ЛИСТА без кликабельности: это
         * alt-текст картинок (подписи постов и фото), который иначе
         * терялся совсем. У кнопок и панелей desc — служебный шум
         * («Отправить», «Уведомление»), его пропускаем.
         */
        if ((text == null || text.isEmpty()) && includeDesc
                && node.getChildCount() == 0 && !node.isClickable()) {
            CharSequence d = node.getContentDescription();
            text = d == null ? null : d.toString().trim();
        }

        if (text != null && !text.isEmpty()) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);

            /*
             * ЛОВУШКА, ЗАМЕРЕННАЯ НА ЖИВЫХ ПРИЛОЖЕНИЯХ. Прямоугольники
             * приходят невалидными: видел bottom меньше top
             * (top=2712, bottom=2565) и отрицательные координаты
             * (top=343, bottom=-93). Это узлы, частично уехавшие за границу
             * окна. Без нормализации центр считается неверно и зона
             * отсекает вообще всё.
             */
            int left = Math.min(b.left, b.right);
            int right = Math.max(b.left, b.right);
            int top = Math.min(b.top, b.bottom);
            int bottom = Math.max(b.top, b.bottom);
            Rect norm = new Rect(left, top, right, bottom);

            int cx = (left + right) / 2;
            int cy = (top + bottom) / 2;

            /*
             * ВТОРАЯ ЛОВУШКА, ВАЖНЕЕ ПЕРВОЙ. WebView отдаёт координаты в
             * системе отсчёта ДОКУМЕНТА, а не экрана: в браузере на статье
             * Википедии y шёл от -4297 до -3582 при экране 0..2800. Это не
             * мусор, а настоящий текст страницы. Если применить к нему зону,
             * выбрасывается почти всё — в замере осталась 1 строка из 18.
             * Поэтому такие строки помечаем виртуальными и зону к ним не
             * применяем.
             */
            boolean virtual = cx < 0 || cy < 0 || cx > screenW || cy > screenH;

            /*
             * БЫЛ БАГ ФАНТОМНОГО ТЕКСТА: приложение читало текст из узлов,
             * которых не видно на экране — скрытые View (visibility=GONE),
             * off-screen контент, системные элементы. isVisibleToUser()
             * отсекает их. Виртуальные (WebView) узлы проверяем отдельно:
             * WebView не всегда корректно сообщает видимость, а текст там
             * настоящий.
             */
            boolean visible = virtual || node.isVisibleToUser();

            if (visible) {
                boolean keep = virtual || zone == null || zone.contains(cx, cy);
                if (keep) out.add(new Line(text, norm, virtual));
            }
        }

        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            collect(child, zone, screenW, screenH, includeDesc, out, depth + 1);
        }
    }

    /**
     * Подобрать зону содержимого автоматически.
     *
     * ИДЕЯ: у экрана с текстом почти всегда есть прокручиваемый контейнер, и
     * именно он занимает область содержимого — без шапки, панели навигации и
     * кнопок. Берём самый большой по площади прокручиваемый узел.
     *
     * Вырожденные прямоугольники отбрасываем по той же причине, что описана
     * выше: площадь получилась бы отрицательной.
     */
    public Rect autoZone(int screenW, int screenH) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        Rect best = null;
        long bestArea = 0;
        List<AccessibilityNodeInfo> stack = new ArrayList<>();
        stack.add(root);
        int guard = 0;
        while (!stack.isEmpty() && guard++ < 4000) {
            AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
            if (n == null) continue;
            if (n.isScrollable()) {
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                int l = Math.max(0, Math.min(b.left, b.right));
                int r = Math.min(screenW, Math.max(b.left, b.right));
                int t = Math.max(0, Math.min(b.top, b.bottom));
                int bo = Math.min(screenH, Math.max(b.top, b.bottom));
                long area = (long) (r - l) * (bo - t);
                if (r - l >= 100 && bo - t >= 200 && area > bestArea) {
                    bestArea = area;
                    best = new Rect(l, t, r, bo);
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) stack.add(c);
            }
        }
        return best;
    }
}
