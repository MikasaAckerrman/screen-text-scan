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
        AccessibilityKeepAliveService.start(this);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
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
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return out;
        collect(root, zone, screenW, screenH, includeDesc, out, 0);
        return out;
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

        CharSequence cs = node.getText();
        String text = cs == null ? null : cs.toString().trim();
        if ((text == null || text.isEmpty()) && includeDesc) {
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

            boolean keep = virtual || zone == null || zone.contains(cx, cy);
            if (keep) out.add(new Line(text, norm, virtual));
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
