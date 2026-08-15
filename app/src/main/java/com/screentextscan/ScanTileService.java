package com.screentextscan;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Плитка в панели быстрых настроек.
 *
 * ЧТО ЗДЕСЬ БЫЛО СЛОМАНО. Готовность определялась по статической ссылке на
 * службу доступности, а она пуста в свежесозданном процессе. Система убивает
 * процесс приложения постоянно — оно ей не нужно, — поэтому после каждого
 * такого убийства плитка считала службу выключенной и ставила себе
 * STATE_UNAVAILABLE. Плитка в этом состоянии в Android физически не
 * нажимается: отсюда «если приложение не запущено, через плитку не вызвать».
 *
 * Теперь состояние берётся из системной настройки (см. Permissions), и
 * плитка НИКОГДА не бывает недоступной: если разрешений нет, она остаётся
 * нажимаемой и ведёт на экран настройки. Даже при неверном определении
 * готовности пользователь не окажется в тупике.
 */
public class ScanTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile t = getQsTile();
        if (t == null) return;

        boolean ready = Permissions.ready(this);
        /*
         * STATE_INACTIVE в обоих случаях — намеренно. Разница только в
         * подписи. STATE_UNAVAILABLE не используем нигде: он делает плитку
         * ненажимаемой, а значит лишает единственного способа узнать, чего
         * не хватает.
         */
        t.setState(Tile.STATE_INACTIVE);
        t.setLabel(getString(R.string.tile_label));
        t.setContentDescription(ready
                ? "Начать чтение текста с экрана"
                : "Нужна настройка — нажмите");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            t.setSubtitle(ready ? null : "нужна настройка");
        }
        t.updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();

        /*
         * Куда ведём. Если чего-то не хватает — на экран настройки, он
         * показывает, что именно. Если всё есть — в невидимый переходник,
         * который поднимает сервис.
         *
         * ПОЧЕМУ НЕ СЕРВИС НАПРЯМУЮ: панель быстрых настроек закрывается
         * только как побочный эффект запуска АКТИВНОСТИ. Пока панель
         * открыта, активное окно — это она, и служба доступности прочитает
         * саму панель вместо приложения под ней.
         */
        boolean ready = Permissions.ready(this);
        Intent target = ready
                ? new Intent(this, LaunchActivity.class)
                : new Intent(this, MainActivity.class);
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // С Android 14 вариант с Intent запрещён и бросает исключение.
            PendingIntent pi = PendingIntent.getActivity(
                    this, 0, target,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pi);
        } else {
            collapseLegacy(target);
        }
    }

    /**
     * Ветка для Android 13 и ниже: там варианта с PendingIntent ещё нет,
     * а вариант с Intent — единственный рабочий. На 14+ он бросает
     * исключение, поэтому условие по версии выше обязательно.
     */
    @SuppressWarnings("deprecation")
    private void collapseLegacy(Intent target) {
        startActivityAndCollapse(target);
    }
}
