package com.screentextscan;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Build;
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
                        | AccessibilityEvent.TYPE_VIEW_SCROLLED
                        | AccessibilityEvent.TYPE_WINDOWS_CHANGED)
                    : (AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                        | AccessibilityEvent.TYPE_WINDOWS_CHANGED);
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
    /**
     * Перечитать узел ИЗ ИСТОЧНИКА, в обход кэша.
     *
     * refresh() появился в API 28 (на SDK 34 вариант с аргументом
     * удалён); до 28 остаёмся на кэше — таких устройств у приложения нет.
     * Возвращает false, если узел исчез (элемент списка переработан).
     */
    /**
     * Диагностический дамп всех текстовых кандидатов текущего экрана —
     * для настройки фильтра контролов. В лог: флаги (clickable у узла и
     * родителя, editable), границы и обрезка текста. Ничего не копирует.
     */
    public void dumpCandidates() {
        AccessibilityNodeInfo root = readableRoot(liveForegroundPkg);
        if (root == null) {
            android.util.Log.d("ScreenTextScan", "dump: root=null");
            return;
        }
        class V {
            void walk(AccessibilityNodeInfo n, int depth, boolean parentClickable) {
                if (n == null || depth > 40) return;
                CharSequence cs = n.getText();
                String t = cs == null ? "" : cs.toString().trim();
                CharSequence d = n.getContentDescription();
                String ds = d == null ? "" : d.toString().trim();
                if (!t.isEmpty() || !ds.isEmpty()) {
                    Rect b = new Rect();
                    n.getBoundsInScreen(b);
                    android.util.Log.d("ScreenTextScan", "dump: c=" + n.isClickable()
                            + " pc=" + parentClickable
                            + " e=" + n.isEditable()
                            + " w=" + b.width() + " h=" + b.height()
                            + " t=[" + (t.length() > 24 ? t.substring(0, 24) : t)
                            + "] d=[" + (ds.length() > 24 ? ds.substring(0, 24) : ds) + "]");
                }
                for (int i = 0; i < n.getChildCount(); i++) {
                    walk(n.getChild(i), depth + 1, n.isClickable() || parentClickable);
                }
            }
        }
        new V().walk(root, 0, false);
    }

    private static boolean refreshFromSource(AccessibilityNodeInfo node) {
        if (Build.VERSION.SDK_INT < 28) return true;
        try {
            return node.refresh();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public List<Line> readScreen(String targetPkg, Rect zone, int screenW,
                                 int screenH, boolean includeDesc) {
        List<Line> out = new ArrayList<>();
        AccessibilityNodeInfo root = readableRoot(targetPkg);
        if (root == null) return out;
        /*
         * Корень тоже мог закэшироваться вместе со старым снимком окна:
         * «текст вообще не тот, что на экране» — это именно он. Обновляем
         * и корень (заодно — его список детей).
         */
        if (!refreshFromSource(root)) return out;
        visitedNodes = 0;
        collect(root, zone, screenW, screenH, includeDesc, out, 0, false);
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
    /**
     * Окно приложения, к которому ПРИВЯЗАН скан.
     *
     * Правило пользователя: «текст должен читаться только с того
     * приложения, в котором я нахожусь» — и только с него. Раньше читали
     * «активное окно» (окно последнего касания): пользователь прыгает по
     * приложениям — скан шёл за ним, автокопируя чужой текст на каждом
     * переходе, и в буфере смешивались несколько приложений.
     *
     * Теперь: targetPkg задан (обычный скан) — ищем ЕГО окно: активное →
     * сфокусированное → крупнейшее окно этого пакета. Пропали окна пакета
     * (приложение закрыто) — null, poll решает, что делать.
     *
     * targetPkg == null (диагностические обходы) — прежнее поведение:
     * активное окно чужого пакета, иначе верхнее чужое по z-порядку.
     */
    private AccessibilityNodeInfo readableRoot(String targetPkg) {
        List<android.view.accessibility.AccessibilityWindowInfo> ws = getWindows();

        if (targetPkg != null) {
            AccessibilityNodeInfo fallback = null;
            long fallbackArea = 0;
            if (ws != null) {
                for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
                    if (w == null) continue;
                    int t = w.getType();
                    if (t == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null) continue;
                    CharSequence p = r.getPackageName();
                    if (p == null || !p.toString().equals(targetPkg)) continue;
                    if (w.isActive()) return r;
                    if (fallback == null || w.isFocused()) {
                        Rect b = new Rect();
                        w.getBoundsInScreen(b);
                        long area = (long) b.width() * b.height();
                        if (w.isFocused() || area > fallbackArea) {
                            fallback = r;
                            fallbackArea = area;
                        }
                    }
                }
            }
            // Список окон может отставать (он тоже обновляется событиями,
            // см. liveForegroundPkg) — тогда берём активное окно, если оно
            // принадлежит цели.
            if (fallback == null) {
                AccessibilityNodeInfo active = getRootInActiveWindow();
                if (active != null) {
                    CharSequence p = active.getPackageName();
                    if (p != null && p.toString().equals(targetPkg)) return active;
                }
            }
            return fallback;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            CharSequence p = root.getPackageName();
            if (p == null || !p.toString().equals(getPackageName())) return root;
        }
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
     * ЖИВОЙ пакет приложения на переднем плане.
     *
     * ПОЧЕМУ НЕ getRootInActiveWindow(). Воспроизведено 01.10: пользователь
     * в Ozon, а «активное окно» и весь список getWindows() у клиента a11y
     * застряли на Firefox С УТРА — указатель активного окна и список окон
     * обновляются только СОБЫТИЯМИ, которых наш клиент не получал. Скан
     * привязывался к вруну и читал не то приложение — «читается не с
     * экрана».
     *
     * СОБЫТИЯ — единственный живой источник: они приходят в момент смены
     * окна, кэш не участвует. onAccessibilityEvent записывает сюда пакет
     * каждого TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOWS_CHANGED (свои
     * оверлеи, SystemUI и клавиатуру не считаем).
     */
    private volatile String liveForegroundPkg;
    /** Момент последней СМЕНЫ liveForegroundPkg — для оценки устойчивости. */
    private volatile long livePkgSince;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int t = event.getEventType();
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && t != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
        CharSequence p = event.getPackageName();
        if (p == null) return;
        String s = p.toString();
        if (s.equals(getPackageName()) || s.equals("com.android.systemui")
                || s.equals("com.android.imf")) return;
        if (!s.equals(liveForegroundPkg)) {
            liveForegroundPkg = s;
            livePkgSince = android.os.SystemClock.uptimeMillis();
        }
    }

    /**
     * Пакет, удерживающийся на переднем плане не менее minStableMs.
     *
     * ЗАЧЕМ: транзиенты (боковая панель vivo, переходники) живут секунду;
     * привязка на них = мусор в накопителе. Устойчивость меряем ПО
     * СОБЫТИЯМ (событие смены пакета = точный момент), а не лишними
     * опросами — старт чтения не ждёт холостых циклов.
     */
    public String getStableWindowPackage(long minStableMs) {
        String p = liveForegroundPkg;
        if (p == null) return null;
        return (android.os.SystemClock.uptimeMillis() - livePkgSince) >= minStableMs
                ? p : null;
    }

    /**
     * Пакет приложения на переднем плане СЕЙЧАС — живой трекер событий,
     * не клиентский кэш. Пусто (служба только подключилась, событий ещё
     * не было) — честный null: poll подождёт первое событие.
     */
    public String getActiveWindowPackage() {
        return liveForegroundPkg;
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
     * Рекурсивный обход. Глубина ограничена: у некоторых приложений дерево
     * зацикливается на самоссылающихся узлах, и без предела обход не
     * заканчивается.
     */
    private void collect(AccessibilityNodeInfo node, Rect zone,
                         int screenW, int screenH, boolean includeDesc,
                         List<Line> out, int depth, boolean parentClickable) {
        if (node == null || depth > 60) return;
        if (++visitedNodes > MAX_NODES) return;

        /*
         * ФИЛЬТР КОНТРОЛОВ (корень «скопировал текст, которого не вижу»:
         * «Копировать», «Нравится», «Новый чат», «Вниз», подсказка ввода).
         * Текст кнопки — не контент: узел кликабелен, его родитель
         * кликабелен (подпись внутри кнопки) или это поле ввода (текст
         * поля — подсказка/набранное). Контент чата и статей этими
         * флагами не обладает.
         */
        boolean selfControl = node.isClickable() || parentClickable
                || node.isEditable();

        /*
         * РЕБИНД-ФИКС (корень «прочитал 5 строк и замолчал»). Кэш
         * доступности инвалидируют события, а не опросы — но у
         * RecyclerView-списков (чаты, ленты) при прокрутке элементы
         * ПЕРЕЗАПИСЫВАЮТ ТЕ ЖЕ узлы: id прежние, текст внутри новый.
         * Кэш честно возвращает старый текст по старым id — опрос
         * вечно видит первый снимок. Диагностика 25.09: quiet-scan без
         * оверлеев держал lines=8 одиннадцать опросов подряд.
         * refresh() перечитывает узел напрямую, в обход кэша; заодно
         * обновляются видимость (фантомы уехавших узлов) и число детей.
         */
        if (node.isScrollable()) refreshFromSource(node);

        CharSequence cs = node.getText();
        if (selfControl) cs = null;
        /*
         * Пер-узловый refresh() (лекарство v1.9 от замороженного кэша)
         * снят 01.10: он стоил по IPC-циклу на каждый текст и давал
         * «с задержкой читается». Свежесть теперь обеспечивают живой
         * трекер переднего плана и полная подписка на события во время
         * чтения; refresh остался на корне и скроллящих контейнерах —
         * их один-три, и они обновляют структуру (число детей).
         */
        String text = cs == null ? null : cs.toString().trim();
        /*
         * contentDescription — только у ЛИСТА без кликабельности: это
         * alt-текст картинок (подписи постов и фото), который иначе
         * терялся совсем. У кнопок и панелей desc — служебный шум
         * («Отправить», «Уведомление»), его пропускаем.
         */
        if ((text == null || text.isEmpty()) && includeDesc
                && node.getChildCount() == 0 && !node.isClickable()
                && !parentClickable) {
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
            collect(child, zone, screenW, screenH, includeDesc, out,
                    depth + 1, node.isClickable() || parentClickable);
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
        // Снимок для зоны тоже должен быть свежим: подбирать рамку по
        // закэшированному прошлому экрану — выбирать не то, что видно.
        refreshFromSource(root);
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
