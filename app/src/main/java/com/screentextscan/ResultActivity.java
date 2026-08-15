package com.screentextscan;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Экран результата: правка, перевод, копирование.
 *
 * ЗАЧЕМ ОН НУЖЕН — прямой ответ на «если что-то скопируется с ошибкой, как
 * это исправить». Раньше исправить было нельзя никак: текст уезжал в буфер
 * как есть, и чистить его приходилось в другом приложении. Теперь между
 * чтением и копированием есть шаг, на котором:
 *
 *   • каждую строку можно вычеркнуть — она останется видимой, но в буфер
 *     не попадёт. Именно вычеркнуть, а не удалить: вернуть тоже надо уметь;
 *   • любую строку можно исправить прямо на месте;
 *   • всё вместе можно перевести, не выходя из приложения.
 *
 * ПОЧЕМУ СТРОКИ, А НЕ ОДНО БОЛЬШОЕ ПОЛЕ. В одном поле правка удобнее, но
 * теряется соответствие: непонятно, где кончается одна прочитанная строка и
 * начинается другая, а после перевода это соответствие — единственный
 * способ проверить перевод. Поэтому список.
 *
 * Данные передаются через статическое поле, а не через Intent: текст с
 * экрана легко перевалит за лимит Binder-транзакции (около 1 МБ), и
 * приложение упадёт на большом документе. Ссылку обнуляем в onDestroy.
 */
public class ResultActivity extends Activity {

    /**
     * Накопитель, переданный сервисом.
     *
     * ПОЧЕМУ СТАТИКА, А НЕ Intent: текст с экрана легко перевалит за лимит
     * Binder-транзакции (около 1 МБ), и приложение упало бы на большом
     * документе.
     *
     * ПОЧЕМУ НЕ ОБНУЛЯЕТСЯ В onDestroy БЕЗУСЛОВНО — ошибка, которую я
     * поймал разбором поворота экрана. При повороте система уничтожает и
     * пересоздаёт активность; если обнулить поле в onDestroy, пересозданная
     * копия найдёт null и закроется сама, потеряв весь прочитанный текст.
     * Поэтому очищаем только при настоящем закрытии — см. onDestroy.
     */
    static TextAccumulator pending;

    /**
     * Перевод тоже переживает поворот. Иначе после поворота исчезли бы все
     * переводы, и человек нажимал бы «Перевести» второй раз, снова ожидая.
     */
    static List<TextTranslator.Pair> pendingTranslation;

    private TextAccumulator acc;
    private TextTranslator translator;
    private LinearLayout list;
    private TextView counter;
    private CheckBox wifiOnly;
    private List<Pair> rows = new ArrayList<>();
    private List<TextTranslator.Pair> translated;

    /** Связка «строка результата → её элементы на экране». */
    private static class Pair {
        int index;
        TextView view;
        TextView translation;
        Button dropBtn;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        acc = pending;
        if (acc == null || acc.size() == 0) {
            Toast.makeText(this, "Нечего показывать", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        // Перевод, сделанный до поворота экрана, восстанавливаем.
        translated = pendingTranslation;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0B0D);

        root.addView(buildHeader());

        ScrollView sc = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(4), dp(12), dp(12));
        sc.addView(list);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(buildFooter());
        setContentView(root);

        rebuild();
    }

    @Override
    protected void onDestroy() {
        if (translator != null) translator.close();
        /*
         * Чистим статику ТОЛЬКО при настоящем закрытии. isFinishing() как
         * раз и различает два случая: пользователь ушёл с экрана (true) или
         * система пересоздаёт активность из-за поворота (false). Без этой
         * проверки поворот терял весь прочитанный текст.
         */
        if (isFinishing()) {
            if (pending == acc) pending = null;
            pendingTranslation = null;
        } else {
            pendingTranslation = translated;
        }
        super.onDestroy();
    }

    /* ================= шапка ================= */

    private View buildHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(dp(16), dp(16), dp(16), dp(8));

        TextView title = new TextView(this);
        title.setText("Прочитанный текст");
        title.setTextSize(20f);
        title.setTextColor(Color.WHITE);
        h.addView(title);

        counter = new TextView(this);
        counter.setTextSize(13f);
        counter.setTextColor(0xFF9A9AA2);
        counter.setPadding(0, dp(4), 0, 0);
        h.addView(counter);

        TextView hintView = new TextView(this);
        hintView.setText("Нажмите на строку, чтобы исправить. «×» — не копировать её.");
        hintView.setTextSize(12f);
        hintView.setTextColor(0xFF6E6E76);
        hintView.setPadding(0, dp(6), 0, 0);
        h.addView(hintView);

