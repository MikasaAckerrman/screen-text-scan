package com.screentextscan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.TypedValue;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Плавающий круг с «вкусной» тёмной текстурой.
 *
 * View = 200dp (чтобы искры и burst помещались полностью).
 * Рисуемый круг = 62dp — по центру View.
 *
 * ВЗАИМОДЕЙСТВИЕ (из OverlayService):
 *   тап        → копировать накопленный текст + flash + продолжить чтение
 *   зажатие 2.5с → искры → pop → убрать с экрана
 *
 * АНИМАЦИИ:
 *   pulseNewText() — всасывание частиц при новом тексте
 *   startLongPressAnim() — искры сходятся к центру при зажатии
 *   flashCopy() — вспышка-волна при копировании
 *   pop() — лопающийся пузырь
 *   press — лёгкое сжатие при нажатии (0.95) + glow
 */
public class ScanBubbleView extends View {

    // --- палитра ---
    private static final int CIRCLE_BG = 0xF0101014;
    private static final int FG        = 0xFFFFFFFF;
    private static final int RING_DIM  = 0x22FFFFFF;
    private static final int RING_FG   = 0xFFFFFFFF;

    private static final int[] SPARK_COLORS = {
        0xCCFFFFFF, 0xAA88CCFF, 0xAA88AACC, 0xAAAACCFF, 0xCCDDFFFF
    };

    // радиус рисуемого круга (View больше — для анимаций)
    private static final float CIRCLE_DP = 31f; // 62dp diameter

    private final Paint bg        = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringBg    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint num       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tmpPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect   = new RectF();
    private final Path  tmpPath   = new Path();
    private RadialGradient texture;

    private int count;
    private float readiness;
    private boolean complete;

    // --- press ---
    private float pressScale = 1.0f;
    private float glowAlpha = 0f;

    // --- всасывание ---
    private static final long ABSORB_MS = 700;
    private static final int ABSORB_COUNT = 14;
    private boolean absorbing = false;
    private long absorbStart;
    private final List<float[]> absorbParticles = new ArrayList<>();
    private final Random rnd = new Random();

    // --- искры при зажатии ---
    private static final int SPARK_COUNT = 32;
    private static final long LONG_PRESS_MS = 2500;
    private final List<float[]> sparks = new ArrayList<>();
    private long pressStart;
    private boolean pressing;
    private float pressProgress;
    private float pulse;

    // --- flash при копировании ---
    private static final long FLASH_MS = 600;
    private boolean flashing = false;
    private long flashStart;

    // --- pop ---
    private static final long POP_MS = 500;
    private boolean popping = false;
    private long popStart;
    private float popProgress;
    private final List<float[]> burst = new ArrayList<>();
    private Runnable onPopDone;

    public ScanBubbleView(Context c) {
        super(c);
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
        glowPaint.setStyle(Paint.Style.FILL);
    }

    public void setCount(int c) { count = c; invalidate(); }
    public void setReadiness(float r, boolean done) {
        readiness = r < 0 ? 0 : (r > 1 ? 1 : r);
        complete = done;
        invalidate();
    }

    // === PRESS ===

    /** Вызывается из OverlayService при ACTION_DOWN. */
    public void onPress() {
        pressScale = 0.93f;
        glowAlpha = 1.0f;
        invalidate();
    }

    /** Вызывается из OverlayService при ACTION_UP/CANCEL. */
    public void onRelease() {
        pressScale = 1.0f;
        glowAlpha = 0f;
        invalidate();
    }

    // === ВСАСЫВАНИЕ ===

    public void pulseNewText() {
        absorbing = true;
        absorbStart = System.currentTimeMillis();
        absorbParticles.clear();
        for (int i = 0; i < ABSORB_COUNT; i++) {
            float angle = (float) (i * (2 * Math.PI / ABSORB_COUNT)) + rnd.nextFloat() * 0.5f;
            float dist = dp(70 + rnd.nextInt(50));
            float size = dp(1.5f + rnd.nextFloat() * 2.5f);
            int color = SPARK_COLORS[rnd.nextInt(SPARK_COLORS.length)];
            absorbParticles.add(new float[]{angle, dist, size, color});
        }
        invalidate();
    }

