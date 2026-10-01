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
    /** Всегда СВЕЖИЙ кадр (обновляется слушателем) — см. start(). */
    private volatile Bitmap latest;
    /** Поток слушателя: конвертация кадра не должна занимать главный. */
    private android.os.HandlerThread readerThread;
    private android.os.Handler readerHandler;

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
                    PixelFormat.RGBA_8888, 2);
            /*
             * ДРЕНАЖ ОЧЕРЕДИ — критично. У поверхности буферов мало; если
             * кадры НЕ забирать, очередь заполняется ПЕРВЫМ кадром и
             * VirtualDisplay останавливается навсегда: тогда capture()
             * вечно возвращает один и тот же устаревший кадр (экран на
             * момент старта) — «текст не тот, при скролле не читается».
             * Слушатель забирает НОВЕЙШИЙ кадр, конвертирует в Bitmap и
             * закрывает Image — очередь всегда свободна, кадры текут.
             */
            readerThread = new android.os.HandlerThread("sts-reader");
            readerThread.start();
            readerHandler = new android.os.Handler(readerThread.getLooper());
            reader.setOnImageAvailableListener(this::drainFrame, readerHandler);
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

    /** Забрать новейший кадр в буфер-хранитель (вызывается слушателем). */
    private void drainFrame(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            Image.Plane[] planes = img.getPlanes();
            int rowStride = planes[0].getRowStride();
            int pixelStride = planes[0].getPixelStride();
            Bitmap nb = Bitmap.createBitmap(
                    rowStride / pixelStride, img.getHeight(),
                    Bitmap.Config.ARGB_8888);
            nb.copyPixelsFromBuffer(planes[0].getBuffer());
            // Обрезаем выравнивание строки, если Bitmap шире кадра.
            if (nb.getWidth() != width || nb.getHeight() != height) {
                Bitmap cropped = Bitmap.createBitmap(nb, 0, 0,
                        Math.min(width, nb.getWidth()),
                        Math.min(height, nb.getHeight()));
                nb.recycle();
                nb = cropped;
            }
            Bitmap old = latest;
            latest = nb;
            if (old != null) old.recycle();
        } catch (RuntimeException ignored) {
            // битый кадр — пропускаем, следующий прибудет
        } finally {
            if (img != null) {
                try { img.close(); } catch (RuntimeException ignored) { }
            }
        }
    }

    /**
     * Свежий кадр (копия — вызывающий распоряжается ею и утилизирует).
     * NULL = кадр ещё не пришёл (первые ~100 мс после старта).
     */
    public Bitmap capture() {
        Bitmap l = latest;
        if (l == null) return null;
        try {
            return Bitmap.createBitmap(l);
        } catch (RuntimeException e) {
            return null;
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
        if (readerThread != null) {
            readerThread.quitSafely();
            try { readerThread.join(200); } catch (InterruptedException ignored) { }
            readerThread = null;
            readerHandler = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (RuntimeException ignored) { }
            projection = null;
        }
        Bitmap l = latest;
        latest = null;
        if (l != null) l.recycle();
    }
}
