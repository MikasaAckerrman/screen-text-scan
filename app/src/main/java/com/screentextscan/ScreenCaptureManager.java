package com.screentextscan;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;

/**
 * Снимки экрана для vision-режима (MediaProjection).
 *
 * Разрешение одно и на весь скан: прозрачная активность показывает
 * системный диалог один раз, токен живёт до конца чтения. Снимок — не
 * видео-поток: ImageReader держит ОДИН буфер, берём свежий кадр по
 * запросу, предыдущие отбрасываются (acquireLatestImage) — память
 * константна, ре-аллокаций нет.
 */
public final class ScreenCaptureManager {

    public interface Listener {
        /** Результат запроса разрешения (из активити-посредника). */
        void onGrantResult(boolean granted);
    }

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private int width, height;

    public boolean isReady() { return projection != null && reader != null; }

    /** Запросить системное разрешение на захват экрана (один раз за скан). */
    public void requestGrant(Context ctx, Listener l) {
        VisionGrantActivity.request(ctx, l);
    }

    /** Создать проекцию по данным разрешения. Вызывать на главном потоке. */
    public void start(Context ctx, int resultCode, Intent data) {
        stop();
        MediaProjectionManager mpm = (MediaProjectionManager)
                ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm.getMediaProjection(resultCode, data);
            if (projection == null) return;
            WindowManager wm = (WindowManager)
                    ctx.getSystemService(Context.WINDOW_SERVICE);
            Point p = new Point();
            wm.getDefaultDisplay().getRealSize(p);
            // Половина разрешения: OCR читает и так, памяти/времени вдвое
            // меньше, шрифты интерфейсов крупны. Экран 1260x2800 → 630x1400.
            width = Math.max(320, p.x / 2);
            height = Math.max(320, p.y / 2);
            reader = ImageReader.newInstance(width, height,
                    PixelFormat.RGBA_8888, 1);
            reader.setOnImageAvailableListener(r -> { /* берём по запросу */ },
                    null);
            display = projection.createVirtualDisplay(
                    "sts-vision", width, height,
                    ctx.getResources().getDisplayMetrics().densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, null);
            if (Build.VERSION.SDK_INT >= 34) {
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() { stop(); }
                }, null);
            }
        } catch (RuntimeException e) {
            stop();
        }
    }

    /**
     * Свежий кадр. Первые кадры после старта бывают пустыми — ImageReader
     * наполняется асинхронно, пустой кадр = null, вызывающий просто
     * пропускает цикл.
     */
    public Bitmap capture() {
        if (reader == null) return null;
        Image img = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null) return null;
            Image.Plane[] planes = img.getPlanes();
            int rowStride = planes[0].getRowStride();
            int pixelStride = planes[0].getPixelStride();
            Bitmap full = Bitmap.createBitmap(
                    rowStride / pixelStride, img.getHeight(),
                    Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(planes[0].getBuffer());
            // Обрезаем выравнивание строки: createBitmap по rowStride может
            // быть шире кадра.
            Bitmap out = (full.getWidth() == width)
                    ? full
                    : Bitmap.createBitmap(full, 0, 0, width,
                            Math.min(height, full.getHeight()));
            if (out != full) full.recycle();
            return out;
        } catch (RuntimeException e) {
            return null;
        } finally {
            if (img != null) {
                try { img.close(); } catch (RuntimeException ignored) { }
            }
        }
    }

    /** Завершить проекцию. Освобождает токен — следующий скан спросит заново. */
    public void stop() {
        if (display != null) {
            try { display.release(); } catch (RuntimeException ignored) { }
            display = null;
        }
        if (reader != null) {
            try { reader.close(); } catch (RuntimeException ignored) { }
            reader = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (RuntimeException ignored) { }
            projection = null;
        }
    }
}
