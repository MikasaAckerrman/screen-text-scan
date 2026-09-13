package com.screentextscan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Минималистичный плавающий шарик с анимацией искр при долгом зажатии.
 *
 * СОСТОЯНИЯ:
 *   READING — маленький тёмный круг с числом строк и кольцом готовности.
 *   DONE    — аккуратная пилюля с иконкой копирования.
 *
 * ВЗАИМОДЕЙСТВИЕ (управляется из OverlayService):
 *   тап по DONE         → копировать в буфер, шарик исчезает
 *   долгое зажатие 2.5с  → анимация искр → открыть экран правки
 *
 * ИСКРЫ: при зажатии частицы сходятся со всех сторон к центру.
 * Не огонь — мягкие белый/голубой/сиреневый. Сопровождаются лёгким
 * пульсирующим кольцом прогресса.
 */
public class ScanBubbleView extends View {

    public enum State { READING, DONE }

    // --- палитра: тёмный минимализм + холодные искры ---
    private static final int BG       = 0xF0101014;
    private static final int FG       = 0xFFFFFFFF;
    private static final int RING_DIM = 0x22FFFFFF;
    private static final int RING_FG  = 0xFFFFFFFF;
    private static final int SUBTLE   = 0x99FFFFFF;

    // цвета искр — не огонь, холодный спектр
    private static final int[] SPARK_COLORS = {
        0xCCFFFFFF, 0xAA88CCFF, 0xAA88AACC, 0xAAAACCFF, 0xCCDDFFFF
    };

    private final Paint bg     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint num    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private State state = State.READING;
    private int count;
    private float readiness;
    private boolean complete;

    // --- анимация искр при долгом зажатии ---
    private static final int SPARK_COUNT = 28;
    private static final long LONG_PRESS_MS = 2500;
    private final List<Spark> sparks = new ArrayList<>();
    private final Random rnd = new Random();
    private long pressStart;
    private boolean pressing;
    private float pressProgress; // 0..1 за 2.5с
    private float pulse;          // лёгкая пульсация

    // --- анимация лопающегося пузыря ---
    private static final long POP_MS = 380;
    private boolean popping = false;
    private long popStart;
    private float popProgress; // 0..1
    private final List<BurstParticle> burst = new ArrayList<>();
    private Runnable onPopDone;

    private static class BurstParticle {
        float angle, dist, size;
        int color;
    }

    private static class Spark {
        float angle;    // направление от центра
        float startDist; // начальная дистанция
        float size;
        int color;
        float phase;     // 0..1 — насколько искра приблизилась
    }

    public ScanBubbleView(Context c) {
        super(c);
        bg.setColor(BG);
        bg.setStyle(Paint.Style.FILL);

        ringBg.setStyle(Paint.Style.STROKE);
        ringBg.setColor(RING_DIM);
        ringBg.setStrokeWidth(dp(2));

        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(RING_FG);
        ring.setStrokeWidth(dp(2.2f));
        ring.setStrokeCap(Paint.Cap.ROUND);

        num.setColor(FG);
        num.setTextAlign(Paint.Align.CENTER);
        num.setFakeBoldText(true);

        tick.setStyle(Paint.Style.STROKE);
        tick.setColor(FG);
        tick.setStrokeWidth(dp(2));
        tick.setStrokeCap(Paint.Cap.ROUND);

        sparkPaint.setStyle(Paint.Style.FILL);
    }

    public void setState(State s) { state = s; invalidate(); }
    public void setCount(int c) { count = c; invalidate(); }

    public void setReadiness(float r, boolean done) {
        readiness = r < 0 ? 0 : (r > 1 ? 1 : r);
        complete = done;
        invalidate();
    }

    /** Начать анимацию долгого зажатия. */
    public void startLongPressAnim() {
        pressing = true;
        pressStart = System.currentTimeMillis();
        pressProgress = 0;
        sparks.clear();
        for (int i = 0; i < SPARK_COUNT; i++) {
            Spark s = new Spark();
            s.angle = (float) (i * (2 * Math.PI / SPARK_COUNT)) + rnd.nextFloat() * 0.3f;
            s.startDist = dp(80 + rnd.nextInt(60));
            s.size = dp(1.5f + rnd.nextFloat() * 2.5f);
            s.color = SPARK_COLORS[rnd.nextInt(SPARK_COLORS.length)];
            s.phase = 0;
            sparks.add(s);
        }
        invalidate();
    }

    /** Отменить анимацию (палец отпустили раньше или сдвинули). */
    public void cancelLongPressAnim() {
        pressing = false;
        sparks.clear();
        pressProgress = 0;
        invalidate();
    }

