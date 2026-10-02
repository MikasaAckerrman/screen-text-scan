package com.screentextscan;

import java.io.File;

/**
 * Реализация UserService Shizuku: исполняется ВНУТРИ сервера Shizuku
 * (uid shell). screencap из-под shell — законный системный механизм
 * (как adb shell screencap): ни MediaProjection, ни согласий.
 *
 * Загружается сервером Shizuku по ComponentName; класс обязан жить
 * в отдельном процессе (см. манифест, :shizuku).
 */
public class CaptureServiceImpl extends ICaptureService.Stub {

    @Override
    public String screencap(String path) {
        try {
            // Убрать прошлый кадр: screencap не перезаписывает атомарно.
            new File(path).delete();
            Process p = Runtime.getRuntime().exec(
                    new String[]{"screencap", "-p", path});
            // screencap быстрый (~250 мс), но ждать обязаны: файл должен
            // быть готов к чтению.
            p.waitFor();
            return new File(path).exists() ? path : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void destroy() {
        // Ничего не держим: процессы одноразовые.
    }
}
