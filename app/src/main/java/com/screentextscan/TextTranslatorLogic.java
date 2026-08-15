package com.screentextscan;

/**
 * Решения о том, ЧТО и КУДА переводить. Отдельно от TextTranslator, потому
 * что сам перевод требует ML Kit и устройства, а эти две функции — чистая
 * логика, и ошибаются они тихо:
 *
 *   • неверное направление переводит русский текст на русский, получается
 *     каша, и понять причину по результату почти невозможно;
 *   • пропущенный «×1.30» модель превращает в «х1,30» и портит таблицу
 *     чисел, которую человек как раз и хотел прочитать.
 *
 * Поэтому обе проверяются тестом без устройства.
 */
public final class TextTranslatorLogic {

    private TextTranslatorLogic() {
    }

    /**
     * Порог доли кириллицы, при котором текст считаем русским.
     *
     * 55%, а не 50%: смешанные строки вида «Кэш шейдеров GLSL» должны
     * оставаться русскими, а они дают около 70% кириллицы. Ровно половина
     * встречается у строк, где русское слово соседствует с длинным
     * английским термином, и там надёжнее считать текст английским —
     * перевод на русский нужен чаще и ошибка менее заметна.
     */
    private static final int RU_SHARE = 55;

    /**
     * Минимум букв, ниже которого о языке говорить нельзя. «ОК», «Wi-Fi»,
     * «1.00» — направление по ним не определить, и угадывание вредит.
     */
    private static final int MIN_LETTERS = 3;

    /** Доля букв, ниже которой строка — не текст, а числа и знаки. */
    private static final int MIN_LETTER_SHARE = 40;

    /**
     * Текст на русском?
     *
     * Считаем по алфавиту, а не определителем языка ML Kit: тот тянет ещё
     * одну модель к загрузке, а различить надо всего два случая.
     */
    public static boolean looksRussian(String text) {
        if (text == null) return false;
        int cyr = 0, lat = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch >= 'а' && ch <= 'я') || (ch >= 'А' && ch <= 'Я') || ch == 'ё' || ch == 'Ё') {
                cyr++;
            } else if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
                lat++;
            }
        }
        int total = cyr + lat;
        if (total < MIN_LETTERS) return false;
        return cyr * 100 / total >= RU_SHARE;
    }

    /**
     * Строку переводить бессмысленно?
     *
     * Числа, множители «×1.30», проценты, время, версии. Модель на таком
     * либо ничего не меняет, либо портит, а вызов всё равно стоит времени.
     */
    public static boolean isUntranslatable(String s) {
        if (s == null) return true;
        String t = s.trim();
        if (t.isEmpty()) return true;
        int letters = 0;
        for (int i = 0; i < t.length(); i++) {
            if (Character.isLetter(t.charAt(i))) letters++;
        }
        return letters * 100 / t.length() < MIN_LETTER_SHARE;
    }
}
