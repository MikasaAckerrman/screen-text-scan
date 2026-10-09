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

    /**
     * Есть ли у приложения root? Однократная проверка «su -c id»:
     * выданный root запоминается менеджером, отказ тоже (exit код).
     */
    public static boolean hasRoot() {
        int s = suState;
        if (s != 0) return s == 1;
        try {
            long t0 = android.os.SystemClock.elapsedRealtime();
            Process p = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", "id"});
            boolean ok = p.waitFor() == 0;
            suState = ok ? 1 : -1;
            android.util.Log.d("ScreenTextScan",
                    "root: su -c id → " + ok + " за "
                            + (android.os.SystemClock.elapsedRealtime() - t0) + "мс");
            return ok;
        } catch (Throwable t) {
            suState = -1;
            android.util.Log.d("ScreenTextScan", "root: su exec: " + t);
            return false;
        }
    }

    /**
     * Снимок экрана. NULL = root недоступен/битый кадр — вызывающий
     * остаётся на предыдущем источнике. Только из фонового потока.
     */
    public static Bitmap capture(Context ctx) {
        if (!hasRoot()) return null;
        File f = new File(ctx.getExternalFilesDir(null), "root_cap.png");
        try {
            // Убрать прошлый кадр: screencap не пишет поверх атомарно.
            f.delete();
            Process p = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", "screencap -p " + f.getAbsolutePath()});
            p.waitFor();
            if (!f.exists() || f.length() < 100) return null;
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable t) {
            android.util.Log.d("ScreenTextScan", "root capture: " + t);
            return null;
        } finally {
            f.delete();  // кадр прочитан — файл не копим
        }
    }
}
