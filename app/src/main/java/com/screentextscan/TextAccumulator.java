package com.screentextscan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Накопитель прочитанного текста.
 *
 * ЗАЧЕМ ОТДЕЛЬНЫЙ КЛАСС. Накопление — не «добавить в список»: при прокрутке
 * одна и та же строка приходит десятки раз, иногда обрезанной, иногда с
 * другим переносом, а порядок на экране меняется. Здесь собраны все решения
 * на этот счёт, и класс не зависит от Android — его логика проверяется
 * обычным javac без устройства.
 *
 * ПОРЯДОК ВЫВОДА — порядок ПЕРВОГО появления строки, а не текущий экранный.
 * Причина: пока человек листает вниз, экранный порядок постоянно
 * перестраивается, а порядок появления совпадает с порядком чтения.
 *
 * ЧЕТЫРЕ УРОВНЯ ЗАЩИТЫ ОТ ПОВТОРОВ, каждый закрывает свой случай:
 *   1. точное совпадение — строка не менялась между опросами;
 *   2. совпадение после схлопывания пробелов и среза концевой пунктуации —
 *      та же надпись пришла с другим переносом;
 *   3. НАРАЩИВАНИЕ: новая строка содержит старую как начало или конец —
 *      это дорисованная строка, которую в прошлый опрос застали
 *      обрезанной. Старая версия ЗАМЕНЯЕТСЯ, место в порядке сохраняется;
 *   4. вложение: новая строка целиком внутри уже сохранённой — отбрасываем.
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
                    + "|\\d+[.,]?\\d*\\s*(?:КБ/с|МБ/с|KB/s|MB/s|Б/с|B/s)"
                    + "|[A-Z0-9]{1,4}G?\\+?"            // 4G+, H, LTE
                    + ")$");

    /**
     * Ниже этой длины наращивание НЕ применяем. Короткие строки слишком
     * часто оказываются началом чего-то другого: «Да» — начало «Дальше», и
     * без порога кнопка «Да» исчезала бы из результата, поглощённая соседним
     * словом.
     */
    private static final int GROW_MIN_LEN = 12;

    /** ключ → индекс в order. LinkedHashSet не подходит: нужна замена по месту. */
    private final Map<String, Integer> index = new HashMap<>();
    private final List<String> order = new ArrayList<>();
    /** Вычеркнутые пользователем позиции. Не удаляем — чтобы можно было вернуть. */
    private final Set<Integer> dropped = new LinkedHashSet<>();

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
        while (end > 0 && " .,;:!?·•—-…".indexOf(t.charAt(end - 1)) >= 0) end--;
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
     *         дочитано ли до конца
     */
    public int addAll(List<String> texts) {
        int added = 0;
        for (String t : texts) {
            if (t == null) continue;
            if (isJunk(t)) continue;
            String key = normalize(t);
            if (key.isEmpty()) continue;

            if (index.containsKey(key)) continue;         // уровень 1–2

            int grown = findGrowable(key);
            if (grown >= 0) {                              // уровень 3
                String oldKey = normalize(order.get(grown));
                index.remove(oldKey);
                order.set(grown, t.trim());
                index.put(key, grown);
                continue;                                  // не «новая», а уточнённая
            }
            if (isContainedInExisting(key)) continue;      // уровень 4

            index.put(key, order.size());
            order.add(t.trim());
            added++;
        }
        return added;
    }

    /**
     * Найти сохранённую строку, которую новая продолжает.
     *
     * ПОЧЕМУ ТОЛЬКО НАЧАЛО ИЛИ КОНЕЦ, А НЕ ЛЮБОЕ ВХОЖДЕНИЕ. При прокрутке
     * строка дорисовывается с края: «Использовался при созда» → полностью.
     * Совпадение же в середине означает другую строку, случайно содержащую
     * ту же фразу, — такие склеивать нельзя.
     */
    private int findGrowable(String newKey) {
        if (newKey.length() < GROW_MIN_LEN) return -1;
        for (int i = 0; i < order.size(); i++) {
            String old = normalize(order.get(i));
            if (old.length() < GROW_MIN_LEN) continue;
            if (old.length() >= newKey.length()) continue;
            if (newKey.startsWith(old) || newKey.endsWith(old)) return i;
        }
        return -1;
    }

    /** Новая строка — обрезок уже сохранённой? Тогда она не нужна. */
    private boolean isContainedInExisting(String newKey) {
        if (newKey.length() < GROW_MIN_LEN) return false;
        for (String s : order) {
            String old = normalize(s);
            if (old.length() <= newKey.length()) continue;
            if (old.startsWith(newKey) || old.endsWith(newKey)) return true;
        }
        return false;
    }

    /* ==================================================================
       Правка результата.

       ЗАЧЕМ ОНА ЕСТЬ. Служба доступности отдаёт то, что есть в дереве, а
       там бывает мусор: подпись невидимой кнопки, дубль заголовка, обрывок.
       Заставлять человека вычищать это вручную в другом приложении —
       перекладывать нашу работу на него.

       Вычеркнутые строки НЕ удаляются из списка: пользователь должен иметь
       возможность вернуть строку, если вычеркнул по ошибке.
       ================================================================== */

    public void drop(int i) {
        if (i >= 0 && i < order.size()) dropped.add(i);
    }

    public void restore(int i) {
        dropped.remove(i);
    }

    public boolean isDropped(int i) {
        return dropped.contains(i);
    }

    public void restoreAll() {
        dropped.clear();
    }

    /** Заменить текст строки — для правки опечатки распознавания. */
    public void edit(int i, String text) {
        if (i < 0 || i >= order.size() || text == null) return;
        String oldKey = normalize(order.get(i));
        index.remove(oldKey);
        order.set(i, text.trim());
        String newKey = normalize(text);
        if (!newKey.isEmpty()) index.put(newKey, i);
    }

    /** Все строки, включая вычеркнутые, в порядке появления. */
    public List<String> lines() {
        return new ArrayList<>(order);
    }

    /** Номера вычеркнутых строк. */
    public Set<Integer> droppedIndices() {
        return Collections.unmodifiableSet(dropped);
    }

    public int size() {
        return order.size();
    }

    /** Сколько строк попадёт в результат. */
    public int keptSize() {
        return order.size() - dropped.size();
    }

    /** Итоговый текст: без вычеркнутых. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < order.size(); i++) {
            if (dropped.contains(i)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(order.get(i));
        }
        return sb.toString();
    }

    public void clear() {
        index.clear();
        order.clear();
        dropped.clear();
    }
}
