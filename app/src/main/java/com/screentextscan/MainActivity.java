package com.screentextscan;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
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

    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

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

        root.addView(step("3. Плитка в быстрых настройках",
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
    }

    /** Состояние обоих разрешений одной строкой — сразу видно, чего не хватает. */
    private void refresh() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean a11y = ScanAccessibilityService.get() != null;
        String s = (overlay ? "✓" : "✗") + " наложение поверх приложений\n"
                + (a11y ? "✓" : "✗") + " служба доступности";
        if (overlay && a11y) s += "\n\nВсё готово.";
        status.setText(s);
        status.setTextColor(overlay && a11y
                ? Color.parseColor("#30D158") : Color.parseColor("#FF9F0A"));
    }

    private void openOverlaySettings() {
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
    }

    private void openAccessibilitySettings() {
        // Прямой переход к своей странице невозможен: системный экран общий.
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void startScanNow() {
        if (!Settings.canDrawOverlays(this) || ScanAccessibilityService.get() == null) {
            refresh();
            return;
        }
        startForegroundService(new Intent(this, OverlayService.class)
                .setAction(OverlayService.ACTION_START));
        /*
         * Себя убираем с экрана: читать надо чужое приложение, а не наше.
         * moveTaskToBack вместо finish() — чтобы возврат «назад» привёл
         * пользователя туда, откуда он пришёл.
         */
        moveTaskToBack(true);
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
