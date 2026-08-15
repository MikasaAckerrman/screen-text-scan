package com.screentextscan;

import android.graphics.Rect;

/**
 * Геометрия рамки выбора зоны: попадание в ручки, изменение размера,
 * перетаскивание, ограничение экраном.
 *
 * ЗАЧЕМ ОТДЕЛЬНЫМ КЛАССОМ. Это единственная часть выбора зоны, где легко
 * ошибиться незаметно: перепутанная сторона при растягивании или потерянное
 * ограничение дают рамку, которая уезжает за экран или выворачивается
 * наизнанку. На телефоне такое ловится долго, а тестом — сразу. Класс не
 * зависит ни от чего, кроме Rect, поэтому проверяется обычным javac.
 *
 * ПОЧЕМУ РУЧКИ, А НЕ «НАЧАТЬ ЗАНОВО». В первой версии любое касание внутри
 * начинало рисовать новую рамку — и подправить готовую было нельзя, только
 * обвести заново. Теперь: касание по ручке тянет свою сторону или угол,
 * касание внутри тащит рамку целиком, касание снаружи начинает новую.
 */
public final class ZoneGeometry {

    /** Что схватил палец. */
    public enum Grip {
        NONE,
        MOVE,
        LEFT, RIGHT, TOP, BOTTOM,
        TL, TR, BL, BR
    }

    /** Минимальный размер рамки. Меньше — в неё не попадёт ни одна строка. */
    public static final int MIN_SIZE_DP = 48;

    private ZoneGeometry() {
    }

    /**
     * Определить, за что взялся палец.
     *
     * @param touchSlop радиус ручки в пикселях. Берётся заметно больше
     *                  нарисованного кружка: попасть пальцем в 8 dp точку
     *                  на ходу невозможно, а промах читается как «начать
     *                  новую рамку» — самая обидная ошибка из возможных.
     */
    public static Grip gripAt(Rect r, int x, int y, int touchSlop) {
        if (r == null || r.isEmpty()) return Grip.NONE;

        boolean nearLeft = Math.abs(x - r.left) <= touchSlop;
        boolean nearRight = Math.abs(x - r.right) <= touchSlop;
        boolean nearTop = Math.abs(y - r.top) <= touchSlop;
        boolean nearBottom = Math.abs(y - r.bottom) <= touchSlop;

        // По вертикали/горизонтали палец должен быть в пределах стороны,
        // иначе касание далеко за углом считалось бы попаданием в сторону.
        boolean inX = x >= r.left - touchSlop && x <= r.right + touchSlop;
        boolean inY = y >= r.top - touchSlop && y <= r.bottom + touchSlop;

        // Углы проверяем ПЕРВЫМИ: у угла обе стороны рядом, и если сначала
        // проверить сторону, до угла дело не дойдёт никогда.
        if (nearLeft && nearTop) return Grip.TL;
        if (nearRight && nearTop) return Grip.TR;
        if (nearLeft && nearBottom) return Grip.BL;
        if (nearRight && nearBottom) return Grip.BR;

        if (nearLeft && inY) return Grip.LEFT;
        if (nearRight && inY) return Grip.RIGHT;
        if (nearTop && inX) return Grip.TOP;
        if (nearBottom && inX) return Grip.BOTTOM;

        if (r.contains(x, y)) return Grip.MOVE;
        return Grip.NONE;
    }

    /**
     * Применить перемещение пальца.
     *
     * @param base  рамка на момент касания (не текущая!) — иначе смещение
     *              накапливается с ошибкой и рамка «убегает» от пальца
     * @param grip  что схвачено
     * @param dx    смещение пальца от точки касания
     * @param dy    смещение пальца от точки касания
     * @param bounds область, за которую нельзя выходить (обычно экран)
     * @param minSize минимальная сторона
     */
    public static Rect apply(Rect base, Grip grip, int dx, int dy,
                             Rect bounds, int minSize) {
        Rect r = new Rect(base);
        switch (grip) {
            case MOVE: {
                /*
                 * При перетаскивании размер сохраняется, поэтому смещение
                 * ограничиваем ДО применения. Если сначала сдвинуть, а потом
                 * обрезать по экрану, рамка у края начнёт сжиматься — палец
                 * тянет, а она худеет.
                 */
                int maxDx = bounds.right - base.right;
                int minDx = bounds.left - base.left;
                int maxDy = bounds.bottom - base.bottom;
                int minDy = bounds.top - base.top;
                int cdx = clamp(dx, minDx, maxDx);
                int cdy = clamp(dy, minDy, maxDy);
                r.offset(cdx, cdy);
                return r;
            }
            case LEFT:
                r.left = base.left + dx;
                break;
            case RIGHT:
                r.right = base.right + dx;
                break;
            case TOP:
                r.top = base.top + dy;
                break;
            case BOTTOM:
                r.bottom = base.bottom + dy;
                break;
            case TL:
                r.left = base.left + dx;
                r.top = base.top + dy;
                break;
            case TR:
                r.right = base.right + dx;
                r.top = base.top + dy;
                break;
            case BL:
                r.left = base.left + dx;
                r.bottom = base.bottom + dy;
                break;
            case BR:
                r.right = base.right + dx;
                r.bottom = base.bottom + dy;
                break;
            default:
                return r;
        }
        return normalize(r, bounds, minSize);
    }

