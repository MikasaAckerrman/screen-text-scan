package com.screentextscan;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import rikka.shizuku.Shizuku;

/**
 * Снимки экрана через Shizuku (screencap из-под shell) — БЕЗ системного
 * диалога согласия и вообще без MediaProjection.
 *
 * ПОЧЕМУ ЭТО ЛУЧШЕ. Диалог «в каком приложении снимать/записать» — самая
 * неудобная точка флоу, а Android 15 спрашивает его КАЖДУЮ сессию.
 * screencap из-под shell — законный системный механизм (тот же, что
 * `adb shell screencap`): согласий не существует, тип сервиса
 * mediaProjection не нужен (исчезает и класс крашей Vivo на
 * SecurityException). У пользователя Shizuku живёт постоянно и
 * поднимается TCP-сервером при падении.
 *
 * СВЕЖЕСТЬ КАДРА. Каждый снимок — НОВЫЙ процесс: кадр всегда текущий,
 * «замороженный снимок» невозможен по построению (в отличие от
 * MediaProjection-потока, где очередь кадров могла стоять). Скролл
 * читается честно: что на экране в момент снимка — то и в кадре.
 *
 * РАЗРЕШЕНИЕ: один раз навсегда — shell-диалог Shizuku «Разрешить
 * ScreenTextScan?» (не системный MediaProjection, не на каждый скан).
 * До разрешения — capture() возвращает null, вызывающий деградирует
 * на MediaProjection-фолбэк.
 */
public final class ShizukuCapture {

    private static final int PERMISSION_REQUEST = 7701;

    /** Биндер Shizuku жив? (Shizuku запущен — ещё не значит «разрешено».) */
    public static boolean isAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Разрешение выдано этому приложению? */
    public static boolean isGranted() {
        try {
            return isAlive() && Shizuku.checkSelfPermission() == Shizuku.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Запросить разрешение (один раз навсегда). Диалог Shizuku — не
     * системный: он показывается приложением-менеджером Shizuku.
     * Вызывать с главного потока.
     */
    public static void requestPermission() {
        try {
            if (isAlive() && Shizuku.checkSelfPermission() != Shizuku.PERMISSION_GRANTED) {
                Shizuku.requestPermission(PERMISSION_REQUEST);
            }
        } catch (Throwable ignored) {
            // биндер умер между проверкой и запросом — фолбэк MP решит
        }
    }

    /**
     * Снимок экрана. NULL = Shizuku недоступен/не разрешён/битый кадр —
     * вызывающий переходит на MediaProjection-фолбэк.
     *
     * ВРЕМЯ: screencap ~200-400 мс + PNG-декод ~50 мс. Вызывать только
     * из фонового потока (у нас — sts-scan).
     */
    public static Bitmap capture() {
        if (!isGranted()) return null;
        Process p = null;
        try {
            // -p: PNG в stdout. Тот же вызов, что adb exec-out screencap.
            p = Shizuku.newProcess(new String[]{"screencap", "-p"}, null, null);
            byte[] png = readAll(p.getInputStream());
            p.waitFor();
            if (png.length < 100) return null;  // пустой/битый вывод
            return BitmapFactory.decodeByteArray(png, 0, png.length);
        } catch (Throwable t) {
            android.util.Log.d("ScreenTextScan", "shizuku capture: " + t);
            return null;
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) { }
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
