package com.screentextscan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Минималистичный плавающий круг. Один размер во всех состояниях.
 *
 * СОСТОЯНИЯ:
 *   READING — тёмный круг с числом строк и кольцом готовности.
 *   DONE    — тот же круг, но с иконкой копирования вместо числа.
 *
 * АНИМАЦИИ:
 *   pulseNewText() — при появлении нового текста частицы «всасываются»
 *     в круг со всех сторон. Плавный эффект поглощения.
 *   startLongPressAnim() — при долгом зажатии искры сходятся к центру
 *     с пульсирующим кольцом прогресса.
 *   pop() — круг лопается: расширение → частицы наружу → ripple → затухание.
 *
 * ВЗАИМОДЕЙСТВИЕ (из OverlayService):
 *   тап по DONE        → копировать в буфер + pop + вибрация
 *   долгое зажатие 2.5с → искры → pop + вибрация → убрать с экрана
 */
public class ScanBubbleView extends View {

    public enum State { READING, DONE }

    // --- палитра ---
    private static final int BG       = 0xF0101014;
    private static final int FG       = 0xFFFFFFFF;
    private static final int RING_DIM = 0x22FFFFFF;
    private static final int RING_FG  = 0xFFFFFFFF;

    private static final int[] SPARK_COLORS = {
        0xCCFFFFFF, 0xAA88CCFF, 0xAA88AACC, 0xAAAACCFF, 0xCCDDFFFF
    };

    private final Paint bg     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint num    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // переиспользуемые объекты для draw — без аллокаций в onDraw
    private final Paint tmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();
    private final Path tmpPath = new Path();

    private State state = State.READING;
    private int count;
    private float readiness;
    private boolean complete;

    // --- анимация всасывания при новом тексте ---
    private static final long ABSORB_MS = 700;
    private static final int ABSORB_COUNT = 12;
    private boolean absorbing = false;
    private long absorbStart;
    private final List<AbsorbParticle> absorbParticles = new ArrayList<>();
    private static class AbsorbParticle {
        float angle, startDist, size;
        int color;
    }

    // --- анимация искр при долгом зажатии ---
    private static final int SPARK_COUNT = 28;
    private static final long LONG_PRESS_MS = 2500;
    private final List<Spark> sparks = new ArrayList<>();
    private final Random rnd = new Random();
    private long pressStart;
    private boolean pressing;
    private float pressProgress;
    private float pulse;

    // --- анимация лопающегося пузыря ---
    private static final long POP_MS = 450;
    private boolean popping = false;
    private long popStart;
    private float popProgress;
    private final List<BurstParticle> burst = new ArrayList<>();
    private Runnable onPopDone;

    private static class BurstParticle {
        float angle, dist, size;
        int color;
        float speed;
    }

    private static class Spark {
        float angle, startDist, size;
        int color;
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

    // === ЭФФЕКТ ВСАСЫВАНИЯ ===

    /** Вызывается при появлении нового текста. Частицы «всасываются» в круг. */
    public void pulseNewText() {
        absorbing = true;
        absorbStart = System.currentTimeMillis();
        absorbParticles.clear();
        for (int i = 0; i < ABSORB_COUNT; i++) {
            AbsorbParticle p = new AbsorbParticle();
            p.angle = (float) (i * (2 * Math.PI / ABSORB_COUNT)) + rnd.nextFloat() * 0.5f;
            p.startDist = dp(50 + rnd.nextInt(40));
            p.size = dp(1.5f + rnd.nextFloat() * 2f);
            p.color = SPARK_COLORS[rnd.nextInt(SPARK_COLORS.length)];
            absorbParticles.add(p);
        }
        invalidate();
    }