    /**
     * Привести рамку в порядок: не вывернутая, не меньше минимума, внутри
     * границ.
     *
     * ПРО ВЫВОРАЧИВАНИЕ: если тянуть левую сторону правее правой, получится
     * left > right. Rect такое не запрещает, но `contains` перестаёт
     * работать, и рамка становится «невидимой» для попаданий. Поэтому
     * стороны меняем местами, а не просто ограничиваем — палец продолжает
     * тянуть ту же точку, просто она стала другой стороной.
     *
     * ПРО ГРАНИЦЫ — здесь ОБРЕЗАЕМ сторону, а не сдвигаем рамку целиком.
     * Разница принципиальная, и её нашёл тест: при растягивании за край
     * сдвиг «уводил» противоположную сторону, хотя палец её не трогал.
     * Растягивание обязано двигать ТОЛЬКО схваченную сторону. Сдвиг
     * применяется лишь при перетаскивании, и он сделан отдельно в apply(),
     * до вызова этого метода.
     */
    public static Rect normalize(Rect r, Rect bounds, int minSize) {
        int left = Math.min(r.left, r.right);
        int right = Math.max(r.left, r.right);
        int top = Math.min(r.top, r.bottom);
        int bottom = Math.max(r.top, r.bottom);

        if (bounds != null) {
            left = clamp(left, bounds.left, bounds.right);
            right = clamp(right, bounds.left, bounds.right);
            top = clamp(top, bounds.top, bounds.bottom);
            bottom = clamp(bottom, bounds.top, bounds.bottom);
        }

        /*
         * Минимум добираем ПОСЛЕ обрезки и в ту сторону, где есть место.
         * Если добирать до обрезки, у края экрана рамка вылезет наружу и
         * обрезка вернёт её обратно — минимум окажется нарушен молча.
         */
        if (right - left < minSize) {
            if (bounds != null && right + (minSize - (right - left)) > bounds.right) {
                left = Math.max(bounds.left, right - minSize);
            } else {
                right = left + minSize;
            }
        }
        if (bottom - top < minSize) {
            if (bounds != null && bottom + (minSize - (bottom - top)) > bounds.bottom) {
                top = Math.max(bounds.top, bottom - minSize);
            } else {
                bottom = top + minSize;
            }
        }
        return new Rect(left, top, right, bottom);
    }

    /**
     * Пересчитать зону при повороте экрана.
     *
     * ПОЧЕМУ ПРОПОРЦИОНАЛЬНО, А НЕ СБРОС. Зона задана в пикселях экрана, и
     * после поворота эти числа означают совсем другое место. Сбрасывать её
     * жалко: пользователь уже выбрал, что читать, и содержимое обычно
     * переливается в новую ширину, оставаясь тем же самым. Поэтому
     * переводим доли: «две трети высоты от верха» остаются двумя третями.
     *
     * Точным это быть не может по определению — текст переверстался. Но
     * попасть примерно в ту же часть экрана лучше, чем не попасть никуда.
     */
    public static Rect rotate(Rect zone, int oldW, int oldH, int newW, int newH) {
        if (zone == null || oldW <= 0 || oldH <= 0) return null;
        float fx = (float) newW / oldW;
        float fy = (float) newH / oldH;
        Rect r = new Rect(
                Math.round(zone.left * fx), Math.round(zone.top * fy),
                Math.round(zone.right * fx), Math.round(zone.bottom * fy));
        return normalize(r, new Rect(0, 0, newW, newH), 1);
    }

    static int clamp(int v, int lo, int hi) {
        if (lo > hi) return lo;      // область пустая — деваться некуда
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
