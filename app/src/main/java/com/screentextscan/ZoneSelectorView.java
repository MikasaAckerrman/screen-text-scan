package com.screentextscan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/**
 * Выбор зоны чтения пальцем.
 *
 * ЧТО ИЗМЕНИЛОСЬ ПО СРАВНЕНИЮ С ПЕРВОЙ ВЕРСИЕЙ. Раньше любое касание
 * начинало НОВУЮ рамку, поэтому подправить уже нарисованную было нельзя —
 * только обвести заново. Теперь рамка живёт: за ручки её растягивают, за
 * середину перетаскивают, и лишь касание за пределами начинает новую.
 * Вся геометрия этого — в ZoneGeometry, отдельно и под тестами, потому что
 * ошибки там тихие: рамка либо выворачивается, либо сжимается у края.
 *
 * ПОЧЕМУ РИСУЕМ САМИ. Нужно затемнить экран КРОМЕ выделенной области —
 * стандартных средств нет. Четыре затемняющих прямоугольника вокруг рамки:
 * внутри не затемнено вообще, поэтому видно, что именно будет прочитано.
 */
public class ZoneSelectorView extends View {

    public interface Listener {
        void onZoneChosen(Rect zone);

        void onWholeScreen();

        void onAutoZone();

        void onCancel();
    }

    private final Paint dim = new Paint();
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleCore = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnBgMain = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Listener listener;
    private Rect zone;

    /** Состояние текущего касания. */
    private ZoneGeometry.Grip grip = ZoneGeometry.Grip.NONE;
    private Rect gripBase;
    private float downX, downY;
    private boolean drawingNew;

    private final Rect btnAccept = new Rect();
    private final Rect btnAuto = new Rect();
    private final Rect btnWhole = new Rect();
    private final Rect btnReset = new Rect();
    private final Rect btnCancel = new Rect();

    public ZoneSelectorView(Context c) {
        super(c);
        dim.setColor(0xB8000000);

        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(dp(2));
        frame.setColor(Color.WHITE);

        handle.setColor(Color.WHITE);
        handle.setStyle(Paint.Style.FILL);
        handleCore.setColor(0xFF101014);
        handleCore.setStyle(Paint.Style.FILL);

        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);

        btnBg.setColor(0xF01C1C1E);
        btnBgMain.setColor(0xFFFFFFFF);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** Начальная рамка: подсказка вместо чистого листа. */
    public void setInitialZone(Rect r) {
        zone = r == null ? null : new Rect(r);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        if (zone != null && !zone.isEmpty()) {
            c.drawRect(0, 0, w, zone.top, dim);
            c.drawRect(0, zone.bottom, w, h, dim);
            c.drawRect(0, zone.top, zone.left, zone.bottom, dim);
            c.drawRect(zone.right, zone.top, w, zone.bottom, dim);
            c.drawRect(zone, frame);
            drawHandles(c, zone);

            text.setTextSize(sp(12));
            String size = zone.width() + " × " + zone.height();
            float ty = zone.top > sp(30) ? zone.top - sp(9) : zone.bottom + sp(20);
            c.drawText(size, zone.centerX(), ty, text);
        } else {
            c.drawRect(0, 0, w, h, dim);
            text.setTextSize(sp(15));
            c.drawText("Обведите пальцем область,", w / 2f, h / 2f - sp(12), text);
            c.drawText("откуда читать текст", w / 2f, h / 2f + sp(10), text);
        }

        drawButtons(c, w, h);
    }

    /**
     * Ручки: четыре угла и четыре середины сторон.
     *
     * Рисуем белым кружком с тёмным ядром — такая «мишень» видна и на
     * светлом, и на тёмном содержимом. Однотонная точка на фотографии
     * теряется.
     */
    private void drawHandles(Canvas c, Rect r) {
        float rad = dp(7);
        int[][] pts = {
                {r.left, r.top}, {r.right, r.top}, {r.left, r.bottom}, {r.right, r.bottom},
                {r.centerX(), r.top}, {r.centerX(), r.bottom},
                {r.left, r.centerY()}, {r.right, r.centerY()},
        };
        for (int[] p : pts) {
            c.drawCircle(p[0], p[1], rad, handle);
            c.drawCircle(p[0], p[1], rad * 0.45f, handleCore);
        }
    }

