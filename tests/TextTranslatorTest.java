import java.util.ArrayList;
import java.util.List;

/**
 * Тест вспомогательной логики перевода: определение направления и отбор
 * строк, которые переводить бессмысленно.
 *
 * ЗАЧЕМ БЕЗ ML Kit. Сам перевод — чужая модель, её проверять не наша задача.
 * А вот две решающие функции наши, и обе ошибаются тихо: неверное
 * направление переводит русский текст на русский (получается каша), а
 * пропущенный «×1.30» модель превращает в «х1,30» и портит таблицу.
 *
 * Обе функции статические и ни от чего не зависят, поэтому вызываются
 * напрямую — копия класса при сборке теста лишена импортов ML Kit
 * автоматически (см. шаг CI).
 */
public class TextTranslatorTest {

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

        /* ---------- определение направления ---------- */
        check("русская фраза распознана",
                TextTranslatorLogic.looksRussian("Настройки приложения и уведомления"));
        check("английская фраза не русская",
                !TextTranslatorLogic.looksRussian("Application settings and notifications"));

        /*
         * Смешанная строка — главный случай. «Кэш шейдеров GLSL» русская,
         * несмотря на латинский термин: если посчитать её английской, весь
         * интерфейс поедет на перевод в обратную сторону.
         */
        check("русская с латинским термином всё ещё русская",
                TextTranslatorLogic.looksRussian("Кэш шейдеров GLSL включён"));
        check("английская с русским словом остаётся английской",
                !TextTranslatorLogic.looksRussian("Enable GLSL cache настройка"));

        // Слишком мало букв — направление неопределимо, берём английский:
        // перевод НА русский нужен заметно чаще.
        check("две буквы не считаются русским",
                !TextTranslatorLogic.looksRussian("ОК"));
        check("цифры не считаются русским",
                !TextTranslatorLogic.looksRussian("1.00 × 2.66"));

        /* ---------- что не надо переводить ---------- */
        check("множитель не переводим",
                TextTranslatorLogic.isUntranslatable("×1.30"));
        check("номер с точкой не переводим",
                TextTranslatorLogic.isUntranslatable("3."));
        check("проценты не переводим",
                TextTranslatorLogic.isUntranslatable("±14,1%"));
        check("время не переводим",
                TextTranslatorLogic.isUntranslatable("16:15:03"));
        check("пустая строка не переводится",
                TextTranslatorLogic.isUntranslatable(""));
        check("только пробелы не переводятся",
                TextTranslatorLogic.isUntranslatable("   "));

        // А это переводить НАДО, несмотря на цифры внутри.
        check("строка с цифрами и словами переводится",
                !TextTranslatorLogic.isUntranslatable("21 rounds per strategy"));
        check("обычная фраза переводится",
                !TextTranslatorLogic.isUntranslatable("Benchmark results"));
        check("короткое слово переводится",
                !TextTranslatorLogic.isUntranslatable("Discard"));

        /*
         * Граница: «4Compute shader ×2.66» — 60% букв, переводить надо.
         * Проверяем именно её, потому что порог 40% выбран не наугад, и
         * сдвинуть его случайной правкой легко.
         */
        check("смешанная строка бенчмарка переводится",
                !TextTranslatorLogic.isUntranslatable("4Compute shader  ×2.66"));

        System.out.println();
        System.out.println("итог: " + ok + " ок, " + fail + " провал");
        if (fail > 0) System.exit(1);
    }
}
