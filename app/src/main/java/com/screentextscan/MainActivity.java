package com.screentextscan;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Экран первой настройки.
 *
 * ЗАЧЕМ ОН НУЖЕН, ХОТЯ ПОЛЬЗОВАТЕЛЬ ХОТЕЛ «ПЛИТКУ И КНОПКУ». Оба нужных
 * разрешения — ОСОБЫЕ: их нельзя выдать обычным диалогом, только вручную в
 * системных настройках, каждое на своей странице. Без экрана, который
 * показывает, что уже включено, а что нет, человек остаётся с серой плиткой
 * и без объяснений.
 *
 * Интерфейс собран кодом, без файлов разметки: три кнопки и текст состояния
 * не стоят отдельных ресурсов, а держать их в одном месте с логикой проще
 * для чтения.
 */
public class MainActivity extends Activity {

    private static final String STATE_OPENING_ACCESSIBILITY =
            "opening_accessibility_settings";

    /**
     * Флаг на один жизненный цикл Activity. При первом открытии ярлыка
     * отправляем прямо к выключенной службе, но после возврата по Back не
     * зацикливаем пользователя между приложением и системными настройками.
     */
    private boolean autoAccessibilityArmed = true;
    private boolean openingAccessibilitySettings;

    private TextView status;
    private TextView persistenceStatus;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null) {
            openingAccessibilitySettings = b.getBoolean(
                    STATE_OPENING_ACCESSIBILITY, false);
            autoAccessibilityArmed = !openingAccessibilitySettings;
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.parseColor("#0E0E10"));
        scroll.addView(root);

        root.addView(title("Чтение текста с экрана"));
        root.addView(body(
                "Читает текст из любого приложения — не распознаванием картинки, "
                        + "а напрямую из его элементов. Поэтому ошибок распознавания "
                        + "не бывает, а один опрос экрана стоит около 50 мс."));

        status = body("");
        status.setPadding(0, dp(14), 0, dp(14));
        root.addView(status);

        root.addView(step("1. Наложение поверх приложений",
                "Нужно для плавающей кнопки «Стоп» — без этого разрешения "
                        + "её нельзя нарисовать над чужим приложением.",
                "Открыть настройку", v -> openOverlaySettings()));

        root.addView(step("2. Служба доступности",
                "Источник текста. Читает содержимое активного окна по запросу; "
                        + "события не отслеживает, поэтому в простое не расходует заряд.",
                "Открыть настройку", v -> openAccessibilitySettings()));

        persistenceStatus = body("");
        persistenceStatus.setPadding(0, dp(14), 0, 0);
        root.addView(persistenceStatus);

        root.addView(step("3. Защита службы на vivo",
                "OriginOS отключает службы при полной очистке приложения. "
                        + "Один раз включите ScreenTextScan в автозапуске и "
                        + "снимите ограничение батареи.",
                "Открыть автозапуск vivo", v -> openVivoAutostart()));

        root.addView(step("4. Работа без ограничений",
                "Разрешает Android не останавливать защиту службы в фоне.",
                "Разрешить", v -> requestBatteryExemption()));

        root.addView(step("5. Плитка в быстрых настройках",
                "Потяните шторку, нажмите «Изменить» и перетащите плитку "
                        + "«Текст с экрана» к остальным. Дальше чтение запускается "
                        + "двумя жестами.",
                "Проверить сейчас", v -> startScanNow()));

        root.addView(body(
                "Как это работает:\n"
                        + "1. Плитка — начинается выбор зоны.\n"
                        + "2. Обведите область или нажмите «Подобрать». Рамку можно "
                        + "подправить за ручки и перетащить — заново обводить не нужно.\n"
                        + "3. Листайте текст. На кружке видно, сколько строк набрано, "
                        + "а кольцо вокруг заполняется, когда с видимой части взято всё.\n"
                        + "4. Нажмите кружок — он расширится в «Копировать».\n"
                        + "5. Откроется список: строки можно исправить, лишние исключить, "
                        + "всё вместе перевести."));

        root.addView(body(
                "Перевод работает на устройстве: языковая пара скачивается один раз, "
                        + "дальше интернет не нужен. Прочитанный текст никуда не отправляется."));

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        AccessibilityKeepAliveService.start(this);

        if (openingAccessibilitySettings) {
            // Возврат из настроек: показать статус, не отправлять туда снова.
            openingAccessibilitySettings = false;
            autoAccessibilityArmed = false;
            return;
        }
        if (autoAccessibilityArmed
                && (!Permissions.isAccessibilityMasterOn(this)
                || !Permissions.isAccessibilityEnabled(this))) {
            autoAccessibilityArmed = false;
            openAccessibilitySettings();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!openingAccessibilitySettings) {
            // Обычное сворачивание: следующее открытие ярлыка снова помогает.
            autoAccessibilityArmed = true;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_OPENING_ACCESSIBILITY,
                openingAccessibilitySettings);
        super.onSaveInstanceState(outState);
    }

    /** Состояние обоих разрешений одной строкой — сразу видно, чего не хватает. */
    private void refresh() {
        boolean overlay = Permissions.canOverlay(this);
        boolean a11yMaster = Permissions.isAccessibilityMasterOn(this);
        boolean a11y = Permissions.isAccessibilityEnabled(this);
        String s = (overlay ? "✓" : "✗") + " наложение поверх приложений\n"
                + (a11yMaster && a11y ? "✓" : "✗") + " служба доступности";
        if (!a11yMaster) s += " (общий переключатель выключен)";
        if (overlay && a11yMaster && a11y) s += "\n\nВсё готово.";
        status.setText(s);
        status.setTextColor(overlay && a11yMaster && a11y
                ? Color.parseColor("#30D158") : Color.parseColor("#FF9F0A"));

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean unrestricted = pm.isIgnoringBatteryOptimizations(getPackageName());
        persistenceStatus.setText((unrestricted ? "✓" : "✗")
                + " без ограничений батареи\n"
                + "• автозапуск vivo: включите ScreenTextScan вручную");
        persistenceStatus.setTextColor(unrestricted
                ? Color.parseColor("#30D158") : Color.parseColor("#FF9F0A"));
    }

    private void openOverlaySettings() {
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
    }

    private void openAccessibilitySettings() {
        openingAccessibilitySettings = true;
        Intent details = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME,
                        new ComponentName(this, ScanAccessibilityService.class));
        try {
            startActivity(details);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // Не все OEM-прошивки предоставляют страницу конкретной службы.
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }
    }

    private void openVivoAutostart() {
        Intent vivo = new Intent().setComponent(new ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
        Intent iqoo = new Intent().setComponent(new ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"));
        if (tryStart(vivo) || tryStart(iqoo)) return;

        // Новый OriginOS может скрыть фирменную Activity. Карточка приложения
        // остаётся ближайшим рабочим местом для автозапуска и батареи.
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private boolean tryStart(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            return false;
        }
    }

    private void requestBatteryExemption() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void startScanNow() {
        if (!Permissions.canOverlay(this)) {
            openOverlaySettings();
            return;
        }
        if (!Permissions.isAccessibilityMasterOn(this)
                || !Permissions.isAccessibilityEnabled(this)) {
            openAccessibilitySettings();
            return;
        }
        /*
         * Используем тот же переходник, что и плитка. Он ждёт реального
         * подключения службы после холодного запуска процесса, поэтому
         * кнопка не отказывает молча, когда разрешение уже выдано, но
         * ScanAccessibilityService ещё не успела создать свой экземпляр.
         */
        startActivity(new Intent(this, LaunchActivity.class));
    }

    /* ---------- сборка интерфейса ---------- */

    private TextView title(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(22f);
        v.setTextColor(Color.WHITE);
        v.setPadding(0, 0, 0, dp(10));
        return v;
    }

    private TextView body(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(14f);
        v.setLineSpacing(dp(3), 1f);
        v.setTextColor(Color.parseColor("#B0B0B8"));
        return v;
    }

    private View step(String head, String desc, String btn, View.OnClickListener onClick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        box.setBackgroundColor(Color.parseColor("#17171A"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        box.setLayoutParams(lp);

        TextView h = new TextView(this);
        h.setText(head);
        h.setTextSize(16f);
        h.setTextColor(Color.WHITE);
        box.addView(h);

        TextView d = body(desc);
        d.setPadding(0, dp(6), 0, dp(10));
        box.addView(d);

        Button b = new Button(this);
        b.setText(btn);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        b.setGravity(Gravity.CENTER);
        box.addView(b);

        return box;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
