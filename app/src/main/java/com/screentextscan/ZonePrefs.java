package com.screentextscan;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;

/**
 * Запоминание выбранной зоны.
 *
 * ЗАЧЕМ. Люди читают одно и то же место: тело статьи, окно чата, список
 * настроек. Заставлять обводить область заново каждый раз — та же ошибка,
 * что была с невозможностью подправить рамку, только повторяющаяся вечно.
 * Поэтому прошлый выбор предлагается как начальная рамка.
 *
 * ХРАНИМ ДОЛИ, А НЕ ПИКСЕЛИ. Пиксельная зона теряет смысл при повороте
 * экрана и при переносе на другое устройство: «от 400 до 1200 по вертикали»
 * на другом экране означает другое место. Доли переносятся корректно.
 */
final class ZonePrefs {

    private static final String FILE = "zone";
    private static final String K_L = "l", K_T = "t", K_R = "r", K_B = "b";

    private ZonePrefs() {
    }

    static void save(Context c, Rect zone, int screenW, int screenH) {
        if (zone == null || screenW <= 0 || screenH <= 0) return;
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        p.edit()
                .putFloat(K_L, (float) zone.left / screenW)
                .putFloat(K_T, (float) zone.top / screenH)
                .putFloat(K_R, (float) zone.right / screenW)
                .putFloat(K_B, (float) zone.bottom / screenH)
                .apply();
    }

    static Rect load(Context c, int screenW, int screenH) {
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (!p.contains(K_L)) return null;
        Rect r = new Rect(
                Math.round(p.getFloat(K_L, 0) * screenW),
                Math.round(p.getFloat(K_T, 0) * screenH),
                Math.round(p.getFloat(K_R, 0) * screenW),
                Math.round(p.getFloat(K_B, 0) * screenH));
        /*
         * Проверка на вырожденность обязательна: доли могли сохраниться в
         * другой ориентации, и после пересчёта рамка выйдет слишком узкой.
         * Тогда лучше не предлагать ничего, чем предлагать негодное.
         */
        if (r.width() < 40 || r.height() < 40) return null;
        return ZoneGeometry.normalize(r, new Rect(0, 0, screenW, screenH), 40);
    }
}
