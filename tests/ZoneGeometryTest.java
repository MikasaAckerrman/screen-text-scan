import android.graphics.Rect;

/**
 * Тест геометрии рамки. Без JUnit и без устройства: класс ZoneGeometry
 * намеренно не зависит ни от чего, кроме Rect, а Rect подменяется
 * заглушкой (см. stub/android/graphics/Rect.java).
 *
 * ЗАЧЕМ. Ручки рамки — место, где ошибка тихая: приложение соберётся,
 * рамка нарисуется, но потянув за левый край получишь вывернутый
 * прямоугольник или сжатую у края экрана рамку. На телефоне это ловится
 * долго и раздражает; здесь — мгновенно.
 */
public class ZoneGeometryTest {

    static int ok = 0, fail = 0;

    static void check(String what, boolean cond) {
        if (cond) {
            ok++;
            System.out.println("ОК    " + what);
        } else {
            fail++;
            System.out.println("ПРОВАЛ " + what);
        }
    }

    static void eq(String what, Rect got, int l, int t, int r, int b) {
        boolean good = got.left == l && got.top == t && got.right == r && got.bottom == b;
        if (!good) {
            System.out.println("       ожидали [" + l + "," + t + "," + r + "," + b
                    + "] получили [" + got.left + "," + got.top + "," + got.right + "," + got.bottom + "]");
        }
        check(what, good);
    }

    public static void main(String[] a) {
        Rect screen = new Rect(0, 0, 1000, 2000);
        Rect zone = new Rect(200, 400, 800, 1200);
        int slop = 40;

        // ---- определение того, за что схватились
        check("центр рамки = перетаскивание",
                ZoneGeometry.gripAt(zone, 500, 800, slop) == ZoneGeometry.Grip.MOVE);
        check("левая сторона",
                ZoneGeometry.gripAt(zone, 205, 800, slop) == ZoneGeometry.Grip.LEFT);
        check("правая сторона",
                ZoneGeometry.gripAt(zone, 795, 800, slop) == ZoneGeometry.Grip.RIGHT);
        check("верхняя сторона",
                ZoneGeometry.gripAt(zone, 500, 405, slop) == ZoneGeometry.Grip.TOP);
        check("нижняя сторона",
                ZoneGeometry.gripAt(zone, 500, 1195, slop) == ZoneGeometry.Grip.BOTTOM);
        check("снаружи = ничего",
                ZoneGeometry.gripAt(zone, 50, 50, slop) == ZoneGeometry.Grip.NONE);

        /*
         * Углы имеют ПРИОРИТЕТ над сторонами. Если проверять стороны
         * первыми, до угла дело не дойдёт никогда — у угла обе стороны
         * рядом, и вернётся первая по порядку.
         */
        check("угол важнее стороны (левый верх)",
                ZoneGeometry.gripAt(zone, 202, 402, slop) == ZoneGeometry.Grip.TL);
        check("правый низ",
                ZoneGeometry.gripAt(zone, 798, 1198, slop) == ZoneGeometry.Grip.BR);

        // ---- изменение размера
        eq("тянем левую сторону вправо",
                ZoneGeometry.apply(zone, ZoneGeometry.Grip.LEFT, 100, 0, screen, 48),
                300, 400, 800, 1200);
        eq("тянем правый низ",
                ZoneGeometry.apply(zone, ZoneGeometry.Grip.BR, 100, 100, screen, 48),
                200, 400, 900, 1300);

        /*
         * ВЫВОРАЧИВАНИЕ. Тянем левую сторону далеко правее правой. Стороны
         * должны поменяться местами, а не дать left>right: с вывернутым
         * прямоугольником contains() перестаёт работать и рамка становится
         * непопадаемой.
         */
        Rect flipped = ZoneGeometry.apply(zone, ZoneGeometry.Grip.LEFT, 900, 0, screen, 48);
        check("вывернутая рамка нормализуется", flipped.left < flipped.right);
        check("после выворота левая сторона = старой правой", flipped.left == 800);

        // Минимальный размер соблюдается
        Rect tiny = ZoneGeometry.apply(zone, ZoneGeometry.Grip.RIGHT, -590, 0, screen, 48);
        check("минимальная ширина держится", tiny.width() >= 48);

        // ---- перетаскивание
        eq("перетаскивание сохраняет размер",
                ZoneGeometry.apply(zone, ZoneGeometry.Grip.MOVE, 50, 50, screen, 48),
                250, 450, 850, 1250);

        /*
         * ГЛАВНАЯ ЛОВУШКА ПЕРЕТАСКИВАНИЯ. У края экрана рамка обязана
         * ОСТАНОВИТЬСЯ, сохранив размер. Если сначала сдвинуть, а потом
         * обрезать по границе, она начнёт сжиматься: палец тянет, а рамка
         * худеет — выглядит как поломка.
         */
        Rect atEdge = ZoneGeometry.apply(zone, ZoneGeometry.Grip.MOVE, 9999, 0, screen, 48);
        check("у края размер сохранён", atEdge.width() == zone.width());
        check("у края не вышли за экран", atEdge.right == 1000);

        Rect atTop = ZoneGeometry.apply(zone, ZoneGeometry.Grip.MOVE, 0, -9999, screen, 48);
        check("у верхнего края высота сохранена", atTop.height() == zone.height());
        check("у верхнего края прижались к нулю", atTop.top == 0);

        // ---- нормализация сама по себе
        eq("нормализация вывернутого",
                ZoneGeometry.normalize(new Rect(800, 1200, 200, 400), screen, 1),
                200, 400, 800, 1200);

        Rect huge = ZoneGeometry.normalize(new Rect(-500, -500, 5000, 5000), screen, 1);
        eq("больше экрана обрезается по экрану", huge, 0, 0, 1000, 2000);

        // ---- поворот экрана
        /*
         * Зона задана в пикселях, и после поворота те же числа означают
         * другое место. Сбрасывать её жалко — пользователь уже выбрал, что
         * читать. Переводим пропорционально: доли остаются долями.
         */
        Rect rotated = ZoneGeometry.rotate(new Rect(0, 500, 1000, 1500), 1000, 2000, 2000, 1000);
        check("после поворота вписана в новый экран",
                rotated.right <= 2000 && rotated.bottom <= 1000);
        check("после поворота доля по вертикали сохранена",
                Math.abs(rotated.top - 250) <= 2);
        check("после поворота ширина растянулась", rotated.width() == 2000);

        check("поворот без зоны даёт null",
                ZoneGeometry.rotate(null, 1000, 2000, 2000, 1000) == null);

        // Вырожденный экран не должен приводить к делению на ноль
        check("нулевой старый размер не ломает",
                ZoneGeometry.rotate(zone, 0, 0, 100, 100) == null);

        System.out.println();
        System.out.println("итог: " + ok + " ок, " + fail + " провал");
        if (fail > 0) System.exit(1);
    }
}
