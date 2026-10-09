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
            /*
             * БЕЗ «-p»: RAW RGBA вместо PNG. PNG-кодирование стоило
             * 300-400 мс CPU на кадр (замер 09.10: цикл чтения 2,2 с) —
             * raw-файл пишется последовательной записью (~50-80 мс на
             * UFS), а приложение читает пиксели напрямую в Bitmap.
             * Формат файла: 12 байт заголовка (w, h, format) + w*h*4
             * байт RGBA_8888.
             */
            Process p = Runtime.getRuntime().exec(
                    new String[]{"screencap", path});
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
