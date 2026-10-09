// UserService Shizuku: методы исполняются внутри сервера Shizuku
// с правами shell (uid 2000). destroy() обязателен — Shizuku звать
// его при остановке пользовательского сервиса.
package com.screentextscan;

interface ICaptureService {
    /**
     * Снимок экрана в RAW-файл по указанному пути (без PNG: кодирование
     * PNG стоило 300-400 мс CPU на кадр).
     * @return переданный путь или null при ошибке.
     */
    String screencap(String path);

    /**
     * Выполнить shell-команду внутри сервера Shizuku (uid shell).
     * Для самолечения разрешений после загрузки: shell имеет
     * WRITE_SECURE_SETTINGS и appops. @return stdout или null.
     */
    String exec(String cmd);

    void destroy();
}
