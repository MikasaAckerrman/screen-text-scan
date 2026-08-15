package android.graphics;

/**
 * Минимальная замена android.graphics.Rect для локальных тестов.
 *
 * ЗАЧЕМ СВОЯ, А НЕ ИЗ android.jar. Классы в android.jar — пустые заготовки:
 * каждый метод бросает RuntimeException("Stub!"). Компилировать против них
 * можно, выполнять нельзя. Поэтому для запуска тестов подставляем настоящую
 * реализацию — она короткая и её поведение задокументировано.
 *
 * Реализовано ровно то, что использует ZoneGeometry. Ничего лишнего: любое
 * расхождение с настоящим Rect здесь было бы ложью в тесте.
 */
public class Rect {
    public int left, top, right, bottom;

    public Rect() {
    }

    public Rect(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public Rect(Rect r) {
        this(r.left, r.top, r.right, r.bottom);
    }

    public int width() {
        return right - left;
    }

    public int height() {
        return bottom - top;
    }

    public int centerX() {
        return (left + right) >> 1;
    }

    public int centerY() {
        return (top + bottom) >> 1;
    }

    /** Пустой = нулевой или вывернутый. Точно как в настоящем Rect. */
    public boolean isEmpty() {
        return left >= right || top >= bottom;
    }

    public void setEmpty() {
        left = right = top = bottom = 0;
    }

    public void set(int l, int t, int r, int b) {
        left = l;
        top = t;
        right = r;
        bottom = b;
    }

    /**
     * Точка внутри. Верхняя и левая границы включены, правая и нижняя — нет.
     * Это поведение настоящего Rect, и оно важно: иначе касание точно по
     * правому краю считалось бы попаданием и внутрь, и в ручку.
     */
    public boolean contains(int x, int y) {
        return left < right && top < bottom
                && x >= left && x < right && y >= top && y < bottom;
    }

    public void offset(int dx, int dy) {
        left += dx;
        right += dx;
        top += dy;
        bottom += dy;
    }

    @Override
    public String toString() {
        return "Rect(" + left + ", " + top + " - " + right + ", " + bottom + ")";
    }
}
