package com.screentextscan;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * Проверка того, включены ли нужные разрешения.
 *
 * ЗДЕСЬ БЫЛА ГЛАВНАЯ ОШИБКА ПЕРВЫХ ВЕРСИЙ. Готовность службы доступности я
 * определял по статической ссылке на её экземпляр:
 *
 *     ScanAccessibilityService.get() != null
 *
 * Это работает только пока процесс приложения жив. А процесс система убивает
 * постоянно — он ей не нужен, приложение ничего не делает. При нажатии на
 * плитку процесс создаётся ЗАНОВО, статическая ссылка в нём пустая, и плитка
 * решала, что служба выключена. Хуже того: она ставила себе состояние
 * STATE_UNAVAILABLE, а такая плитка в Android вообще не нажимается — отсюда
 * «если приложение не запущено, через плитку не вызвать».
 *
 * Правильный источник — системная настройка ENABLED_ACCESSIBILITY_SERVICES.
 * Она живёт в системе, а не в нашем процессе, и отвечает верно независимо от
 * того, запущено ли приложение.
 *
 * ПРО РАЗБОР СТРОКИ. Настройка — это список компонентов через двоеточие:
 *   com.foo/com.foo.SvcA:com.bar/.SvcB
 * Имя класса бывает записано и полностью, и сокращённо с точки. Сравнивать
 * строки целиком нельзя: в одном случае «com.screentextscan/.ScanAccessibilityService»,
 * в другом «com.screentextscan/com.screentextscan.ScanAccessibilityService» —
 * это одно и то же, но текстом не совпадает. Поэтому разбираем через
 * ComponentName.unflattenFromString, который обе формы понимает.
 */
public final class Permissions {

    private Permissions() {
    }

    /** Можно рисовать поверх других приложений (плавающая кнопка). */
    public static boolean canOverlay(Context c) {
        return Settings.canDrawOverlays(c);
    }

    /**
     * Служба доступности включена в системных настройках.
     *
     * Именно «включена в настройках», а не «уже подключилась к процессу»:
     * второе бывает ложно-отрицательным сразу после старта процесса.
     */
    public static boolean isAccessibilityEnabled(Context c) {
        ComponentName mine = new ComponentName(c, ScanAccessibilityService.class);
        String enabled = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;

        for (String part : enabled.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(part.trim());
            if (cn == null) continue;
            if (cn.getPackageName().equals(mine.getPackageName())
                    && cn.getClassName().equals(mine.getClassName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Общий выключатель доступности. Проверяем отдельно: бывает, что служба
     * в списке есть, а доступность выключена целиком — тогда она не работает,
     * и сообщать надо именно об этом, иначе человек ищет проблему не там.
     */
    public static boolean isAccessibilityMasterOn(Context c) {
        /*
         * Используем перегрузку С ДЕФОЛТОМ. Двухаргументная getInt бросает
         * проверяемый SettingNotFoundException — это обнаружила полная
         * локальная компиляция после восстановления ZoneSelectorView.
         * Значение по умолчанию 1 соответствует прежнему намерению:
         * отсутствие настройки не считать запретом.
         */
        return Settings.Secure.getInt(c.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, 1) == 1;
    }

    /** Всё ли готово к чтению. */
    public static boolean ready(Context c) {
        return canOverlay(c) && isAccessibilityEnabled(c);
    }

    /**
     * Служба не только включена, но и уже подключилась к нашему процессу.
     *
     * Нужно ровно в одном месте — перед тем как читать экран. Если система
     * ещё не успела привязать службу, читать нечем, и надо подождать, а не
     * говорить «включите разрешение».
     */
    public static boolean isAccessibilityConnected() {
        return ScanAccessibilityService.get() != null;
    }
}
