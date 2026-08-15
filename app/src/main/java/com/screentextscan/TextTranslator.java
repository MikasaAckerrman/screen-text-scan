package com.screentextscan;

import android.content.Context;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Перевод прочитанного текста.
 *
 * ПОЧЕМУ ML Kit, А НЕ СЕТЕВОЙ СЕРВИС. Приложение читает содержимое чужих
 * экранов — это личные данные пользователя. Отправлять их на сторонний
 * сервер только ради перевода недопустимо, поэтому берём модель, которая
 * работает НА УСТРОЙСТВЕ. Сеть нужна один раз, чтобы скачать пару языков
 * (около 30 МБ), дальше перевод идёт без интернета.
 *
 * ПОСТРОЧНО, А НЕ ЦЕЛИКОМ. Текст с экрана — это список строк: пункты меню,
 * подписи, ячейки таблицы. Склеенные в абзац, они дают модели ложный
 * контекст, и она начинает связывать несвязанное. Плюс построчный перевод
 * сохраняет соответствие оригинала и перевода, а значит человек видит, где
 * перевод сомнителен.
 *
 * ЯЗЫК ИСТОЧНИКА ОПРЕДЕЛЯЕМ САМИ, по алфавиту. Отдельный определитель языка
 * ML Kit — ещё одна зависимость и ещё одна модель к загрузке, а нам надо
 * различить всего два случая: кириллица или латиница.
 */
public class TextTranslator {

    /** Готовый результат: строка и её перевод рядом. */
    public static class Pair {
        public final String src;
        public final String dst;

        Pair(String src, String dst) {
            this.src = src;
            this.dst = dst;
        }
    }

    public interface Callback {
        void onProgress(int done, int total);

        void onDone(List<Pair> pairs);

        void onError(String message);
    }

    private final Context ctx;
    private Translator translator;
    private String from, to;

    public TextTranslator(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /**
     * Определить направление по алфавиту — см. TextTranslatorLogic.
     * Метод оставлен для удобства вызова, вся логика и её обоснование там,
     * потому что она проверяется тестом без устройства.
     */
    public static boolean looksRussian(String text) {
        return TextTranslatorLogic.looksRussian(text);
    }

    /** Строку переводить бессмысленно? См. TextTranslatorLogic. */
    static boolean isUntranslatable(String s) {
        return TextTranslatorLogic.isUntranslatable(s);
    }

    /**
     * Скачать модель, если нужно, и перевести все строки.
     *
     * ВАЖНО ПРО ПОТОК: метод блокирующий, вызывать только из фонового.
     * Task.await внутри — намеренно: переводим последовательно, потому что
     * ML Kit всё равно исполняет запросы по очереди, а последовательный код
     * читается втрое проще, чем цепочка из сотни колбэков.
     */
    public void translateAll(List<String> lines, boolean wifiOnly, Callback cb) {
        try {
            String joined = String.join(" ", lines);
            boolean ru = looksRussian(joined);
            String src = ru ? TranslateLanguage.RUSSIAN : TranslateLanguage.ENGLISH;
            String dst = ru ? TranslateLanguage.ENGLISH : TranslateLanguage.RUSSIAN;

            ensureTranslator(src, dst, wifiOnly);

            List<Pair> out = new ArrayList<>(lines.size());
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                out.add(new Pair(line, translateLine(line)));
                cb.onProgress(i + 1, lines.size());
            }
            cb.onDone(out);
        } catch (ExecutionException e) {
            /*
             * Самая частая причина здесь — модель не скачалась: нет сети или
             * пользователь ограничил загрузку сетью Wi-Fi, а Wi-Fi нет.
             * Сообщение должно говорить, что делать, а не «ошибка -1».
             */
            Throwable c = e.getCause() == null ? e : e.getCause();
            cb.onError("Не удалось перевести: " + describe(c));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cb.onError("Перевод прерван");
        } catch (RuntimeException e) {
            cb.onError("Не удалось перевести: " + describe(e));
        }
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) return t.getClass().getSimpleName();
        if (m.contains("download") || m.contains("Download") || m.contains("network")) {
            return "нужно скачать языковую модель, включите интернет";
        }
        return m;
    }

    private void ensureTranslator(String src, String dst, boolean wifiOnly)
            throws ExecutionException, InterruptedException {
        if (translator != null && src.equals(from) && dst.equals(to)) return;
        close();
        from = src;
        to = dst;
        translator = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(src)
                .setTargetLanguage(dst)
                .build());
        DownloadConditions.Builder cond = new DownloadConditions.Builder();
        if (wifiOnly) cond.requireWifi();
        Tasks.await(translator.downloadModelIfNeeded(cond.build()));
    }

    /**
     * Перевести одну строку.
     *
     * ПУСТАЯ СТРОКА — ЭТО НЕ ОШИБКА. Вызывающий подменяет пустыми те строки,
     * которые пользователь вычеркнул: так сохраняется соответствие номеров,
     * а время на перевод мусора не тратится.
     */
    private String translateLine(String line) throws ExecutionException, InterruptedException {
        if (line.isEmpty() || isUntranslatable(line)) return line;
        String res = Tasks.await(translator.translate(line));
        return res == null ? line : res;
    }

    /** Освободить модель. Обязательно: она держит несколько десятков МБ. */
    public void close() {
        if (translator != null) {
            translator.close();
            translator = null;
        }
    }

    /** Направление перевода для показа пользователю, либо null до первого перевода. */
    public String direction() {
        if (from == null) return null;
        return from + " → " + to;
    }
}
