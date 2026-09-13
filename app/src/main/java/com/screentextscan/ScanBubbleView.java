package com.screentextscan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
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
 * Премиум плавающий круг: тёмная текстура, сине-фиолетовые эффекты,
 * полоски-стримеры вместо точек.
 *
 * View = 200dp, круг = 62dp по центру.
 *
 *   тап        → копировать + flash + продолжить
 *   зажатие 2.5с → искры-стримеры → pop → убрать
 */
public class ScanBubbleView extends View {

    // --- палитра: сине-фиолетовый премиум ---
    private static final int FG        = 0xFFFFFFFF;
    private static final int RING_DIM  = 0x22FFFFFF;
    private static final int RING_FG   = 0xFFFFFFFF;

    // спектр от глубокого синего до фиолетового — НЕ мультяшно
    private static final int[] SPARK_COLORS = {
        0xCC4466FF,  // яркий синий
        0xCC5544CC,  // фиолетовый
        0xCC6633DD,  // глубокий фиолетовый
        0xCC7755EE,  // светло-фиолетовый
        0xCC3377FF,  // электрик-синий
        0xCC8844FF,  // пурпур
        0xCC4488EE,  // индиго
        0xCC5577DD,  // приглушённый сине-фиолетовый
    };

    private static final float CIRCLE_DP = 31f;

    // paints
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint streakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();
    private final Path tmpPath = new Path();
    private RadialGradient texture;

    private int count;
    private float readiness;
    private boolean complete;

    // press
    private float pressScale = 1.0f;
    private float glowAlpha = 0f;

    // всасывание — полоски-стримеры
    private static final long ABSORB_MS = 800;
    private static final int ABSORB_COUNT = 16;
    private boolean absorbing = false;
    private long absorbStart;
    private final List<float[]> absorbParticles = new ArrayList<>();
    private final Random rnd = new Random();

    // искры зажатия
    private static final int SPARK_COUNT = 36;
    private static final long LONG_PRESS_MS = 2500;
    private final List<float[]> sparks = new ArrayList<>();
    private long pressStart;
    private boolean pressing;
    private float pressProgress;
    private float pulse;

    // flash
    private static final long FLASH_MS = 650;
    private boolean flashing = false;
    private long flashStart;

