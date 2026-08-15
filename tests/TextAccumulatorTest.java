import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Тест накопителя. Без JUnit и без Android — обычный main, потому что
 * проверять надо чистую логику, а тащить зависимости ради двух десятков
 * проверок бессмысленно.
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

    static List<String> l(String... s) {
        return Arrays.asList(s);
    }

    public static void main(String[] a) {

        /* ---------- уровень 1: точные повторы ---------- */
        TextAccumulator t1 = new TextAccumulator();
        t1.addAll(l("Привет", "Привет", "Привет"));
        check("повтор не дублируется", t1.size() == 1);

        /* ---------- уровень 2: тот же текст иначе оформлен ---------- */
        TextAccumulator t2 = new TextAccumulator();
        t2.addAll(l("Быстрые  инструменты", "Быстрые инструменты"));
        check("схлопывание пробелов", t2.size() == 1);

        TextAccumulator t3 = new TextAccumulator();
        t3.addAll(l("Готово.", "Готово"));
        check("концевая точка срезается", t3.size() == 1);

        TextAccumulator t3b = new TextAccumulator();
        t3b.addAll(l("Загрузка…", "Загрузка"));
        check("концевое многоточие срезается", t3b.size() == 1);

        // Регистр РАЗЛИЧАЕТСЯ специально: «ОК» и «ок» бывают разными кнопками.
        TextAccumulator t4 = new TextAccumulator();
        t4.addAll(l("ОК", "ок"));
        check("регистр различается", t4.size() == 2);

        /* ---------- уровень 3: наращивание обрезанной строки ----------
         * Главный случай, которого не было в первой версии: при прокрутке
         * строку застаёшь недорисованной, а следующим опросом — целиком.
         * Без этого в результате оказывались обе. */
        TextAccumulator t5 = new TextAccumulator();
        t5.addAll(l("Использовался при созда"));
        t5.addAll(l("Использовался при создании компьютерных игр"));
        check("обрезок заменяется полной строкой", t5.size() == 1);
        check("осталась именно полная",
                t5.text().equals("Использовался при создании компьютерных игр"));

        // Дорисовка с НАЧАЛА: у строки появился отсутствовавший левый край.
        TextAccumulator t6 = new TextAccumulator();
        t6.addAll(l("значительный вклад в развитие"));
        t6.addAll(l("Несмотря на значительный вклад в развитие"));
        check("дорисовка слева тоже склеивается", t6.size() == 1);

        // Место в порядке сохраняется: строка не должна прыгать в конец.
        TextAccumulator t7 = new TextAccumulator();
        t7.addAll(l("первая строка целиком", "вторая обрез", "третья строка целиком"));
        t7.addAll(l("вторая обрезанная строка полностью"));
        List<String> lines7 = t7.lines();
        check("уточнённая строка осталась на своём месте",
                lines7.size() == 3 && lines7.get(1).equals("вторая обрезанная строка полностью"));

        // Короткие строки НЕ склеиваем: «Да» — начало «Дальше».
        TextAccumulator t8 = new TextAccumulator();
        t8.addAll(l("Да", "Дальше"));
        check("короткие строки не поглощаются", t8.size() == 2);

        /* ---------- уровень 4: обрезок пришёл ПОСЛЕ полной строки ---------- */
        TextAccumulator t9 = new TextAccumulator();
        t9.addAll(l("Использовался при создании компьютерных игр"));
        t9.addAll(l("Использовался при созда"));
        check("поздний обрезок отброшен", t9.size() == 1);
        check("полная строка не испорчена",
                t9.text().equals("Использовался при создании компьютерных игр"));

        /* ---------- мусор строки состояния ---------- */
        TextAccumulator t10 = new TextAccumulator();
        t10.addAll(l("16:15:03", "29 %", "4G+", "17,0 КБ/с", "Настоящий текст"));
        check("часы, заряд и сеть отброшены", t10.size() == 1);
        check("настоящий текст сохранён", t10.text().equals("Настоящий текст"));

        /* ---------- порядок и счётчик ---------- */
        TextAccumulator t11 = new TextAccumulator();
        t11.addAll(l("первая", "вторая"));
        t11.addAll(l("вторая", "третья"));
        check("порядок первого появления",
                t11.lines().equals(l("первая", "вторая", "третья")));

        TextAccumulator t12 = new TextAccumulator();
        int b1 = t12.addAll(l("aaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbb"));
        int b2 = t12.addAll(l("aaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbb"));
        check("новых в первом опросе 2", b1 == 2);
        check("новых во втором опросе 0", b2 == 0);

        TextAccumulator t13 = new TextAccumulator();
        t13.addAll(Arrays.asList("", "   ", null, "текст"));
        check("пустое и null отброшены", t13.size() == 1);

        /* ---------- правка результата ----------
         * Нужна потому, что дерево иногда отдаёт мусор: подпись невидимой
         * кнопки, дубль заголовка. Вычёркивание НЕ удаляет строку, чтобы
         * её можно было вернуть. */
        TextAccumulator t14 = new TextAccumulator();
        t14.addAll(l("нужная строка раз", "мусорная строка", "нужная строка два"));
        t14.drop(1);
        check("вычеркнутая не попала в текст",
                t14.text().equals("нужная строка раз\nнужная строка два"));
        check("но осталась в списке", t14.size() == 3);
        check("счётчик итоговых уменьшился", t14.keptSize() == 2);
        check("вычёркивание видно", t14.isDropped(1));
        t14.restore(1);
        check("возврат работает", t14.keptSize() == 3);

        t14.edit(1, "исправленная строка");
        check("правка применилась", t14.lines().get(1).equals("исправленная строка"));
        check("после правки текст верный",
                t14.text().equals("нужная строка раз\nисправленная строка\nнужная строка два"));

        /*
         * ВАЖНО: после правки строка не должна снова прийти как «новая».
         * Если бы edit не обновлял индекс, следующий же опрос добавил бы
         * исходный текст обратно, и правка выглядела бы неработающей.
         */
        TextAccumulator t15 = new TextAccumulator();
        t15.addAll(l("строка с опечаткай"));
        t15.edit(0, "строка с опечаткой");
        t15.addAll(l("строка с опечаткой"));
        check("исправленная строка не дублируется", t15.size() == 1);

        /* ---------- границы ---------- */
        TextAccumulator t16 = new TextAccumulator();
        t16.drop(5);
        t16.edit(9, "мимо");
        check("операции по несуществующему номеру безопасны", t16.size() == 0);

        TextAccumulator t17 = new TextAccumulator();
        t17.addAll(l("одна строка"));
        t17.drop(0);
        check("всё вычеркнуто = пустой текст", t17.text().isEmpty());
        t17.restoreAll();
        check("restoreAll возвращает всё", t17.keptSize() == 1);

        System.out.println();
        System.out.println("итог: " + ok + " ок, " + fail + " провал");
        if (fail > 0) System.exit(1);
    }
}
