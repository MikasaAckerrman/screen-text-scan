package com.screentextscan;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Плитка в панели быстрых настроек — то, ради чего нужен APK.
 *
 * ПОЧЕМУ ТОЛЬКО ТАК. Плитка — системная сущность: её объявляет манифест
 * приложения, и добавить её в панель извне нельзя ничем. Пользователь один
 * раз перетаскивает её в панель, дальше чтение запускается двумя жестами:
 * потянуть шторку, нажать плитку.
 *
 * ГЛАВНАЯ ТОНКОСТЬ, ОПРЕДЕЛИВШАЯ УСТРОЙСТВО КЛАССА. Шторку обязательно надо
 * закрыть ДО начала чтения: пока она открыта, активное окно — это она, и
 * служба доступности прочитает саму шторку вместо приложения под ней.
 * Публичного «закрой панель» в API нет; закрытие происходит как побочный
 * эффект `startActivityAndCollapse`. Поэтому плитка запускает не сервис
 * напрямую, а невидимую активность-переходник (LaunchActivity), которая уже
 * поднимает сервис и сразу закрывается.
 *
 * И вторая тонкость: с Android 14 версия `startActivityAndCollapse(Intent)`
 * запрещена и бросает исключение — обязателен вариант с PendingIntent.
 */
public class ScanTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile t = getQsTile();
        if (t == null) return;
        boolean ready = ScanAccessibilityService.get() != null
                && Settings.canDrawOverlays(this);
        // Недоступная плитка выглядит серой — это честный сигнал «не настроено».
        t.setState(ready ? Tile.STATE_INACTIVE : Tile.STATE_UNAVAILABLE);
        t.setLabel(getString(R.string.tile_label));
        t.updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();

        // Не настроено — ведём в настройку, а не молча ничего не делаем.
        boolean ready = ScanAccessibilityService.get() != null
                && Settings.canDrawOverlays(this);

        Intent target = ready
                ? new Intent(this, LaunchActivity.class)
                : new Intent(this, MainActivity.class);
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pi = PendingIntent.getActivity(
                this, 0, target,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(pi);
        } else {
            collapseLegacy(target);
        }
    }

    /**
     * Ветка для Android 13 и ниже. Вынесена в отдельный метод с подавлением
     * предупреждения: вариант с Intent там ЕДИНСТВЕННЫЙ рабочий (версии с
     * PendingIntent ещё нет), а на 14+ он бросает исключение — поэтому
     * условие по версии выше обязательно.
     */
    @SuppressWarnings("deprecation")
    private void collapseLegacy(Intent target) {
        startActivityAndCollapse(target);
    }
}
