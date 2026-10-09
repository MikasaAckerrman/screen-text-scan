package com.screentextscan;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Авто-выдача ВСЕХ разрешений приложения через root (KernelSU).
 *
 * ПРАВИЛО ПОЛЬЗОВАТЕЛЯ (09.10): «при нажатии все разрешения выдавались
 * автоматически, учитывая что у нас есть рут, и чтобы не приходилось
 * заходить в настройки приложения».
 *
 * ГРАНТ KernelSU — единственное ручное действие в жизни приложения
 * (менеджер → Superuser; su монтируется в namespace процесса). Дальше
 * приложение САМО чинит свои разрешения при каждом запуске: ни диалогов,
 * ни экранов настроек, ни «перебрасывает в приложение».
 *
 * Что именно (и почему безопасно):
 *  - POST_NOTIFICATIONS: pm grant (runtime);
 *  - SYSTEM_ALERT_WINDOW: appops allow (специальное, через настройки
 *    не выдаётся программно без рута);
 *  - доступность: строка enabled_accessibility_services ДОПОЛНЯЕТСЯ
 *    нашей службой (чужие сохраняются!), accessibility_enabled=1 —
 *    ровно тот ребинд, которым мы лечили Crashed-состояние вручную.
 *
 * Вызывается с фонового потока (su exec ~50-300 мс с кешем гранта).
 */
public final class RootSelfGrant {

    private static final String PKG = "com.screentextscan";

    /**
     * Проверить и выдать всё пропущенное. Тихая: любая неудача
     * отдельного шага не мешает остальным — вызывающий после этого
     * просто делает обычную проверку (и, если что, ведёт в настройки,
     * как раньше).
     *
     * @return true, если ВСЕ ключевые права в порядке после вызова.
     */
    public static boolean ensureAll(Context ctx) {
        if (!RootCapture.hasRoot()) return false;
        List<String> cmds = new ArrayList<>();
        // Уведомления (runtime): grant безопасен повторно.
        cmds.add("pm grant " + PKG + " android.permission.POST_NOTIFICATIONS");
        // Оверлей: специальное разрешение — только appops.
        cmds.add("appops set " + PKG + " SYSTEM_ALERT_WINDOW allow");
        // Доступность: дописать нашу службу, ЧУЖИЕ не трогать.
        ComponentName mine = new ComponentName(PKG,
                "com.screentextscan.ScanAccessibilityService");
        String flat = mine.flattenToString();
        try {
            String cur = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null || !cur.contains(flat)) {
                String next = (cur == null || cur.isEmpty())
                        ? flat
                        : cur + ":" + flat;
                // Экранирование для shell-строки: кавычки внутри значения.
                cmds.add("settings put secure "
                        + Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                        + " '" + next + "'");
            }
        } catch (Throwable ignored) {
            // чтение настроек не удалось — просто попробуем дописать
            cmds.add("settings put secure "
                    + Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                    + " '" + flat + "'");
        }
        cmds.add("settings put secure accessibility_enabled 1");
        // Выполнить одной su-сессией: меньше exec'ов, атомарнее.
        StringBuilder sb = new StringBuilder();
        for (String c : cmds) sb.append(c).append("; ");
        RootCapture.execRaw(sb.toString());
        boolean ok = Permissions.isAccessibilityEnabled(ctx)
                && Settings.canDrawOverlays(ctx);
        android.util.Log.d("ScreenTextScan", "selfgrant: a11y=" + ok
                + " overlay=" + Settings.canDrawOverlays(ctx));
        return ok;
    }
}