    // pop
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
        streakPaint.setStyle(Paint.Style.STROKE);
        streakPaint.setStrokeCap(Paint.Cap.ROUND);
        dotPaint.setStyle(Paint.Style.FILL);
    }

    public void setCount(int c) { count = c; invalidate(); }
    public void setReadiness(float r, boolean done) {
        readiness = r < 0 ? 0 : (r > 1 ? 1 : r);
        complete = done;
        invalidate();
    }

    // === PRESS ===
    public void onPress() { pressScale = 0.93f; glowAlpha = 1.0f; invalidate(); }
    public void onRelease() { pressScale = 1.0f; glowAlpha = 0f; invalidate(); }

    // === ВСАСЫВАНИЕ ===
    public void pulseNewText() {
        absorbing = true;
        absorbStart = System.currentTimeMillis();
        absorbParticles.clear();
        for (int i = 0; i < ABSORB_COUNT; i++) {
            float angle = (float) (i * (2 * Math.PI / ABSORB_COUNT)) + rnd.nextFloat() * 0.4f;
            float dist = dp(75 + rnd.nextInt(45));
            float len = dp(12 + rnd.nextInt(20));
            float width = dp(1 + rnd.nextFloat() * 1.5f);
            int color = SPARK_COLORS[rnd.nextInt(SPARK_COLORS.length)];
            absorbParticles.add(new float[]{angle, dist, len, width, color});
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
            float dist = dp(85 + rnd.nextInt(55));
            float len = dp(10 + rnd.nextInt(25));
            float width = dp(1 + rnd.nextFloat() * 1.5f);
            int color = SPARK_COLORS[rnd.nextInt(SPARK_COLORS.length)];
            sparks.add(new float[]{angle, dist, len, width, color});
        }
        invalidate();
    }

    public void cancelLongPressAnim() {
        pressing = false;
        sparks.clear();
        pressProgress = 0;
        invalidate();
    }

    // === FLASH ===
    public void flashCopy() { flashing = true; flashStart = System.currentTimeMillis(); invalidate(); }

    // === POP ===
    public void pop(Runnable done) {
        onPopDone = done;
        popping = true;
        popStart = System.currentTimeMillis();
        popProgress = 0;
        burst.clear();
        for (int i = 0; i < 28; i++) {
            float angle = (float) (i * (2 * Math.PI / 28)) + rnd.nextFloat() * 0.3f;
            float len = dp(15 + rnd.nextInt(20));
            float width = dp(1.5f + rnd.nextFloat() * 2f);
            float speed = 0.8f + rnd.nextFloat() * 0.6f;
            int color = SPARK_COLORS[rnd.nextInt(SPARK_COLORS.length)];
            burst.add(new float[]{angle, 0, len, width, color, speed});
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

        if (popping) {
            long elapsed = System.currentTimeMillis() - popStart;
            popProgress = Math.min(1f, (float) elapsed / POP_MS);
            drawPop(c, w, h, cx, cy, r);
            if (popProgress >= 1f) {
                popping = false;
                final Runnable cb = onPopDone;
                onPopDone = null;
                if (cb != null) post(cb);
            } else invalidate();
            return;
        }

        // glow
        if (glowAlpha > 0.01f) {
            tmpPaint.setColor(0x446688FF);
            tmpPaint.setAlpha((int) (90 * glowAlpha));
            c.drawCircle(cx, cy, r + dp(10), tmpPaint);
            needInvalidate = true;
            glowAlpha *= 0.93f;
        }

        drawCircle(c, cx, cy, r);

        if (flashing) {
            long elapsed = System.currentTimeMillis() - flashStart;
            float fp = Math.min(1f, (float) elapsed / FLASH_MS);
            drawFlash(c, cx, cy, r, fp);
            if (fp >= 1f) flashing = false;
            needInvalidate = true;
        }

        if (pressing) {
            long elapsed = System.currentTimeMillis() - pressStart;
            pressProgress = Math.min(1f, (float) elapsed / LONG_PRESS_MS);
            pulse = (float) (0.5 + 0.5 * Math.sin(elapsed * 0.012));
            drawSparks(c, cx, cy);
            if (pressProgress < 1f) drawPressRing(c, cx, cy, r);
            needInvalidate = true;
        }

        if (absorbing) {
            long elapsed = System.currentTimeMillis() - absorbStart;
            float ap = Math.min(1f, (float) elapsed / ABSORB_MS);
            drawAbsorb(c, cx, cy, r, ap);
            if (ap >= 1f) { absorbing = false; absorbParticles.clear(); }
            needInvalidate = true;
        }

        if (needInvalidate) invalidate();
    }

    /** «Вкусная» тёмная текстура: radial gradient с сине-фиолетовым бликом. */
    private void drawCircle(Canvas c, float cx, float cy, float r) {
        if (texture == null) {
            texture = new RadialGradient(
                -r * 0.3f, -r * 0.4f, r * 2.2f,
                new int[]{0xFF1A1830, 0xF0121420, 0xF0080810},
                new float[]{0f, 0.4f, 1f},
                Shader.TileMode.CLAMP);
        }
        bg.setShader(texture);
        c.drawCircle(cx, cy, r, bg);
        bg.setShader(null);

        // внутренняя обводка
        tmpPaint.set(ringBg);
        tmpPaint.setColor(0x15FFFFFF);
        c.drawCircle(cx, cy, r - dp(1), tmpPaint);

        // кольцо готовности
        tmpRect.set(cx - r + dp(2), cy - r + dp(2), cx + r - dp(2), cy + r - dp(2));
        c.drawArc(tmpRect, 0, 360, false, ringBg);
        if (readiness > 0) c.drawArc(tmpRect, -90, 360 * readiness, false, ring);

        if (complete) {
            drawTick(c, cx, cy, r * 0.42f);
        } else {
            num.setTextSize(sp(count >= 100 ? 15 : 18));
            float textH = num.ascent() + num.descent();
            c.drawText(String.valueOf(count), cx, cy - textH / 2, num);
        }
    }

    /** Всасывание: полоски-стримеры тянутся к центру. */
    private void drawAbsorb(Canvas c, float cx, float cy, float r, float p) {
        for (float[] ap : absorbParticles) {
            float angle = ap[0], startDist = ap[1], len = ap[2], width = ap[3];
            int color = (int) ap[4];
            // позиция головы стримера
            float dist = startDist * (1f - p) + r * 0.2f * (1f - p);
            float appear = Math.min(1f, p * 2.5f);
            if (appear <= 0) continue;
            float fade = (1f - p * 0.9f) * appear;
            int alpha = (int) (Color.alpha(color) * fade);
            if (alpha <= 0) continue;

            float headX = cx + (float) Math.cos(angle) * dist;
            float headY = cy + (float) Math.sin(angle) * dist;
            // хвост стримера — дальше от центра
            float tailDist = dist + len * (1f - p * 0.5f);
            float tailX = cx + (float) Math.cos(angle) * tailDist;
            float tailY = cy + (float) Math.sin(angle) * tailDist;

            // градиентная полоска: голова яркая, хвост затухает
            streakPaint.setStrokeWidth(width * (0.5f + 0.5f * (1f - p)));
            streakPaint.setColor(color);
            streakPaint.setAlpha(alpha);
            c.drawLine(headX, headY, tailX, tailY, streakPaint);

            // яркая точка на голове
            dotPaint.setColor(color);
            dotPaint.setAlpha(alpha);
            c.drawCircle(headX, headY, width * 0.8f, dotPaint);
        }
    }

    /** Искры зажатия: сине-фиолетовые стримеры сходятся к центру. */
    private void drawSparks(Canvas c, float cx, float cy) {
        for (float[] s : sparks) {
            float angle = s[0], startDist = s[1], len = s[2], width = s[3];
            int color = (int) s[4];
            float dist = startDist * (1f - pressProgress);
            float appear = Math.min(1f, pressProgress * 1.5f + angle * 0.04f);
            if (appear <= 0) continue;

            float headX = cx + (float) Math.cos(angle) * dist;
            float headY = cy + (float) Math.sin(angle) * dist;
            float tailDist = dist + len * (1f - pressProgress * 0.5f);
            float tailX = cx + (float) Math.cos(angle) * tailDist;
            float tailY = cy + (float) Math.sin(angle) * tailDist;

            int alpha = (int) (Color.alpha(color) * appear * (1f - pressProgress * 0.3f));
            streakPaint.setStrokeWidth(width * appear);
            streakPaint.setColor(color);
            streakPaint.setAlpha(alpha);
            c.drawLine(headX, headY, tailX, tailY, streakPaint);

            // голова-точка
            dotPaint.setColor(color);
            dotPaint.setAlpha(alpha);
            float sz = width * appear * (1f - pressProgress * 0.5f);
            c.drawCircle(headX, headY, sz, dotPaint);
        }
    }

    private void drawPressRing(Canvas c, float cx, float cy, float r) {
        float rr = r + dp(6 + pulse * 3);
        tmpRect.set(cx - rr, cy - rr, cx + rr, cy + rr);
        tmpPaint.set(ring);
        tmpPaint.setStrokeWidth(dp(2.5f + pulse));
        tmpPaint.setColor(0x665577FF);
        c.drawArc(tmpRect, -90, 360 * pressProgress, false, tmpPaint);
    }

    /** Flash копирования: кольцо + стримеры. */
    private void drawFlash(Canvas c, float cx, float cy, float r, float p) {
        // кольцо
        float fr = r * (1f + p * 1.5f);
        float fa = (1f - p) * 0.7f;
        tmpPaint.setStyle(Paint.Style.STROKE);
        tmpPaint.setStrokeWidth(dp(2.5f * (1f - p)));
        tmpPaint.setColor(0xDD6688FF);
        tmpPaint.setAlpha((int) (255 * fa));
        c.drawCircle(cx, cy, fr, tmpPaint);

        // стримеры наружу
        for (int i = 0; i < 10; i++) {
            float a = (float) (i * 2 * Math.PI / 10);
            float d1 = r + p * r * 0.8f;
            float d2 = r + p * r * 1.4f;
            float x1 = cx + (float) Math.cos(a) * d1;
            float y1 = cy + (float) Math.sin(a) * d1;
            float x2 = cx + (float) Math.cos(a) * d2;
            float y2 = cy + (float) Math.sin(a) * d2;
            int color = SPARK_COLORS[i % SPARK_COLORS.length];
            streakPaint.setStrokeWidth(dp(2) * (1f - p));
            streakPaint.setColor(color);
            streakPaint.setAlpha((int) (200 * (1f - p)));
            c.drawLine(x1, y1, x2, y2, streakPaint);
        }
    }

    /** Pop: расширение → 2 ripple → стримеры наружу → вспышка. */
    private void drawPop(Canvas c, int w, int h, float cx, float cy, float r) {
        float p = popProgress;
        float scale = p < 0.2f ? 1f + p
                : p < 0.45f ? 1.2f - ((p - 0.2f) / 0.25f) * 1.2f : 0;
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
            tmpPaint.setColor(0xDD6688FF);
            tmpPaint.setAlpha((int) (255 * ra));
            c.drawCircle(cx, cy, rr, tmpPaint);
        }

        // стримеры наружу
        for (float[] bp : burst) {
            float angle = bp[0], len = bp[2], width = bp[3];
            int color = (int) bp[4];
            float speed = bp[5];
            float t = p * speed;
            float dist = r * 0.4f + t * r * 2.5f;
            float headX = cx + (float) Math.cos(angle) * dist;
            float headY = cy + (float) Math.sin(angle) * dist;
            float tailDist = dist + len * (1f - t * 0.5f);
            float tailX = cx + (float) Math.cos(angle) * tailDist;
            float tailY = cy + (float) Math.sin(angle) * tailDist;
            int a = (int) (Color.alpha(color) * Math.max(0, 1f - t));
            if (a <= 0) continue;
            streakPaint.setStrokeWidth(width * (1f - t * 0.5f));
            streakPaint.setColor(color);
            streakPaint.setAlpha(a);
            c.drawLine(headX, headY, tailX, tailY, streakPaint);
            // голова
            dotPaint.setColor(color);
            dotPaint.setAlpha(a);
            c.drawCircle(headX, headY, width * (1f - t * 0.5f), dotPaint);
        }

        // центральная вспышка
        if (p < 0.35f) {
            float fa = (1f - p / 0.35f) * 0.4f;
            tmpPaint.setStyle(Paint.Style.FILL);
            tmpPaint.setColor(0xCC7788FF);
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
