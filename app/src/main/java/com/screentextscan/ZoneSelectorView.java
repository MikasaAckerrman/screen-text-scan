package com.screentextscan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;

/**
 * Выбор зоны чтения пальцем.
 *
 * ПОЧЕМУ РИСУЕМ САМИ, А НЕ БЕРЁМ ГОТОВЫЙ ЭЛЕМЕНТ. Нужно затемнить весь экран
 * КРОМЕ выделенной области — стандартных средств для этого нет. Рисуем четыре
 * затемняющих прямоугольника вокруг рамки: так видно и что выделено, и что
 * под выделением, потому что сама область не затемнена вообще.
 *
 * ТРИ ВЫХОДА, А НЕ ОДИН. Обводить пальцем каждый раз утомительно, поэтому
 * кроме ручной рамки есть «весь экран» и «подобрать самому» — последнее
 * находит прокручиваемый контейнер, который почти всегда и есть содержимое.
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
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnBg = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Listener listener;
    private float x0, y0, x1, y1;
    private boolean dragging, hasRect;

    /** Кнопки внизу: подобрать / весь экран / отмена. Заполняются в onDraw. */
    private final Rect btnAuto = new Rect();
    private final Rect btnWhole = new Rect();
    private final Rect btnCancel = new Rect();
    private final Rect btnAccept = new Rect();

    public ZoneSelectorView(Context c) {
        super(c);
        dim.setColor(Color.parseColor("#B3000000"));
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(dp(2));
        frame.setColor(Color.parseColor("#0A84FF"));
        hint.setColor(Color.WHITE);
        hint.setTextSize(sp(14));
        hint.setTextAlign(Paint.Align.CENTER);
        btnBg.setColor(Color.parseColor("#EE1C1C1E"));
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();

        if (hasRect) {
            Rect r = current();
            // затемняем всё, кроме выбранного: четыре полосы вокруг рамки
            c.drawRect(0, 0, w, r.top, dim);
            c.drawRect(0, r.bottom, w, h, dim);
            c.drawRect(0, r.top, r.left, r.bottom, dim);
            c.drawRect(r.right, r.top, w, r.bottom, dim);
            c.drawRect(r, frame);
            String size = r.width() + "×" + r.height();
            c.drawText(size, r.centerX(), Math.max(r.top - dp(8), sp(16)), hint);
        } else {
            c.drawRect(0, 0, w, h, dim);
            c.drawText("Обведите пальцем область, откуда читать текст",
                    w / 2f, h / 2f, hint);
        }

        // ---- панель кнопок
        int bh = dp(46), pad = dp(10);
        int y = h - bh - dp(28);
        if (hasRect) {
            int half = (w - pad * 3) / 2;
            btnAccept.set(pad, y, pad + half, y + bh);
            btnCancel.set(pad * 2 + half, y, w - pad, y + bh);
            btnAuto.setEmpty();
            btnWhole.setEmpty();
            drawButton(c, btnAccept, "Читать эту область");
            drawButton(c, btnCancel, "Заново");
        } else {
            int third = (w - pad * 4) / 3;
            btnAuto.set(pad, y, pad + third, y + bh);
            btnWhole.set(pad * 2 + third, y, pad * 2 + third * 2, y + bh);
            btnCancel.set(pad * 3 + third * 2, y, w - pad, y + bh);
            btnAccept.setEmpty();
            drawButton(c, btnAuto, "Подобрать");
            drawButton(c, btnWhole, "Весь экран");
            drawButton(c, btnCancel, "Отмена");
        }
    }

    private void drawButton(Canvas c, Rect r, String label) {
        c.drawRoundRect(r.left, r.top, r.right, r.bottom, dp(12), dp(12), btnBg);
        Paint p = new Paint(hint);
        p.setTextSize(sp(13));
        c.drawText(label, r.centerX(), r.centerY() + sp(5), p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int x = (int) e.getX(), y = (int) e.getY();

        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            if (hit(btnAuto, x, y)) {
                if (listener != null) listener.onAutoZone();
                return true;
            }
            if (hit(btnWhole, x, y)) {
                if (listener != null) listener.onWholeScreen();
                return true;
            }
            if (hit(btnCancel, x, y)) {
                if (hasRect) {
                    // «Заново» — сбрасываем рамку, а не выходим совсем
                    hasRect = false;
                    invalidate();
                } else if (listener != null) {
                    listener.onCancel();
                }
                return true;
            }
            if (hit(btnAccept, x, y)) {
                if (listener != null) listener.onZoneChosen(current());
                return true;
            }
            x0 = x1 = x;
            y0 = y1 = y;
            dragging = true;
            hasRect = false;
            invalidate();
            return true;
        }

        if (!dragging) return super.onTouchEvent(e);

        switch (e.getAction()) {
            case MotionEvent.ACTION_MOVE:
                x1 = x;
                y1 = y;
                hasRect = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                x1 = x;
                y1 = y;
                Rect r = current();
                /*
                 * Крошечная рамка — почти наверняка случайный тап, а не выбор.
                 * Без этой проверки одно неловкое касание давало бы зону в
                 * несколько пикселей, и текст не нашёлся бы вообще.
                 */
                hasRect = r.width() > dp(40) && r.height() > dp(40);
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    /** Рамка, нормализованная: тянуть можно в любую сторону. */
    private Rect current() {
        return new Rect(
                (int) Math.min(x0, x1), (int) Math.min(y0, y1),
                (int) Math.max(x0, x1), (int) Math.max(y0, y1));
    }

    private static boolean hit(Rect r, int x, int y) {
        return !r.isEmpty() && r.contains(x, y);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private float sp(int v) {
        /*
         * scaledDensity объявлен устаревшим с API 34: система перешла на
         * TypedValue-пересчёт. Считаем через TypedValue — так значение
         * учитывает и масштаб шрифта, и плотность, и не сломается дальше.
         */
        return android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }
}
