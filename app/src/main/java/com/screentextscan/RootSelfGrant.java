package com.screentextscan;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Авто-выдача и самолечение ВСЕХ разрешений приложения.
 *
 * ПРАВИЛО ПОЛЬЗОВАТЕЛЯ (09.10): «постоянно приходится выдавать
 * разрешение на специальные возможности, нужно чтобы даже после
 * перезагрузки сохранялось, учитывай что у нас всегда работает
 * Shizuku». OriginOS сбрасывает строку доступности после загрузки и
 * обновлений — приложение восстанавливает её само, по трём каналам:
 *
 *   1. ПРЯМАЯ запись (WRITE_SECURE_SETTINGS, выданный один раз root'ом
 *      или shell'ом — ПЕРЕЖИВАЕТ перезагрузки, выдаётся мгновенно);
 *   2. ROOT su (KernelSU: allowlist живёт в /data/adb/ksu, вечен);
 *   3. SHIZUKU UserService exec (uid shell, сервер у пользователя
 *      поднимается сам — канал после загрузки, когда root не выдан).
 *
 * Ни один канал не показывает системных диалогов. Вызов — из фона
 * (su exec ~20-300 мс; прямая запись — микросекунды).
 */
public final class RootSelfGrant {

    private static final String PKG = "com.screentextscan";
    private static final String WSS = "android.permission.WRITE_SECURE_SETTINGS";

    /**
     * Собрать СЛЕДУЮЩЕЕ значение строки доступности: наша служба
     * ДОПОЛНЯЕТСЯ к текущим (чужие — clipcells и др. — сохраняются).
     *
     * @return null, если наша служба уже в строке (менять нечего).
     */
    private static String computeA11yNext(Context ctx) {
        ComponentName mine = new ComponentName(PKG,
                "com.screentextscan.ScanAccessibilityService");
        String flat = mine.flattenToString();
        String cur = null;
        try {
            cur = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        } catch (Throwable ignored) { }
        if (cur != null && cur.contains(flat)) return null;
        return (cur == null || cur.isEmpty()) ? flat : cur + ":" + flat;
    }

    /** Есть ли у приложения прямое право писать secure-настройки. */
    private static boolean hasDirectWrite(Context ctx) {
        return ctx.checkSelfPermission(WSS) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Проверить и восстановить всё. Тихая: неудача канала лишь
     * переключает на следующий. Вызывать из фонового потока.
     *
     * @return true, если доступность включена И оверлей разрешён.
     */
    public static boolean ensureAll(Context ctx) {
        boolean a11yOk = Permissions.isAccessibilityEnabled(ctx);

        /*
         * КАНАЛ 1 — прямая запись: мгновенно, без процессов. Право
         * выдаётся ОДИН раз (root/shell) и переживает перезагрузки.
         */
        String next = computeA11yNext(ctx);
        if (next != null && hasDirectWrite(ctx)) {
            try {
                Settings.Secure.putString(ctx.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
                Settings.Secure.putInt(ctx.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                a11yOk = true;
            } catch (Throwable ignored) { }
        }

        /*
         * КАНАЛЫ 2/3 — команды shell: уведомления (pm grant), оверлей
         * (appops), прямой grant WRITE_SECURE_SETTINGS (один раз —
         * дальше работает канал 1), доступность (если канал 1 не смог).
         */
        List<String> cmds = new ArrayList<>();
        cmds.add("pm grant " + PKG + " android.permission.POST_NOTIFICATIONS");
        if (!hasDirectWrite(ctx)) {
            cmds.add("pm grant " + PKG + " " + WSS);
        }
        cmds.add("appops set " + PKG + " SYSTEM_ALERT_WINDOW allow");
        if (!a11yOk && next != null) {
            cmds.add("settings put secure "
                    + Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                    + " '" + next + "'");
            cmds.add("settings put secure accessibility_enabled 1");
        }
        StringBuilder sb = new StringBuilder();
        for (String c : cmds) sb.append(c).append("; ");
        String script = sb.toString();

        boolean shellRan = false;
        // 2) ROOT (KernelSU — allowlist переживает перезагрузку).
        if (RootCapture.hasRoot()) {
            shellRan = RootCapture.execRaw(script);
        }
        // 3) SHIZUKU (сервер пользователя поднимается сам).
        if (!shellRan) {
            String r = ShizukuCapture.exec(script);
            shellRan = r != null;
            if (shellRan && !hasDirectWrite(ctx)) {
                // grant через shell уже отработал — перечитать состояние.
            }
        }
        if (shellRan && !a11yOk && next != null && !hasDirectWrite(ctx)) {
            // Канал shell только что дописал строку — перечитать.
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) { }
        }

        boolean ok = Permissions.isAccessibilityEnabled(ctx)
                && Settings.canDrawOverlays(ctx);
        android.util.Log.d("ScreenTextScan", "selfgrant: a11y=" + ok
                + " overlay=" + Settings.canDrawOverlays(ctx)
                + " wss=" + hasDirectWrite(ctx)
                + " shell=" + shellRan);
        return ok;
    }
}