    private void drawButtons(Canvas c, int w, int h) {
        int pad = (int) dp(10);
        int bh = (int) dp(48);
        int y = h - bh - (int) dp(30);

        if (zone != null && !zone.isEmpty()) {
            /*
             * Главное действие («Читать») выделено белой заливкой: когда
             * рамка уже стоит, из трёх кнопок нужна почти всегда одна, и
             * искать её глазами не должно быть нужно.
             */
            int wide = (w - pad * 2) * 62 / 100;
            btnAccept.set(pad, y, pad + wide, y + bh);
            int rest = w - pad * 3 - wide;
            btnReset.set(pad * 2 + wide, y, pad * 2 + wide + rest / 2, y + bh);
            btnCancel.set(pad * 2 + wide + rest / 2 + pad / 2, y, w - pad, y + bh);
            btnAuto.setEmpty();
            btnWhole.setEmpty();

            drawButton(c, btnAccept, "Читать область", true);
            drawButton(c, btnReset, "Заново", false);
            drawButton(c, btnCancel, "Отмена", false);
        } else {
            int third = (w - pad * 4) / 3;
            btnAuto.set(pad, y, pad + third, y + bh);
            btnWhole.set(pad * 2 + third, y, pad * 2 + third * 2, y + bh);
            btnCancel.set(pad * 3 + third * 2, y, w - pad, y + bh);
            btnAccept.setEmpty();
            btnReset.setEmpty();

            drawButton(c, btnAuto, "Подобрать", true);
            drawButton(c, btnWhole, "Весь экран", false);
            drawButton(c, btnCancel, "Отмена", false);
        }
    }

    private void drawButton(Canvas c, Rect r, String label, boolean primary) {
        RectF box = new RectF(r);
        c.drawRoundRect(box, dp(13), dp(13), primary ? btnBgMain : btnBg);
        Paint p = new Paint(text);
        p.setTextSize(sp(13));
        p.setColor(primary ? 0xFF101014 : Color.WHITE);
        c.drawText(label, r.centerX(), r.centerY() + sp(4.5f), p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int x = (int) e.getX(), y = (int) e.getY();

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return onDown(x, y);

            case MotionEvent.ACTION_MOVE:
                return onMove(x, y);

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return onUp();
        }
        return super.onTouchEvent(e);
    }

    private boolean onDown(int x, int y) {
        // Кнопки проверяем ПЕРВЫМИ: они лежат поверх затемнения, и касание
        // по ним не должно начинать рисование рамки.
        if (hit(btnAuto, x, y)) {
            if (listener != null) listener.onAutoZone();
            return true;
        }
        if (hit(btnWhole, x, y)) {
            if (listener != null) listener.onWholeScreen();
            return true;
        }
        if (hit(btnAccept, x, y)) {
            if (listener != null && zone != null) listener.onZoneChosen(new Rect(zone));
            return true;
        }
        if (hit(btnReset, x, y)) {
            zone = null;
            invalidate();
            return true;
        }
        if (hit(btnCancel, x, y)) {
            if (listener != null) listener.onCancel();
            return true;
        }

        downX = x;
        downY = y;

        /*
         * Радиус попадания в ручку заметно больше нарисованного кружка:
         * попасть пальцем в точку 7 dp на ходу невозможно, а промах читается
         * как «начать новую рамку» — самая обидная ошибка из возможных,
         * потому что стирает уже сделанную работу.
         */
        grip = ZoneGeometry.gripAt(zone, x, y, (int) dp(26));
        if (grip != ZoneGeometry.Grip.NONE) {
            gripBase = new Rect(zone);
            drawingNew = false;
        } else {
            drawingNew = true;
            gripBase = null;
        }
        return true;
    }

    private boolean onMove(int x, int y) {
        if (drawingNew) {
            zone = ZoneGeometry.normalize(
                    new Rect((int) downX, (int) downY, x, y),
                    screenRect(), 1);
            invalidate();
            return true;
        }
        if (grip != ZoneGeometry.Grip.NONE && gripBase != null) {
            zone = ZoneGeometry.apply(gripBase, grip,
                    (int) (x - downX), (int) (y - downY),
                    screenRect(), (int) dp(ZoneGeometry.MIN_SIZE_DP));
            invalidate();
            return true;
        }
        return true;
    }

    private boolean onUp() {
        if (drawingNew && zone != null) {
            /*
             * Крошечная рамка — почти наверняка случайный тап, а не выбор.
             * Без этой проверки одно неловкое касание давало бы зону в
             * несколько пикселей, и текст не нашёлся бы вообще.
             */
            int min = (int) dp(ZoneGeometry.MIN_SIZE_DP);
            if (zone.width() < min || zone.height() < min) zone = null;
        }
        drawingNew = false;
        grip = ZoneGeometry.Grip.NONE;
        gripBase = null;
        invalidate();
        return true;
    }

    private Rect screenRect() {
        return new Rect(0, 0, getWidth(), getHeight());
    }

    private static boolean hit(Rect r, int x, int y) {
        return !r.isEmpty() && r.contains(x, y);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }
}
