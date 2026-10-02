package com.screentextscan;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.IBinder;

import java.io.File;
import java.io.IOException;

import rikka.shizuku.Shizuku;

/**
 * Снимки экрана через Shizuku UserService (screencap с правами shell) —
 * БЕЗ системного диалога согласия и без MediaProjection.
 *
 * МЕХАНИЗМ (форк 13.6, публичный API): bindUserService поднимает наш
 * CaptureServiceImpl ВНУТРИ сервера Shizuku (uid shell); он исполняет
 * «screencap -p <файл>» — тот же законный вызов, что «adb shell
 * screencap». Файл кладётся во внешнее хранилище приложения (доступно
 * обеим сторонам: shell через FUSE, приложению — как свой каталог),
 * binder переносит только путь — лимит транзакций не задет.
 *
 * СВЕЖЕСТЬ КАДРА. Каждый снимок — новый exec: кадр всегда текущий
 * (замороженный снимок невозможен по построению), скролл читается
 * честно.
 *
 * РАЗРЕШЕНИЕ: один раз навсегда (shell-диалог Shizuku, не системный).
 * Константы результата — стандартные PackageManager.PERMISSION_*.
 */
public final class ShizukuCapture {

    private static final int PERMISSION_REQUEST = 7701;

    private static volatile ICaptureService service;
    private static ServiceConnection connection;

    /** Биндер Shizuku жив? */
    public static boolean isAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Разрешение выдано этому приложению? (0 = PERMISSION_GRANTED) */
    public static boolean isGranted() {
        try {
            return isAlive()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Запросить разрешение Shizuku (один раз навсегда, shell-диалог).
     * Вызывать с главного потока.
     */
    public static void requestPermission() {
        try {
            if (isAlive() && Shizuku.checkSelfPermission()
                    != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(PERMISSION_REQUEST);
            }
        } catch (Throwable ignored) {
            // биндер умер между проверкой и запросом — фолбэк решит
        }
    }

    /**
     * Поднять UserService (асинхронно: готовность — в capture()).
     * Вызывать с главного потока (bindService с флагами).
     */
    public static synchronized void bind(Context ctx) {
        if (service != null || connection != null) return;
        try {
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(ctx, CaptureServiceImpl.class))
                    .daemon(false)
                    .processNameSuffix("screencap")
                    .version(versionCode(ctx));
            connection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    service = ICaptureService.Stub.asInterface(binder);
                    android.util.Log.d("ScreenTextScan", "shizuku: UserService подключен");
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    service = null;
                }
            };
            Shizuku.bindUserService(args, connection);
        } catch (Throwable t) {
            android.util.Log.d("ScreenTextScan", "shizuku bind: " + t);
            connection = null;
        }
    }

    /** Отпустить UserService (больше кадров не будет). */
    public static synchronized void unbind(Context ctx) {
        if (connection == null) return;
        try {
            Shizuku.unbindUserService(
                    new Shizuku.UserServiceArgs(
                            new ComponentName(ctx, CaptureServiceImpl.class))
                            .daemon(false)
                            .processNameSuffix("screencap")
                            .version(versionCode(ctx)),
                    connection, true);
        } catch (Throwable ignored) {
        }
        connection = null;
        service = null;
    }

    /**
     * Снимок экрана. NULL = сервис не готов/ошибка — вызывающий
     * деградирует на MediaProjection-фолбэк. Только из фонового потока.
     */
    public static Bitmap capture(Context ctx) {
        ICaptureService s = service;
        if (s == null) return null;
        File f = new File(ctx.getExternalFilesDir(null), "shizuku_cap.png");
        try {
            String path = s.screencap(f.getAbsolutePath());
            if (path == null) return null;
            Bitmap bmp = BitmapFactory.decodeFile(path);
            return bmp;
        } catch (Throwable t) {
            android.util.Log.d("ScreenTextScan", "shizuku capture: " + t);
            return null;
        } finally {
            f.delete();  // кадр прочитан — файл не копим
        }
    }

    /** UserService готов принимать вызовы? */
    public static boolean isBound() {
        return service != null;
    }

    private static int versionCode(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 1;
        }
    }
}
