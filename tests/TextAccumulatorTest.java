import java.util.Arrays;
import java.util.List;

/**
 * Тест накопителя. Без JUnit и без Android — обычный main, потому что
 * проверять надо чистую логику, а тащить зависимости ради шести проверок
 * бессмысленно.
 *
 * ЗАЧЕМ ЭТОТ ТЕСТ ВООБЩЕ. Дедупликация — единственное место, где ошибка
 * тихая: приложение соберётся, кнопка нажмётся, а в результате будет либо
 * тысяча повторов одной строки, либо потерянный текст. Ни компилятор, ни
 * ручная проверка на телефоне такого не поймают быстро.
 */
public class TextAccumulatorTest {

    static int ok = 0, fail = 0;

    static void check(String what, boolean cond) {
        if (cond) {
            ok++;
            System.out.println("ОК    " + what);
        } else {
            fail++;
            System.out.println("ПРОВАЛ " + what);
        }
    }

    public static void main(String[] a) {
        // 1. Повторы одной строки не копятся. Это основной случай: при
        //    прокрутке каждая строка приходит десятки раз.
        TextAccumulator t1 = new TextAccumulator();
        t1.addAll(Arrays.asList("Привет", "Привет", "Привет"));
        check("повтор не дублируется", t1.size() == 1);

        // 2. Разный перенос — та же строка. Проверено на живых экранах:
        //    одна надпись приходит то с одним пробелом, то с двумя.
        TextAccumulator t2 = new TextAccumulator();
        t2.addAll(Arrays.asList("Быстрые  инструменты", "Быстрые инструменты"));
        check("схлопывание пробелов", t2.size() == 1);

        // 3. Концевая точка не делает строку новой.
        TextAccumulator t3 = new TextAccumulator();
        t3.addAll(Arrays.asList("Готово.", "Готово"));
        check("концевая пунктуация срезается", t3.size() == 1);

        // 4. Регистр РАЗЛИЧАЕТСЯ. Специально: «ОК» и «ок» бывают разными
        //    элементами, и склеивать их нельзя.
        TextAccumulator t4 = new TextAccumulator();
        t4.addAll(Arrays.asList("ОК", "ок"));
        check("регистр различается", t4.size() == 2);

        // 5. Мусор строки состояния отбрасывается. Часы приходят каждый
        //    опрос и без фильтра дали бы по строке в секунду.
        TextAccumulator t5 = new TextAccumulator();
        t5.addAll(Arrays.asList("16:15:03", "29 %", "4G+", "17,0 КБ/с", "Настоящий текст"));
        check("часы, заряд и сеть отброшены", t5.size() == 1);
        check("настоящий текст сохранён", t5.text().equals("Настоящий текст"));

        // 6. Порядок — по первому появлению, а не по последнему опросу.
        TextAccumulator t6 = new TextAccumulator();
        t6.addAll(Arrays.asList("первая", "вторая"));
        t6.addAll(Arrays.asList("вторая", "третья"));
        List<String> lines = t6.lines();
        check("порядок первого появления",
                lines.equals(Arrays.asList("первая", "вторая", "третья")));

        // 7. Счётчик новых строк — по нему решается, продолжать ли чтение.
        TextAccumulator t7 = new TextAccumulator();
        int firstBatch = t7.addAll(Arrays.asList("a", "b"));
        int secondBatch = t7.addAll(Arrays.asList("a", "b"));
        check("новых в первом опросе 2", firstBatch == 2);
        check("новых во втором опросе 0", secondBatch == 0);

        // 8. Пустые и пробельные строки не попадают.
        TextAccumulator t8 = new TextAccumulator();
        t8.addAll(Arrays.asList("", "   ", null, "текст"));
        check("пустое и null отброшены", t8.size() == 1);

        System.out.println();
        System.out.println("итог: " + ok + " ок, " + fail + " провал");
        if (fail > 0) System.exit(1);
    }
}
