package com.screentextscan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Накопитель прочитанного текста.
 *
 * ЗАЧЕМ ОТДЕЛЬНЫЙ КЛАСС. Накопление — не «добавить в список»: при прокрутке
 * одна и та же строка приходит десятки раз, иногда с другим переносом, а
 * порядок на экране меняется. Здесь собраны все решения на этот счёт, и
 * класс не зависит от Android — его логику можно проверить обычным тестом.
 *
 * ПОРЯДОК ВЫВОДА — порядок ПЕРВОГО появления строки, а не текущий экранный.
 * Причина: пока человек листает вниз, экранный порядок постоянно
 * перестраивается, а порядок появления совпадает с порядком чтения.
 */
public class TextAccumulator {

    /**
     * Строки, которые есть на экране всегда и текстом не являются: часы,
     * заряд, скорость сети, индикаторы связи. Они приходят каждый опрос и
     * раздувают результат.
     */
    private static final Pattern JUNK = Pattern.compile(
            "^(?:\\d{1,2}:\\d{2}(?::\\d{2})?"          // 16:15:03
                    + "|\\d{1,3}\\s*%"                  // 29 %
                    + "|\\d+[.,]?\\d*\\s*(?:КБ/с|МБ/с|KB/s|MB/s)"
                    + "|[A-Z0-9]{1,4}G?\\+?"            // 4G+, H, LTE
                    + ")$");

    private final Set<String> seen = new HashSet<>();
    private final List<String> order = new ArrayList<>();

    /**
     * Ключ для сравнения строк.
     *
     * Схлопываем пробелы и срезаем концевую пунктуацию: при прокрутке та же
     * строка может прийти с другим переносом или без точки. Регистр НЕ
     * трогаем — «ОК» и «ок» бывают разными кнопками.
     */
    static String normalize(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        int end = t.length();
        while (end > 0 && " .,;:!?·•—-".indexOf(t.charAt(end - 1)) >= 0) end--;
        int start = 0;
        while (start < end && t.charAt(start) == ' ') start++;
        return t.substring(start, end);
    }

    static boolean isJunk(String s) {
        return JUNK.matcher(s.trim()).matches();
    }

    /**
     * Добавить строки одного опроса.
     *
     * @return сколько строк оказались новыми — по этому числу решается,
     *         продолжать ли чтение
     */
    public int addAll(List<String> texts) {
        int added = 0;
        for (String t : texts) {
            if (t == null) continue;
            if (isJunk(t)) continue;
            String key = normalize(t);
            if (key.isEmpty() || seen.contains(key)) continue;
            seen.add(key);
            order.add(t.trim());
            added++;
        }
        return added;
    }

    public int size() {
        return order.size();
    }

    public List<String> lines() {
        return new ArrayList<>(order);
    }

    public String text() {
        return String.join("\n", order);
    }

    public void clear() {
        seen.clear();
        order.clear();
    }
}