    // === ДОЛГОЕ ЗАЖАТИЕ ===

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
            sparks.add(s);
        }
        invalidate();
    }

    public void cancelLongPressAnim() {
        pressing = false;
        sparks.clear();
        pressProgress = 0;
        invalidate();
    }

    // === POP ===

    public void pop(Runnable done) {
        onPopDone = done;
        popping = true;
        popStart = System.currentTimeMillis();
        popProgress = 0;
        burst.clear();
        for (int i = 0; i < 24; i++) {
            BurstParticle p = new BurstParticle();
            p.angle = (float) (i * (2 * Math.PI / 24)) + rnd.nextFloat() * 0.35f;
            p.dist = 0;
            p.size = dp(2 + rnd.nextFloat() * 4);
            p.speed = 0.8f + rnd.nextFloat() * 0.6f;
            p.color = SPARK_COLORS[rnd.nextInt(SPARK_COLORS.length)];
            burst.add(p);
        }
        invalidate();
    }

    // === ONDRAW ===

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f - dp(3);

        // pop имеет приоритет — рисуем только его
        if (popping) {
            long elapsed = System.currentTimeMillis() - popStart;
            popProgress = Math.min(1f, (float) elapsed / POP_MS);
            drawPop(c, w, h, cx, cy, r);
            if (popProgress >= 1f) {
                popping = false;
                // Используем post() — нельзя removeView внутри onDraw
                final Runnable cb = onPopDone;
                onPopDone = null;
                if (cb != null) post(cb);
            } else {
                invalidate();
            }
            return;
        }

        // основной круг
        if (state == State.DONE) {
            drawDone(c, w, h, cx, cy, r);
        } else {
            drawReading(c, w, h, cx, cy, r);
        }

        // обновление прогресса зажатия
        boolean needInvalidate = false;
        if (pressing) {
            long elapsed = System.currentTimeMillis() - pressStart;
            pressProgress = Math.min(1f, (float) elapsed / LONG_PRESS_MS);
            pulse = (float) (0.5 + 0.5 * Math.sin(elapsed * 0.012));
            drawSparks(c, w, h, cx, cy);
            if (pressProgress < 1f) drawPressRing(c, w, h, cx, cy);
            needInvalidate = true;
        }

        // всасывание
        if (absorbing) {
            long elapsed = System.currentTimeMillis() - absorbStart;
            float ap = Math.min(1f, (float) elapsed / ABSORB_MS);
            drawAbsorb(c, w, h, cx, cy, r, ap);
            if (ap >= 1f) {
                absorbing = false;
                absorbParticles.clear();
            }
            needInvalidate = true;
        }

        if (needInvalidate) invalidate();
    }

    // === РИСОВАНИЕ ===

    private void drawReading(Canvas c, int w, int h, float cx, float cy, float r) {
        c.drawCircle(cx, cy, r, bg);

        tmpRect.set(cx - r + dp(2), cy - r + dp(2), cx + r - dp(2), cy + r - dp(2));
        c.drawArc(tmpRect, 0, 360, false, ringBg);
        if (readiness > 0) {
            c.drawArc(tmpRect, -90, 360 * readiness, false, ring);
        }

        if (complete) {
            drawTick(c, cx, cy, r * 0.45f);
        } else {
            num.setTextSize(sp(count >= 100 ? 15 : 18));
            c.drawText(String.valueOf(count), cx, cy + dp(2), num);
        }
    }

    private void drawDone(Canvas c, int w, int h, float cx, float cy, float r) {
        c.drawCircle(cx, cy, r, bg);

        tmpPaint.set(ringBg);
        tmpPaint.setColor(0x28FFFFFF);
        c.drawCircle(cx, cy, r, tmpPaint);

        float s = r * 0.42f;
        tmpPaint.set(tick);
        tmpPaint.setStrokeWidth(dp(1.6f));
        tmpPaint.setStyle(Paint.Style.STROKE);
        tmpRect.set(cx - s * 0.15f, cy - s * 0.85f, cx + s * 1.15f, cy + s * 0.45f);
        c.drawRoundRect(tmpRect, dp(3), dp(3), tmpPaint);
        tmpRect.set(cx - s * 0.85f, cy - s * 0.45f, cx + s * 0.45f, cy + s * 0.85f);
        c.drawRoundRect(tmpRect, dp(3), dp(3), tmpPaint);

        tmpPaint.set(num);
        tmpPaint.setTextSize(sp(9));
        tmpPaint.setFakeBoldText(false);
        tmpPaint.setColor(0xB3FFFFFF);
        c.drawText(String.valueOf(count), cx, cy + r * 0.82f, tmpPaint);
    }

    /** Эффект всасывания: частицы плавно стягиваются к центру круга. */
    private void drawAbsorb(Canvas c, int w, int h, float cx, float cy, float r, float p) {
        for (AbsorbParticle ap : absorbParticles) {
            // частица движется от startDist к r (границе круга)
            float dist = ap.startDist * (1f - p) + r * 0.3f * (1f - p);
            // лёгкая задержка появления
            float appear = Math.min(1f, p * 2f);
            if (appear <= 0) continue;
            // затухание по мере приближения к кругу
            float fade = (1f - p) * appear;
            int alpha = (int) (Color.alpha(ap.color) * fade);
            if (alpha <= 0) continue;

            float x = cx + (float) Math.cos(ap.angle) * dist;
            float y = cy + (float) Math.sin(ap.angle) * dist;
            float sz = ap.size * appear * (0.5f + 0.5f * (1f - p));

            sparkPaint.setColor(ap.color);
            sparkPaint.setAlpha(alpha);
            c.drawCircle(x, y, sz, sparkPaint);

            // тонкий след за частицей
            float trailDist = dist + dp(6);
            float tx = cx + (float) Math.cos(ap.angle) * trailDist;
            float ty = cy + (float) Math.sin(ap.angle) * trailDist;
            sparkPaint.setAlpha((int) (30 * fade));
            c.drawCircle(tx, ty, sz * 0.3f, sparkPaint);
        }
    }

    private void drawSparks(Canvas c, int w, int h, float cx, float cy) {
        for (Spark s : sparks) {
            float dist = s.startDist * (1f - pressProgress);
            float appear = Math.min(1f, pressProgress * 1.5f + s.angle * 0.05f);
            if (appear <= 0) continue;
            float x = cx + (float) Math.cos(s.angle) * dist;
            float y = cy + (float) Math.sin(s.angle) * dist;
            float sz = s.size * appear * (1f - pressProgress * 0.5f);
            sparkPaint.setColor(s.color);
            sparkPaint.setAlpha((int) (Color.alpha(s.color) * appear * (1f - pressProgress * 0.3f)));
            c.drawCircle(x, y, sz, sparkPaint);
            if (pressProgress > 0.2f) {
                float trail = dist + dp(8);
                sparkPaint.setAlpha((int) (40 * appear * (1f - pressProgress)));
                c.drawCircle(cx + (float) Math.cos(s.angle) * trail,
                              cy + (float) Math.sin(s.angle) * trail,
                              sz * 0.4f, sparkPaint);
            }
        }
    }

    private void drawPressRing(Canvas c, int w, int h, float cx, float cy) {
        float r = Math.max(w, h) / 2f + dp(6 + pulse * 3);
        tmpRect.set(cx - r, cy - r, cx + r, cy + r);
        tmpPaint.set(ring);
        tmpPaint.setStrokeWidth(dp(2.5f + pulse));
        tmpPaint.setColor(0x66AACCFF);
        c.drawArc(tmpRect, -90, 360 * pressProgress, false, tmpPaint);
    }

    /** Лопающийся пузырь — красивый, многослойный эффект. */
    private void drawPop(Canvas c, int w, int h, float cx, float cy, float r) {
        float p = popProgress;

        float scale;
        if (p < 0.2f) {
            scale = 1f + p * 1.0f;
        } else if (p < 0.45f) {
            float t = (p - 0.2f) / 0.25f;
            scale = 1.2f - t * 1.2f;
        } else {
            scale = 0;
        }
        int alpha = (int) (255 * (1f - p * p));

        if (scale > 0 && alpha > 0) {
            c.save();
            c.scale(scale, scale, cx, cy);
            bg.setAlpha(alpha);
            c.drawCircle(cx, cy, r, bg);
            if (alpha > 60) {
                ring.setAlpha(alpha);
                c.drawCircle(cx, cy, r - dp(2), ring);
                ring.setAlpha(255);
            }
            bg.setAlpha(255);
            c.restore();
        }

        for (int i = 0; i < 2; i++) {
            float ringP = p - i * 0.15f;
            if (ringP < 0 || ringP > 0.7f) continue;
            float rr = r * (1f + ringP * (2f + i));
            float ra = (1f - ringP / 0.7f) * (0.5f - i * 0.2f);
            if (ra <= 0) continue;
            tmpPaint.setStyle(Paint.Style.STROKE);
            tmpPaint.setStrokeWidth(dp(2 - i * 0.5f));
            tmpPaint.setColor(0xDDFFFFFF);
            tmpPaint.setAlpha((int) (255 * ra));
            c.drawCircle(cx, cy, rr, tmpPaint);
        }

        for (BurstParticle bp : burst) {
            float t = p * bp.speed;
            bp.dist = r * 0.5f + t * r * 2.5f;
            float x = cx + (float) Math.cos(bp.angle) * bp.dist;
            float y = cy + (float) Math.sin(bp.angle) * bp.dist;
            float sz = bp.size * (1f - t * 0.5f);
            int a = (int) (Color.alpha(bp.color) * Math.max(0, 1f - t));
            if (a <= 0 || sz <= 0) continue;
            sparkPaint.setColor(bp.color);
            sparkPaint.setAlpha(a);
            c.drawCircle(x, y, sz, sparkPaint);
            float trailD = bp.dist - dp(10);
            if (trailD > 0) {
                sparkPaint.setAlpha((int) (a * 0.3f));
                c.drawCircle(cx + (float) Math.cos(bp.angle) * trailD,
                              cy + (float) Math.sin(bp.angle) * trailD,
                              sz * 0.4f, sparkPaint);
            }
        }

        if (p < 0.35f) {
            float flashA = (1f - p / 0.35f) * 0.5f;
            tmpPaint.setStyle(Paint.Style.FILL);
            tmpPaint.setColor(0xFFFFFFFF);
            tmpPaint.setAlpha((int) (255 * flashA));
            c.drawCircle(cx, cy, r * (0.3f + p * 0.4f), tmpPaint);
        }
    }

    private void drawTick(Canvas c, float cx, float cy, float size) {
        tmpPath.reset();
        tmpPath.moveTo(cx - size * 0.55f, cy - size * 0.05f);
        tmpPath.lineTo(cx - size * 0.12f, cy + size * 0.42f);
        tmpPath.lineTo(cx + size * 0.62f, cy - size * 0.48f);
        c.drawPath(tmpPath, tick);
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
