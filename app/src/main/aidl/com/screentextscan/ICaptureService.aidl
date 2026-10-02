// UserService Shizuku: методы исполняются внутри сервера Shizuku
// с правами shell (uid 2000). destroy() обязателен — Shizuku звать
// его при остановке пользовательского сервиса.
package com.screentextscan;

interface ICaptureService {
    /**
     * Снимок экрана в PNG-файл по указанному пути.
     * Путь — внешнее хранилище приложения (доступно shell через FUSE).
     * @return переданный путь или null при ошибке.
     */
    String screencap(String path);

    void destroy();
}