    public boolean isLongPressReached() {
        return pressing && System.currentTimeMillis() - pressStart >= LONG_PRESS_MS;
    }

    /**
     * Анимация лопающегося пузыря: лёгкое расширение → burst-частицы
     * разлетаются наружу → всё затухает. После завершения вызывает callback.
     */
    public void pop(Runnable done) {
        onPopDone = done;
        popping = true;
        popStart = System.currentTimeMillis();
        popProgress = 0;
        // частицы разлетаются наружу от центра
        burst.clear();
        for (int i = 0; i < 18; i++) {
            BurstParticle p = new BurstParticle();
            p.angle = (float) (i * (2 * Math.PI / 18)) + rnd.nextFloat() * 0.4f;
            p.dist = 0;
            p.size = dp(2 + rnd.nextFloat() * 3);
            p.color = SPARK_COLORS[rnd.nextInt(SPARK_COLORS.length)];
            burst.add(p);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        // обновить прогресс зажатия
        if (pressing) {
            long elapsed = System.currentTimeMillis() - pressStart;
            pressProgress = Math.min(1f, (float) elapsed / LONG_PRESS_MS);
            pulse = (float) (0.5 + 0.5 * Math.sin(elapsed * 0.012));
        }

        // обновить прогресс pop-анимации
        if (popping) {
            long elapsed = System.currentTimeMillis() - popStart;
            popProgress = Math.min(1f, (float) elapsed / POP_MS);
            if (popProgress >= 1f) {
                popping = false;
                if (onPopDone != null) {
                    Runnable r = onPopDone;
                    onPopDone = null;
                    r.run();
                }
                return; // больше не рисуем
            }
        }

        if (popping) {
            drawPop(c, w, h);
            invalidate();
            return;
        }

        if (state == State.DONE) {
            drawDone(c, w, h);
        } else {
            drawReading(c, w, h);
        }

        // искры рисуем поверх в любом состоянии при зажатии
        if (pressing && pressProgress > 0) {
            drawSparks(c, w, h);
            if (pressProgress < 1f) drawPressRing(c, w, h);
            invalidate();
        }
    }

    /** Лопающийся пузырь: расширение → ripple → burst-частицы → затухание. */
    private void drawPop(Canvas c, int w, int h) {
        float cx = w / 2f, cy = h / 2f;
        float p = popProgress;

        // 1) расширение (0..0.25) → сжатие с overshoot (0.25..0.5) → исчезновение
        float scale;
        if (p < 0.25f) {
            scale = 1f + p * 0.8f;         // 1.0 → 1.2
        } else if (p < 0.45f) {
            float t = (p - 0.25f) / 0.2f;  // 0..1
            scale = 1.2f - t * 1.2f;       // 1.2 → 0
        } else {
            scale = 0;
        }
        int alpha = (int) (255 * (1f - p * p));

        // 2) рисуем пузырь с scale и затуханием
        if (scale > 0 && alpha > 0) {
            c.save();
            c.scale(scale, scale, cx, cy);
            bg.setAlpha(alpha);
            if (state == State.DONE) {
                RectF box = new RectF(dp(1), dp(1), w - dp(1), h - dp(1));
                c.drawRoundRect(box, h / 2f, h / 2f, bg);
            } else {
                float r = Math.min(w, h) / 2f - dp(3);
                c.drawCircle(cx, cy, r, bg);
            }
            bg.setAlpha(255);
            c.restore();
        }

        // 3) ripple — расходящееся кольцо
        if (p < 0.6f) {
            float rippleR = Math.max(w, h) / 2f * (0.5f + p * 1.5f);
            float rippleA = (1f - p / 0.6f) * 0.4f;
            Paint rp = new Paint(Paint.ANTI_ALIAS_FLAG);
            rp.setStyle(Paint.Style.STROKE);
            rp.setStrokeWidth(dp(2));
            rp.setColor(0xCCFFFFFF);
            rp.setAlpha((int) (255 * rippleA));
            c.drawCircle(cx, cy, rippleR, rp);
        }

        // 4) burst-частицы разлетаются наружу
        for (BurstParticle bp : burst) {
            float t = p;
            bp.dist = dp(30 + t * 80);
            float x = cx + (float) Math.cos(bp.angle) * bp.dist;
            float y = cy + (float) Math.sin(bp.angle) * bp.dist;
            float sz = bp.size * (1f - t * 0.6f);
            int a = (int) (Color.alpha(bp.color) * (1f - t));
            if (a <= 0 || sz <= 0) continue;
            sparkPaint.setColor(bp.color);
            sparkPaint.setAlpha(a);
            c.drawCircle(x, y, sz, sparkPaint);
        }
    }

    private void drawReading(Canvas c, int w, int h) {
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f - dp(3);

        c.drawCircle(cx, cy, r, bg);

        RectF oval = new RectF(cx - r + dp(2), cy - r + dp(2),
                               cx + r - dp(2), cy + r - dp(2));
        c.drawArc(oval, 0, 360, false, ringBg);
        if (readiness > 0) {
            c.drawArc(oval, -90, 360 * readiness, false, ring);
        }

        if (complete) {
            drawTick(c, cx, cy, r * 0.45f);
        } else {
            num.setTextSize(sp(count >= 100 ? 15 : 18));
            c.drawText(String.valueOf(count), cx, cy + dp(2), num);
        }
    }

    private void drawDone(Canvas c, int w, int h) {
        // минималистичная пилюля со скруглением
        RectF box = new RectF(dp(1), dp(1), w - dp(1), h - dp(1));
        float rad = h / 2f;
        c.drawRoundRect(box, rad, rad, bg);

        // тонкая обводка
        Paint stroke = new Paint(ringBg);
        stroke.setColor(0x30FFFFFF);
        c.drawRoundRect(box, rad, rad, stroke);

        // иконка копирования — две накладывающиеся скруглённые рамки
        float ix = h * 0.5f, iy = h / 2f, s = h * 0.22f;
        Paint ic = new Paint(tick);
        ic.setStrokeWidth(dp(1.6f));
        ic.setStyle(Paint.Style.STROKE);
        // задняя рамка
        RectF back = new RectF(ix - s * 0.1f, iy - s * 0.8f, ix + s * 1.2f, iy + s * 0.5f);
        c.drawRoundRect(back, dp(3), dp(3), ic);
        // передняя рамка
        RectF front = new RectF(ix - s * 0.8f, iy - s * 0.5f, ix + s * 0.5f, iy + s * 0.8f);
        c.drawRoundRect(front, dp(3), dp(3), ic);

        // число строк справа от иконки
        num.setTextSize(sp(14));
        num.setTextAlign(Paint.Align.LEFT);
        c.drawText(String.valueOf(count), h * 0.85f, h / 2f + dp(5), num);
        num.setTextAlign(Paint.Align.CENTER);
    }

    /** Кольцо прогресса долгого зажатия вокруг шарика. */
    private void drawPressRing(Canvas c, int w, int h) {
        float cx = w / 2f, cy = h / 2f;
        float r = Math.max(w, h) / 2f + dp(6 + pulse * 3);
        RectF oval = new RectF(cx - r, cy - r, cx + r, cy + r);
        Paint p = new Paint(ring);
        p.setStrokeWidth(dp(2.5f + pulse));
        p.setColor(0x66AACCFF);
        c.drawArc(oval, -90, 360 * pressProgress, false, p);
    }

    /** Идущие к центру искры. */
    private void drawSparks(Canvas c, int w, int h) {
        float cx = w / 2f, cy = h / 2f;
        for (Spark s : sparks) {
            // каждая искра движется от startDist к 0 (центру)
            float dist = s.startDist * (1f - pressProgress);
            // лёгкая волна — искры появляются не все сразу
            float appear = Math.min(1f, pressProgress * 1.5f + s.angle * 0.05f);
            if (appear <= 0) continue;
            float x = cx + (float) Math.cos(s.angle) * dist;
            float y = cy + (float) Math.sin(s.angle) * dist;
            float sz = s.size * appear * (1f - pressProgress * 0.5f);

            sparkPaint.setColor(s.color);
            sparkPaint.setAlpha((int) (Color.alpha(s.color) * appear * (1f - pressProgress * 0.3f)));
            c.drawCircle(x, y, sz, sparkPaint);

            // лёгкий след
            if (pressProgress > 0.2f) {
                float trail = dist + dp(8);
                float tx = cx + (float) Math.cos(s.angle) * trail;
                float ty = cy + (float) Math.sin(s.angle) * trail;
                sparkPaint.setAlpha((int) (40 * appear * (1f - pressProgress)));
                c.drawCircle(tx, ty, sz * 0.4f, sparkPaint);
            }
        }
    }

    private void drawTick(Canvas c, float cx, float cy, float size) {
        Path p = new Path();
        p.moveTo(cx - size * 0.55f, cy - size * 0.05f);
        p.lineTo(cx - size * 0.12f, cy + size * 0.42f);
        p.lineTo(cx + size * 0.62f, cy - size * 0.48f);
        c.drawPath(p, tick);
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
