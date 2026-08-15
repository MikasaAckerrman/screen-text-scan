package com.screentextscan;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Порядок чтения: превращает набор найденных на экране надписей в
 * последовательность строк «сверху вниз, слева направо».
 *
 * ЗАЧЕМ ЭТО ОТДЕЛЬНО. Дерево элементов обходится в порядке вложенности, а не
 * в порядке того, как текст стоит на экране. Заголовок может прийти после
 * тела, значение — раньше своей подписи, а у таблицы весь второй столбец
 * оказаться после всего первого. Сортировка «по y, потом по x» кажется
 * решением, но ломается на самом частом случае — строке из нескольких
 * надписей:
 *
 *     Скачать PDF      Следить        ← три надписи на одной высоте,
 *     y=1204           y=1209           но y у них РАЗНЫЕ
 *
 * Разница в пять пикселей возникает из-за разного кегля и выравнивания.
 * Простая сортировка по y выдаст их в случайном порядке относительно друг
 * друга, а если между ними попадёт надпись из следующей строки с y=1206 —
 * порядок перемешается полностью.
 *
 * ПОЭТОМУ ДВА ЭТАПА:
 *   1. надписи группируются в ПОЛОСЫ по вертикальному перекрытию —
 *      всё, что перекрывается по высоте больше чем наполовину, считается
 *      одной строкой;
 *   2. полосы сортируются по верхней границе, а внутри полосы надписи —
 *      по левому краю.
 *
 * Это и есть «второй этап проверки», о котором шла речь: сначала собираем
 * что есть, потом раскладываем по местам.
 */
public final class ReadingOrder {

    /**
     * Какая доля высоты должна перекрываться, чтобы считать надписи одной
     * строкой. 0.5 — надписи разного кегля на общей базовой линии
     * перекрываются заведомо больше половины, а соседние строки текста —
     * заведомо меньше: у них между базовыми линиями стоит межстрочный
     * интервал.
     */
    private static final float SAME_ROW_OVERLAP = 0.5f;

    /**
     * Насколько надписи в одной полосе должны быть похожи по высоте.
     * Нужно из-за крупных заголовков: заголовок высотой 100 пикселей
     * перекрывает сразу три строки мелкого текста под собой, и без этой
     * проверки они слились бы с ним в одну полосу.
     */
    private static final float MAX_HEIGHT_RATIO = 2.2f;

    private ReadingOrder() {
    }

    /** Надпись с местом, где она стоит. */
    public static class Item {
        public final String text;
        public final Rect box;
        /** true, если координаты не экранные (WebView считает от документа). */
        public final boolean virtual;

        public Item(String text, Rect box, boolean virtual) {
            this.text = text;
            this.box = box;
            this.virtual = virtual;
        }
    }

    /**
     * Разложить надписи в порядке чтения.
     *
     * Виртуальные (документные) координаты обрабатываются так же: внутри
     * WebView они тоже возрастают сверху вниз, просто отсчёт идёт от начала
     * документа. Смешивать их с экранными нельзя — они лежат в разных
     * системах отсчёта, и надпись с y=-4000 встала бы впереди всего экрана.
     * Поэтому виртуальные идут отдельной группой ПОСЛЕ экранных: содержимое
     * WebView — это тело страницы, а экранные надписи — её обвязка
     * (адресная строка, кнопки), которая на экране стоит выше.
     */
    public static List<String> sort(List<Item> items) {
        return sort(items, false);
    }

    /**
     * @param joinRows склеивать ли надписи одной строки в одно предложение.
     *                 Нужно, когда вёрстка разрезала фразу на куски (ссылка
     *                 внутри текста, выделенное слово). Для таблиц и списков
     *                 настроек, наоборот, вредно: «Метка» и «Значение» —
     *                 разные вещи, и склеивать их нельзя. Решение оставлено
     *                 вызывающему, потому что по одному экрану не угадать.
     */
    public static List<String> sort(List<Item> items, boolean joinRows) {
        List<Item> screen = new ArrayList<>();
        List<Item> virtual = new ArrayList<>();
        for (Item it : items) {
            if (it == null || it.text == null || it.text.isEmpty()) continue;
            (it.virtual ? virtual : screen).add(it);
        }
        List<String> out = new ArrayList<>(items.size());
        out.addAll(flatten(groupRows(screen), joinRows));
        out.addAll(flatten(groupRows(virtual), joinRows));
        return out;
    }

