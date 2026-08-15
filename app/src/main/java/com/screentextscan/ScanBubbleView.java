package com.screentextscan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

/**
 * Плавающая кнопка. Рисуется целиком вручную — три состояния в одном
 * элементе, и переходы между ними должны читаться без подписей.
 *
 * ПОЧЕМУ ЧЁРНО-БЕЛАЯ. Кнопка висит над чужим приложением любого цвета.
 * Цветная (была красная) конфликтует с содержимым и выглядит как чужеродная
 * наклейка; чёрный круг с белой обводкой одинаково уместен и на светлом, и
 * на тёмном, а внимание притягивает формой, а не цветом.
 *
 * ЧТО ОЗНАЧАЮТ ЦИФРЫ — вопрос, который пришлось решать заново. Раньше в
 * круге стояло голое число, и понять, что это «строк набрано», было
 * невозможно. Теперь число сопровождается подписью «строк» мелким кеглем, а
 * главное — вокруг него идёт КОЛЬЦО ЗАПОЛНЕНИЯ:
 *
 *   кольцо пустое   — только что появился новый текст, читаем дальше;
 *   кольцо растёт   — нового текста всё нет;
 *   кольцо полное + галочка — с этого экрана взято всё, можно листать
 *                             дальше или нажать и закончить.
 *
 * Это и есть ответ на «есть ли индикатор, что всё с экрана скопировалось»:
 * индикатор не может знать про текст, которого ещё не видел, но может
 * честно показать «на видимом участке новых строк больше не появляется».
 */
public class ScanBubbleView extends View {

    public enum State {
        /** Читаем: круг, число, кольцо готовности. */
        READING,
        /** Остановлено: широкая кнопка «Копировать». */
        DONE
    }

    private static final int BG = 0xE6101014;        // почти чёрный, слегка прозрачный
    private static final int FG = 0xFFFFFFFF;
    private static final int RING_DIM = 0x33FFFFFF;
    private static final int RING_FULL = 0xFFFFFFFF;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cap = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);

    private State state = State.READING;
    private int count;
    /** 0..1 — насколько давно не было нового текста. */
    private float readiness;
    private boolean complete;

    public ScanBubbleView(Context c) {
        super(c);
        bg.setColor(BG);
        bg.setStyle(Paint.Style.FILL);

        ringBg.setStyle(Paint.Style.STROKE);
        ringBg.setColor(RING_DIM);
        ringBg.setStrokeWidth(dp(2.5f));

        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(RING_FULL);
        ring.setStrokeWidth(dp(2.5f));
        ring.setStrokeCap(Paint.Cap.ROUND);

        num.setColor(FG);
        num.setTextAlign(Paint.Align.CENTER);
        num.setFakeBoldText(true);

        cap.setColor(0xB3FFFFFF);
        cap.setTextAlign(Paint.Align.CENTER);

        tick.setStyle(Paint.Style.STROKE);
        tick.setColor(FG);
        tick.setStrokeWidth(dp(2.2f));
        tick.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setState(State s) {
        state = s;
        invalidate();
    }

    public void setCount(int c) {
        count = c;
        invalidate();
    }

    /**
     * @param r 0 — только что был новый текст, 1 — давно ничего нового
     * @param done true, когда порог пройден: с видимого участка взято всё
     */
    public void setReadiness(float r, boolean done) {
        readiness = r < 0 ? 0 : (r > 1 ? 1 : r);
        complete = done;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        if (state == State.DONE) {
            drawDone(c, w, h);
        } else {
            drawReading(c, w, h);
        }
    }

    private void drawReading(Canvas c, int w, int h) {
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f - dp(3);

        c.drawCircle(cx, cy, r, bg);

        // кольцо готовности: пустое → полное по мере отсутствия нового текста
        RectF oval = new RectF(cx - r + dp(2), cy - r + dp(2), cx + r - dp(2), cy + r - dp(2));
        c.drawArc(oval, 0, 360, false, ringBg);
        if (readiness > 0) {
            // от «12 часов» по часовой — привычное направление заполнения
            c.drawArc(oval, -90, 360 * readiness, false, ring);
        }

        if (complete) {
            /*
             * Полностью прочитанный участок показываем галочкой ВМЕСТО числа:
             * когда всё взято, важно именно это, а счётчик уже не меняется и
             * внимания не требует.
             */
            drawTick(c, cx, cy, r * 0.5f);
            cap.setTextSize(sp(8.5f));
            c.drawText("всё взято", cx, cy + r * 0.72f, cap);
        } else {
            num.setTextSize(sp(count >= 1000 ? 14 : 17));
            c.drawText(String.valueOf(count), cx, cy + dp(count >= 1000 ? 1.5f : 2f), num);
            cap.setTextSize(sp(8.5f));
            c.drawText(lineWord(count), cx, cy + r * 0.62f, cap);
        }
    }

    /**
     * Правильная форма слова: «1 строка», «2 строки», «5 строк».
     * Мелочь, но «5 строка» в интерфейсе выглядит как недоделка.
     */
    private static String lineWord(int n) {
        int t = n % 100;
        if (t >= 11 && t <= 14) return "строк";
        switch (n % 10) {
            case 1: return "строка";
            case 2:
            case 3:
            case 4: return "строки";
            default: return "строк";
        }
    }

    private void drawTick(Canvas c, float cx, float cy, float size) {
        Path p = new Path();
        p.moveTo(cx - size * 0.55f, cy - size * 0.05f);
        p.lineTo(cx - size * 0.12f, cy + size * 0.42f);
        p.lineTo(cx + size * 0.62f, cy - size * 0.48f);
        c.drawPath(p, tick);
    }

    private void drawDone(Canvas c, int w, int h) {
        RectF box = new RectF(dp(1), dp(1), w - dp(1), h - dp(1));
        float rad = h / 2f;
        c.drawRoundRect(box, rad, rad, bg);

        Paint stroke = new Paint(ringBg);
        stroke.setColor(0x40FFFFFF);
        c.drawRoundRect(box, rad, rad, stroke);

        // Иконка копирования слева — чтобы назначение читалось без текста.
        float ix = h * 0.52f, iy = h / 2f, s = h * 0.19f;
        Paint ic = new Paint(tick);
        ic.setStrokeWidth(dp(1.8f));
        c.drawRoundRect(new RectF(ix - s * 0.15f, iy - s * 0.55f, ix + s * 1.1f, iy + s * 1.0f),
                dp(3), dp(3), ic);
        Path back = new Path();
        back.moveTo(ix + s * 0.55f, iy - s * 0.55f);
        back.lineTo(ix + s * 0.55f, iy - s * 1.0f);
        back.lineTo(ix - s * 0.75f, iy - s * 1.0f);
        back.lineTo(ix - s * 0.75f, iy + s * 0.5f);
        c.drawPath(back, ic);

        num.setTextSize(sp(14));
        num.setTextAlign(Paint.Align.LEFT);
        float tx = h * 0.95f;
        c.drawText("Копировать", tx, h / 2f - dp(2), num);
        cap.setTextSize(sp(9.5f));
        cap.setTextAlign(Paint.Align.LEFT);
        c.drawText(count + " " + lineWord(count) + " · нажмите", tx, h / 2f + dp(11), cap);
        num.setTextAlign(Paint.Align.CENTER);
        cap.setTextAlign(Paint.Align.CENTER);
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