    // === ИСКРЫ ЗАЖАТИЯ ===

    public void startLongPressAnim() {
        pressing = true;
        pressStart = System.currentTimeMillis();
        pressProgress = 0;
        sparks.clear();
        for (int i = 0; i < SPARK_COUNT; i++) {
            float angle = (float) (i * (2 * Math.PI / SPARK_COUNT)) + rnd.nextFloat() * 0.3f;
            float dist = dp(80 + rnd.nextInt(60));
            float size = dp(1.5f + rnd.nextFloat() * 2.5f);
            int color = SPARK_COLORS[rnd.nextInt(SPARK_COLORS.length)];
            sparks.add(new float[]{angle, dist, size, color});
        }
        invalidate();
    }

    public void cancelLongPressAnim() {
        pressing = false;
        sparks.clear();
        pressProgress = 0;
        invalidate();
    }

    // === FLASH КОПИРОВАНИЯ ===

    /** Краткая волна-вспышка при копировании — bubble остаётся. */
    public void flashCopy() {
        flashing = true;
        flashStart = System.currentTimeMillis();
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
            float angle = (float) (i * (2 * Math.PI / 24)) + rnd.nextFloat() * 0.35f;
            float size = dp(2 + rnd.nextFloat() * 4);
            float speed = 0.8f + rnd.nextFloat() * 0.6f;
            int color = SPARK_COLORS[rnd.nextInt(SPARK_COLORS.length)];
            burst.add(new float[]{angle, 0, size, color, speed});
        }
        invalidate();
    }

    // === ONDRAW ===

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float cx = w / 2f, cy = h / 2f;
        float r = dp(CIRCLE_DP) * pressScale;
        boolean needInvalidate = false;

        // POP имеет приоритет
        if (popping) {
            long elapsed = System.currentTimeMillis() - popStart;
            popProgress = Math.min(1f, (float) elapsed / POP_MS);
            drawPop(c, w, h, cx, cy, r);
            if (popProgress >= 1f) {
                popping = false;
                final Runnable cb = onPopDone;
                onPopDone = null;
                if (cb != null) post(cb);
            } else {
                invalidate();
            }
            return;
        }

        // glow при нажатии
        if (glowAlpha > 0.01f) {
            tmpPaint.set(glowPaint);
            tmpPaint.setColor(0x4488CCFF);
            tmpPaint.setAlpha((int) (80 * glowAlpha));
            c.drawCircle(cx, cy, r + dp(8), tmpPaint);
            needInvalidate = true;
            glowAlpha *= 0.92f; // затухание
        }

        drawCircle(c, cx, cy, r);

        // flash при копировании
        if (flashing) {
            long elapsed = System.currentTimeMillis() - flashStart;
            float fp = Math.min(1f, (float) elapsed / FLASH_MS);
            drawFlash(c, cx, cy, r, fp);
            if (fp >= 1f) flashing = false;
            needInvalidate = true;
        }

        // искры при зажатии
        if (pressing) {
            long elapsed = System.currentTimeMillis() - pressStart;
            pressProgress = Math.min(1f, (float) elapsed / LONG_PRESS_MS);
            pulse = (float) (0.5 + 0.5 * Math.sin(elapsed * 0.012));
            drawSparks(c, cx, cy);
            if (pressProgress < 1f) drawPressRing(c, cx, cy, r);
            needInvalidate = true;
        }

        // всасывание
        if (absorbing) {
            long elapsed = System.currentTimeMillis() - absorbStart;
            float ap = Math.min(1f, (float) elapsed / ABSORB_MS);
            drawAbsorb(c, cx, cy, r, ap);
            if (ap >= 1f) { absorbing = false; absorbParticles.clear(); }
            needInvalidate = true;
        }

        if (needInvalidate) invalidate();
    }

    /** «Вкусная» тёмная текстура: radial gradient с голубым бликом. */
    private void drawCircle(Canvas c, float cx, float cy, float r) {
        if (texture == null) {
            // лёгкий голубой блик сверху-слева, как капсула порошка
            texture = new RadialGradient(
                -r * 0.3f, -r * 0.4f, r * 2.2f,
                new int[]{0xFF1C2A38, 0xF0141820, 0xF0080A10},
                new float[]{0f, 0.4f, 1f},
                Shader.TileMode.CLAMP);
        }
        bg.setShader(texture);
        c.drawCircle(cx, cy, r, bg);
        bg.setShader(null);

        // тонкая внутренняя обводка для глубины
        tmpPaint.set(ringBg);
        tmpPaint.setColor(0x18FFFFFF);
        c.drawCircle(cx, cy, r - dp(1), tmpPaint);

        // кольцо готовности
        tmpRect.set(cx - r + dp(2), cy - r + dp(2), cx + r - dp(2), cy + r - dp(2));
        c.drawArc(tmpRect, 0, 360, false, ringBg);
        if (readiness > 0) {
            c.drawArc(tmpRect, -90, 360 * readiness, false, ring);
        }

        // цифра или галочка — ровно по центру через ascent/descent
        if (complete) {
            drawTick(c, cx, cy, r * 0.42f);
        } else {
            num.setTextSize(sp(count >= 100 ? 15 : 18));
            num.getTextBounds("0", 0, 1, tmpRect);
            float textH = num.ascent() + num.descent();
            c.drawText(String.valueOf(count), cx, cy - textH / 2, num);
        }
    }

    /** Вспышка-волна при копировании: расходящееся кольцо + частицы. */
    private void drawFlash(Canvas c, float cx, float cy, float r, float p) {
        // кольцо
        float fr = r * (1f + p * 1.5f);
        float fa = (1f - p) * 0.7f;
        tmpPaint.setStyle(Paint.Style.STROKE);
        tmpPaint.setStrokeWidth(dp(2.5f * (1f - p)));
        tmpPaint.setColor(0xDDFFFFFF);
        tmpPaint.setAlpha((int) (255 * fa));
        c.drawCircle(cx, cy, fr, tmpPaint);

        // лёгкие частицы наружу
        for (int i = 0; i < 8; i++) {
            float a = (float) (i * Math.PI / 4);
            float d = r + p * r * 1.2f;
            float x = cx + (float) Math.cos(a) * d;
            float y = cy + (float) Math.sin(a) * d;
            float sz = dp(2) * (1f - p);
            if (sz <= 0) continue;
            sparkPaint.setColor(0xCCFFFFFF);
            sparkPaint.setAlpha((int) (200 * (1f - p)));
            c.drawCircle(x, y, sz, sparkPaint);
        }
    }

    private void drawSparks(Canvas c, float cx, float cy) {
        for (float[] s : sparks) {
            float angle = s[0], startDist = s[1], size = s[2];
            int color = (int) s[3];
            float dist = startDist * (1f - pressProgress);
            float appear = Math.min(1f, pressProgress * 1.5f + angle * 0.05f);
            if (appear <= 0) continue;
            float x = cx + (float) Math.cos(angle) * dist;
            float y = cy + (float) Math.sin(angle) * dist;
            float sz = size * appear * (1f - pressProgress * 0.5f);
            sparkPaint.setColor(color);
            sparkPaint.setAlpha((int) (Color.alpha(color) * appear * (1f - pressProgress * 0.3f)));
            c.drawCircle(x, y, sz, sparkPaint);
            if (pressProgress > 0.2f) {
                float trail = dist + dp(8);
                sparkPaint.setAlpha((int) (40 * appear * (1f - pressProgress)));
                c.drawCircle(cx + (float) Math.cos(angle) * trail,
                              cy + (float) Math.sin(angle) * trail,
                              sz * 0.4f, sparkPaint);
            }
        }
    }

    private void drawPressRing(Canvas c, float cx, float cy, float r) {
        float rr = r + dp(6 + pulse * 3);
        tmpRect.set(cx - rr, cy - rr, cx + rr, cy + rr);
        tmpPaint.set(ring);
        tmpPaint.setStrokeWidth(dp(2.5f + pulse));
        tmpPaint.setColor(0x66AACCFF);
        c.drawArc(tmpRect, -90, 360 * pressProgress, false, tmpPaint);
    }

    private void drawAbsorb(Canvas c, float cx, float cy, float r, float p) {
        for (float[] ap : absorbParticles) {
            float angle = ap[0], startDist = ap[1], size = ap[2];
            int color = (int) ap[3];
            float dist = startDist * (1f - p) + r * 0.3f * (1f - p);
            float appear = Math.min(1f, p * 2f);
            if (appear <= 0) continue;
            float fade = (1f - p) * appear;
            int alpha = (int) (Color.alpha(color) * fade);
            if (alpha <= 0) continue;
            float x = cx + (float) Math.cos(angle) * dist;
            float y = cy + (float) Math.sin(angle) * dist;
            float sz = size * appear * (0.5f + 0.5f * (1f - p));
            sparkPaint.setColor(color);
            sparkPaint.setAlpha(alpha);
            c.drawCircle(x, y, sz, sparkPaint);
            float trailDist = dist + dp(6);
            sparkPaint.setAlpha((int) (30 * fade));
            c.drawCircle(cx + (float) Math.cos(angle) * trailDist,
                          cy + (float) Math.sin(angle) * trailDist,
                          sz * 0.3f, sparkPaint);
        }
    }

    private void drawPop(Canvas c, int w, int h, float cx, float cy, float r) {
        float p = popProgress;
        float scale;
        if (p < 0.2f) scale = 1f + p * 1.0f;
        else if (p < 0.45f) scale = 1.2f - ((p - 0.2f) / 0.25f) * 1.2f;
        else scale = 0;
        int alpha = (int) (255 * (1f - p * p));

        if (scale > 0 && alpha > 0) {
            c.save();
            c.scale(scale, scale, cx, cy);
            bg.setShader(texture);
            bg.setAlpha(alpha);
            c.drawCircle(cx, cy, r, bg);
            bg.setShader(null);
            bg.setAlpha(255);
            c.restore();
        }

        // 2 ripple-кольца
        for (int i = 0; i < 2; i++) {
            float rp = p - i * 0.15f;
            if (rp < 0 || rp > 0.7f) continue;
            float rr = r * (1f + rp * (2f + i));
            float ra = (1f - rp / 0.7f) * (0.5f - i * 0.2f);
            if (ra <= 0) continue;
            tmpPaint.setStyle(Paint.Style.STROKE);
            tmpPaint.setStrokeWidth(dp(2 - i * 0.5f));
            tmpPaint.setColor(0xDDFFFFFF);
            tmpPaint.setAlpha((int) (255 * ra));
            c.drawCircle(cx, cy, rr, tmpPaint);
        }

        // burst-частицы
        for (float[] bp : burst) {
            float angle = bp[0], size = bp[2];
            int color = (int) bp[3];
            float speed = bp[4];
            float t = p * speed;
            float dist = r * 0.5f + t * r * 2.5f;
            float x = cx + (float) Math.cos(angle) * dist;
            float y = cy + (float) Math.sin(angle) * dist;
            float sz = size * (1f - t * 0.5f);
            int a = (int) (Color.alpha(color) * Math.max(0, 1f - t));
            if (a <= 0 || sz <= 0) continue;
            sparkPaint.setColor(color);
            sparkPaint.setAlpha(a);
            c.drawCircle(x, y, sz, sparkPaint);
            float td = dist - dp(10);
            if (td > 0) {
                sparkPaint.setAlpha((int) (a * 0.3f));
                c.drawCircle(cx + (float) Math.cos(angle) * td,
                              cy + (float) Math.sin(angle) * td,
                              sz * 0.4f, sparkPaint);
            }
        }

        // центральная вспышка
        if (p < 0.35f) {
            float fa = (1f - p / 0.35f) * 0.5f;
            tmpPaint.setStyle(Paint.Style.FILL);
            tmpPaint.setColor(0xFFFFFFFF);
            tmpPaint.setAlpha((int) (255 * fa));
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