    /**
     * Сгруппировать надписи в полосы-строки.
     *
     * Работает так: сортируем по верхней границе, затем идём сверху вниз и
     * добавляем надпись в текущую полосу, пока она с ней перекрывается.
     * Перекрытие считаем с ПЕРВОЙ надписью полосы, а не с последней: иначе
     * полоса «расползается» — каждая следующая надпись чуть ниже предыдущей,
     * и цепочкой в одну строку затягивается пол-экрана.
     */
    static List<List<Item>> groupRows(List<Item> items) {
        List<List<Item>> rows = new ArrayList<>();
        if (items.isEmpty()) return rows;

        List<Item> sorted = new ArrayList<>(items);
        Collections.sort(sorted, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                int byTop = Integer.compare(a.box.top, b.box.top);
                return byTop != 0 ? byTop : Integer.compare(a.box.left, b.box.left);
            }
        });

        List<Item> current = new ArrayList<>();
        Item anchor = null;
        for (Item it : sorted) {
            if (anchor == null || sameRow(anchor, it)) {
                if (anchor == null) anchor = it;
                current.add(it);
            } else {
                rows.add(current);
                current = new ArrayList<>();
                current.add(it);
                anchor = it;
            }
        }
        if (!current.isEmpty()) rows.add(current);
        return rows;
    }

    /** Две надписи стоят на одной строке? */
    static boolean sameRow(Item a, Item b) {
        int ha = a.box.height(), hb = b.box.height();
        if (ha <= 0 || hb <= 0) return false;

        // Заголовок не должен вбирать в себя мелкий текст под собой.
        float ratio = ha > hb ? (float) ha / hb : (float) hb / ha;
        if (ratio > MAX_HEIGHT_RATIO) return false;

        int overlap = Math.min(a.box.bottom, b.box.bottom) - Math.max(a.box.top, b.box.top);
        if (overlap <= 0) return false;
        int smaller = Math.min(ha, hb);
        return (float) overlap / smaller >= SAME_ROW_OVERLAP;
    }

    /** Полосы → плоский список: внутри полосы слева направо. */
    private static List<String> flatten(List<List<Item>> rows, boolean joinRows) {
        List<String> out = new ArrayList<>();
        for (List<Item> row : rows) {
            Collections.sort(row, new Comparator<Item>() {
                @Override
                public int compare(Item a, Item b) {
                    int byLeft = Integer.compare(a.box.left, b.box.left);
                    return byLeft != 0 ? byLeft : Integer.compare(a.box.top, b.box.top);
                }
            });
            if (joinRows && row.size() > 1) {
                List<String> parts = new ArrayList<>(row.size());
                List<Rect> boxes = new ArrayList<>(row.size());
                for (Item it : row) {
                    parts.add(it.text);
                    boxes.add(it.box);
                }
                out.addAll(joinRow(parts, boxes));
            } else {
                for (Item it : row) out.add(it.text);
            }
        }
        return out;
    }

    /**
     * Соединять ли две соседние надписи одной полосы в одну строку текста.
     *
     * ЗАЧЕМ. Одно предложение в вёрстке часто разрезано на куски: ссылка
     * внутри текста — отдельный элемент, выделенное слово — отдельный.
     * Получается «Использовался при создании» / «компьютерных игр» / «,» —
     * три строки вместо одной, и читать это неудобно, а переводить
     * бессмысленно: модель не видит контекста.
     *
     * Признак продолжения: надписи стоят рядом по горизонтали (зазор меньше
     * высоты строки) и предыдущая не кончается точкой. Пунктуация в начале
     * второй надписи («,», «.») приклеивается без пробела.
     */
    public static List<String> joinRow(List<String> parts, List<Rect> boxes) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        Rect prev = null;
        for (int i = 0; i < parts.size(); i++) {
            String p = parts.get(i);
            Rect b = boxes.get(i);
            if (prev != null && continues(prev, b, buf.toString(), p)) {
                if (startsWithPunct(p)) buf.append(p);
                else buf.append(' ').append(p);
            } else {
                if (buf.length() > 0) out.add(buf.toString());
                buf.setLength(0);
                buf.append(p);
            }
            prev = b;
        }
        if (buf.length() > 0) out.add(buf.toString());
        return out;
    }

    private static boolean startsWithPunct(String s) {
        if (s.isEmpty()) return false;
        return ",.;:!?)»".indexOf(s.charAt(0)) >= 0;
    }

    private static boolean continues(Rect prev, Rect next, String left, String right) {
        if (left.isEmpty() || right.isEmpty()) return false;
        int gap = next.left - prev.right;
        int h = Math.max(1, prev.height());
        // Зазор больше высоты строки — это уже другой столбец, не продолжение.
        if (gap < -h || gap > h) return false;
        char last = left.charAt(left.length() - 1);
        // Законченное предложение не продолжаем.
        if (".!?".indexOf(last) >= 0) return false;
        return true;
    }
}
