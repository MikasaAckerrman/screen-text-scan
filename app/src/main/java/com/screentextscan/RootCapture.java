package com.screentextscan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.IOException;

/**
 * Снимки экрана через ROOT (su -c screencap) — вторая линия после Shizuku.
 *
 * ЗАЧЕМ. Когда Shizuku недоступен (биндер умер, UserService не поднялся),
 * прежний код уходил на MediaProjection — и показывал системный диалог
 * «в каком приложении снимать/записать» (жалоба 09.10: «максимально
 * неудобно»). С root'ом диалоги не нужны вовсе: uid 0 читает экран
 * напрямую. Единственный разовый вопрос — SukiSU спросит «Разрешить
 * ScreenTextScan?» при ПЕРВОМ вызове su: это диалог root-менеджера,
 * не системы; «Всегда разрешать» закрывает его навсегда.
 *
 * МЕХАНИКА. su -c "screencap -p <файл>": кадр пишется root'ом в файл
 * внешнего хранилища приложения (root пишет куда угодно), приложение
 * читает его как свой каталог. Каждый кадр — новый exec: всегда свежий,
 * скролл читается честно. su-авторизация кешируется менеджером root
 * (KernelSU/SukiSU) — повторные кадры идут без задержки на подтверждение.
 *
 * ПУТЬ ВСЕГДА ФОНОВЫЙ: su и screencap — блокирующие вызовы по 100-500 мс.
 */
public final class RootCapture {

    /** Кеш «su доступен приложению» (проверяется один раз). */
    private static volatile int suState = 0;  // 0 — неизвестно, 1 — да, -1 — нет
    /** Последняя неудачная проверка su: ретрай каждые 30 с (самолечение —
     *  грант KernelSU может быть выдан ПОСЛЕ старта приложения). */
    private static volatile long lastRootDeny;

    /**
     * Есть ли у приложения root? Успех кешируется навсегда; отказ
     * перепроверяется не чаще раза в 30 секунд — если пользователь
     * выдаст грант KernelSU позже, путь оживёт сам, без перезапуска.
     */
    public static boolean hasRoot() {
        int s = suState;
        if (s == 1) return true;
        if (s == -1) {
            long now = System.currentTimeMillis();
            if (now - lastRootDeny < 30_000) return false;
        }
        try {
            long t0 = android.os.SystemClock.elapsedRealtime();
            Process p = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", "id"});
            boolean ok = p.waitFor() == 0;
            suState = ok ? 1 : -1;
            if (!ok) lastRootDeny = System.currentTimeMillis();
            android.util.Log.d("ScreenTextScan",
                    "root: su -c id → " + ok + " за "
                            + (android.os.SystemClock.elapsedRealtime() - t0) + "мс");
            return ok;
        } catch (Throwable t) {
            suState = -1;
            lastRootDeny = System.currentTimeMillis();
            android.util.Log.d("ScreenTextScan", "root: su exec: " + t);
            return false;
        }
    }

    /**
     * Выполнить составную shell-команду от root (для RootSelfGrant:
     * несколько команд одной su-сессией). Возвращает успех/провал.
     */
    static boolean execRaw(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", cmd});
            return p.waitFor() == 0;
        } catch (Throwable t) {
            android.util.Log.d("ScreenTextScan", "root exec: " + t);
            return false;
        }
    }

    /**
     * Декод RAW-кадра screencap (без PNG — кодирование PNG стоило
     * 300-400 мс CPU на кадр). Формат: int32 w, int32 h, int32 format,
     * затем w*h*4 байта RGBA_8888.
     *
     * @param f raw-файл
     * @return Bitmap ARGB_8888 или null (битый/неожиданный формат —
     *         вызывающий решает, пробовать ли PNG-вариант)
     */
    static Bitmap decodeRawFile(java.io.File f) {
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            if (len < 16) return null;
            int w = raf.readInt();
            int h = raf.readInt();
            int fmt = raf.readInt();
            if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return null;
            long pixels = (long) w * h * 4;
            if (len < 12 + pixels) return null;
            if (fmt != 1 && fmt != 4) return null;  // не RGBA_8888
            byte[] buf = new byte[(int) pixels];
            raf.readFully(buf);
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buf);
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(bb);
            return bmp;
        } catch (Throwable t) {
            return null;
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * Снимок экрана. NULL = root недоступен/битый кадр — вызывающий
     * остаётся на предыдущем источнике. Только из фонового потока.
     * RAW (без PNG) — в 3-5 раз быстрее PNG-пути на кадр.
     */
    public static Bitmap capture(Context ctx) {
        if (!hasRoot()) return null;
        File f = new File(ctx.getExternalFilesDir(null), "root_cap.raw");
        try {
            f.delete();
            Process p = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", "screencap " + f.getAbsolutePath()});
            p.waitFor();
            if (!f.exists() || f.length() < 16) return null;
            Bitmap bmp = decodeRawFile(f);
            if (bmp != null) return bmp;
            // Редкий формат экрана: резервный PNG-вариант.
            f.delete();
            Process p2 = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", "screencap -p " + f.getAbsolutePath()});
            p2.waitFor();
            if (!f.exists() || f.length() < 100) return null;
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable t) {
            android.util.Log.d("ScreenTextScan", "root capture: " + t);
            return null;
        } finally {
            f.delete();
        }
    }
}