        return h;
    }

    /* ================= список строк ================= */

    private void rebuild() {
        list.removeAllViews();
        rows.clear();
        for (int i = 0; i < acc.size(); i++) {
            list.addView(buildRow(i));
        }
        updateCounter();
    }

    private View buildRow(final int i) {
        final boolean dropped = acc.isDropped(i);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(10), dp(9), dp(6), dp(9));
        row.setBackgroundColor(0xFF15151A);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(6);
        row.setLayoutParams(rlp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView tv = new TextView(this);
        tv.setText(acc.lines().get(i));
        tv.setTextSize(14f);
        tv.setTextColor(dropped ? 0xFF5A5A62 : Color.WHITE);
        if (dropped) {
            // Зачёркиванием, а не удалением: видно, что именно исключено.
            tv.setPaintFlags(tv.getPaintFlags()
                    | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        }
        tv.setOnClickListener(v -> promptEdit(i));
        texts.addView(tv);

        TextView tr = new TextView(this);
        tr.setTextSize(13f);
        tr.setTextColor(0xFF7FB8FF);
        tr.setPadding(0, dp(3), 0, 0);
        tr.setVisibility(View.GONE);
        if (translated != null && i < translated.size()) {
            String d = translated.get(i).dst;
            if (d != null && !d.equals(acc.lines().get(i))) {
                tr.setText(d);
                tr.setVisibility(View.VISIBLE);
            }
        }
        texts.addView(tr);
        row.addView(texts);

        Button drop = new Button(this);
        drop.setText(dropped ? "↺" : "×");
        drop.setTextSize(16f);
        drop.setAllCaps(false);
        drop.setBackgroundColor(0x00000000);
        drop.setTextColor(dropped ? 0xFF30D158 : 0xFFFF6B6B);
        drop.setPadding(0, 0, 0, 0);
        drop.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(40)));
        drop.setOnClickListener(v -> {
            if (acc.isDropped(i)) acc.restore(i);
            else acc.drop(i);
            rebuild();
        });
        row.addView(drop);

        Pair p = new Pair();
        p.index = i;
        p.view = tv;
        p.translation = tr;
        p.dropBtn = drop;
        rows.add(p);
        return row;
    }

    /**
     * Правка строки. Диалог с полем, а не редактирование на месте: строка
     * может быть длинной, и править её в узкой ячейке неудобно.
     */
    private void promptEdit(final int i) {
        final EditText input = new EditText(this);
        input.setText(acc.lines().get(i));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setTextColor(Color.WHITE);
        input.setSelectAllOnFocus(true);

        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Исправить строку")
                .setView(input)
                .setPositiveButton("Сохранить", (d, w) -> {
                    acc.edit(i, input.getText().toString());
                    rebuild();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void updateCounter() {
        int kept = acc.keptSize(), all = acc.size();
        String s = "В буфер пойдёт строк: " + kept;
        if (kept != all) s += " (исключено " + (all - kept) + ")";
        if (translator != null && translator.direction() != null) {
            s += " · перевод " + translator.direction();
        }
        counter.setText(s);
    }

    /* ================= низ: действия ================= */

    private View buildFooter() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(12), dp(8), dp(12), dp(16));
        f.setBackgroundColor(0xFF101014);

        wifiOnly = new CheckBox(this);
        wifiOnly.setText("Скачивать языковую модель только по Wi-Fi");
        wifiOnly.setTextSize(12f);
        wifiOnly.setTextColor(0xFF9A9AA2);
        wifiOnly.setChecked(true);
        f.addView(wifiOnly);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        Button translate = new Button(this);
        translate.setText("Перевести");
        translate.setAllCaps(false);
        translate.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        translate.setOnClickListener(v -> doTranslate(translate));
        btns.addView(translate);

        Button copy = new Button(this);
        copy.setText("Копировать");
        copy.setAllCaps(false);
        copy.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        copy.setOnClickListener(v -> doCopy(false));
        btns.addView(copy);

        f.addView(btns);

        Button copyTr = new Button(this);
        copyTr.setText("Копировать перевод");
        copyTr.setAllCaps(false);
        copyTr.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        copyTr.setOnClickListener(v -> doCopy(true));
        f.addView(copyTr);

        return f;
    }

    private void doTranslate(final Button btn) {
        if (translator == null) translator = new TextTranslator(this);
        btn.setEnabled(false);
        btn.setText("Перевод…");

        /*
         * Переводим ТОЛЬКО оставленные строки: вычеркнутые пользователь уже
         * признал мусором, и тратить на них время загрузки и перевода
         * бессмысленно. Но соответствие индексов надо сохранить, иначе
         * переводы съедут относительно строк — поэтому передаём полный
         * список, а вычеркнутые подменяем пустыми.
         */
        final List<String> all = acc.lines();
        final List<String> forTranslate = new ArrayList<>(all.size());
        for (int i = 0; i < all.size(); i++) {
            forTranslate.add(acc.isDropped(i) ? "" : all.get(i));
        }
        final boolean wifi = wifiOnly.isChecked();

        /*
         * Отдельный поток обязателен: Tasks.await блокирует, и на главном
         * потоке приложение зависло бы на всё время загрузки модели
         * (десятки МБ по мобильной сети — это минуты).
         */
        new Thread(() -> translator.translateAll(forTranslate, wifi, new TextTranslator.Callback() {
            @Override
            public void onProgress(int done, int total) {
                runOnUiThread(() -> btn.setText("Перевод " + done + "/" + total));
            }

            @Override
            public void onDone(List<TextTranslator.Pair> pairs) {
                runOnUiThread(() -> {
                    translated = pairs;
                    btn.setEnabled(true);
                    btn.setText("Перевести заново");
                    rebuild();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    btn.setEnabled(true);
                    btn.setText("Перевести");
                    Toast.makeText(ResultActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        })).start();
    }

    private void doCopy(boolean wantTranslation) {
        String text;
        if (wantTranslation) {
            if (translated == null) {
                Toast.makeText(this, "Сначала нажмите «Перевести»", Toast.LENGTH_SHORT).show();
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < translated.size(); i++) {
                if (acc.isDropped(i)) continue;      // исключённые не переводим и не копируем
                if (sb.length() > 0) sb.append('\n');
                sb.append(translated.get(i).dst);
            }
            text = sb.toString();
        } else {
            text = acc.text();
        }

        if (text.isEmpty()) {
            Toast.makeText(this, "Нечего копировать", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Текст с экрана", text));
        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show();
        finish();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
