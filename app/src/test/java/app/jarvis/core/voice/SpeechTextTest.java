package app.jarvis.core.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.voice.SpeechText.Opts;

import org.junit.Test;

import java.util.List;

/** Table-driven tests of the speech normalizer: real assistant phrases, verified outputs. */
public class SpeechTextTest {

    /** {input, expected, tagMode} — tagMode: "" = default opts, "keep" = keepEmotionTags(true). */
    private static final Object[][] TABLE = {
        // --- cardinals, gender and case of 1/2
        {"2 часа", "Два часа", ""},
        {"21 год", "Двадцать один год", ""},
        {"5 минут", "Пять минут", ""},
        {"1 час", "Один час", ""},
        {"1 минута", "Одна минута", ""},
        {"2 минуты", "Две минуты", ""},
        {"11 минут", "Одиннадцать минут", ""},
        {"22 года", "Двадцать два года", ""},
        {"25 лет", "Двадцать пять лет", ""},
        {"1000 рублей", "Одна тысяча рублей", ""},
        {"2000 уведомлений", "Две тысячи уведомлений", ""},
        {"5000 файлов", "Пять тысяч файлов", ""},
        {"1000000 строк", "Один миллион строк", ""},
        {"2000000 строк", "Два миллиона строк", ""},
        {"5000000 строк", "Пять миллионов строк", ""},
        {"1234567", "Один миллион двести тридцать четыре тысячи пятьсот шестьдесят семь", ""},
        {"0", "Ноль", ""},
        {"У меня 3 сообщения", "У меня три сообщения", ""},
        {"через 10 минут", "Через десять минут", ""},
        {"1 окно", "Одно окно", ""},
        {"1 задача", "Одна задача", ""},
        {"2 задачи", "Две задачи", ""},
        {"1 жизнь", "Одна жизнь", ""},
        {"1 рубль", "Один рубль", ""},
        {"2 двери", "Две двери", ""},
        {"у 2 окон", "У двух окон", ""},
        {"999999999999", "Девятьсот девяносто девять миллиардов девятьсот девяносто девять миллионов "
                + "девятьсот девяносто девять тысяч девятьсот девяносто девять", ""},
        {"1 000 000", "Один миллион", ""},
        {"500 000 км", "Пятьсот тысяч километров", ""},
        // --- percents
        {"50%", "Пятьдесят процентов", ""},
        {"1%", "Один процент", ""},
        {"2%", "Два процента", ""},
        {"5%", "Пять процентов", ""},
        {"21%", "Двадцать один процент", ""},
        {"100%", "Сто процентов", ""},
        {"Заряд батареи 15%", "Заряд батареи пятнадцать процентов", ""},
        // --- times
        {"07:30", "Семь тридцать", ""},
        {"07:05", "Семь ноль пять", ""},
        {"в 9:00", "В девять ноль ноль", ""},
        {"23:59", "Двадцать три пятьдесят девять", ""},
        {"12:15", "Двенадцать пятнадцать", ""},
        {"Встреча в 14:30 у 2 окон", "Встреча в четырнадцать тридцать у двух окон", ""},
        // --- dates
        {"28.10.2026", "Двадцать восьмое октября две тысячи двадцать шестого года", ""},
        {"01.01.2025", "Первое января две тысячи двадцать пятого года", ""},
        {"01.09.2026", "Первое сентября две тысячи двадцать шестого года", ""},
        {"23.03.1999", "Двадцать третье марта одна тысяча девятьсот девяносто девятого года", ""},
        {"05.05.2005", "Пятое мая две тысячи пятого года", ""},
        {"01.01.2000", "Первое января двухтысячного года", ""},
        {"В 2026 году", "В две тысячи двадцать шестом году", ""},
        {"до 2026 года", "До две тысячи двадцать шестого года", ""},
        // --- decimals
        {"3,5", "Три целых пять десятых", ""},
        {"1,5", "Одна целая пять десятых", ""},
        {"2,5", "Две целых пять десятых", ""},
        {"0,5", "Пять десятых", ""},
        {"3,14", "Три целых четырнадцать сотых", ""},
        {"1,01", "Одна целая одна сотая", ""},
        {"-2,5", "Минус две целых пять десятых", ""},
        {"5,25 кг", "Пять целых двадцать пять сотых килограмма", ""},
        {"3,0", "Три", ""},
        {"Температура 3,5.", "Температура три целых пять десятых.", ""},
        {"1,5 часа", "Одна целая пять десятых часа", ""},
        {"25,4 км", "Двадцать пять целых четыре десятых километра", ""},
        // --- ordinals
        {"3-й", "Третий", ""},
        {"21-й век", "Двадцать первый век", ""},
        {"3-е место", "Третье место", ""},
        {"2-я половина", "Вторая половина", ""},
        {"5-го числа", "Пятого числа", ""},
        {"1-й", "Первый", ""},
        {"в 3-й раз", "В третий раз", ""},
        {"5-ю строку", "Пятую строку", ""},
        // --- conservative: never mangle
        {"3-5 дней", "3-5 дней", ""},
        {"123-45-67", "123-45-67", ""},
        {"1.2.3", "1.2.3", ""},
        {"1,000,000", "1,000,000", ""},
        {"10:20:30", "10:20:30", ""},
        {"Просто текст без чисел", "Просто текст без чисел", ""},
        // --- units
        {"5 км", "Пять километров", ""},
        {"1 км", "Один километр", ""},
        {"22 км", "Двадцать два километра", ""},
        {"5 кг", "Пять килограммов", ""},
        {"3 мин", "Три минуты", ""},
        {"1 мин", "Одна минута", ""},
        {"10 сек", "Десять секунд", ""},
        {"2 ч", "Два часа", ""},
        {"25 °C", "Двадцать пять градусов Цельсия", ""},
        {"-5 °C", "Минус пять градусов Цельсия", ""},
        {"5 тыс. км", "Пять тысяч километров", ""},
        {"2 млн рублей", "Два миллиона рублей", ""},
        {"500 ₽", "Пятьсот рублей", ""},
        {"10 $", "Десять долларов", ""},
        {"5 €", "Пять евро", ""},
        {"100 км/ч", "Сто километров в час", ""},
        {"5 часов", "Пять часов", ""},
        {"5 литров воды", "Пять литров воды", ""},
        {"22 тыс. файлов", "Двадцать две тысячи файлов", ""},
        // --- markdown / emoji / URLs
        {"**Важно**: встреча", "Важно: встреча", ""},
        {"# Заголовок\nТекст", "Заголовок. Текст", ""},
        {"[док](https://example.com/x)", "Док", ""},
        {"Смотри https://example.com/a?b=1.", "Смотри ссылка.", ""},
        {"Загляни на www.example.com завтра", "Загляни на ссылка завтра", ""},
        {"Привет 😀! Как дела 🤔", "Привет! Как дела", ""},
        {"`код` и **жирный**", "Код и жирный", ""},
        {"- пункт один\n- пункт два", "Пункт один. пункт два.", ""},
        {"1. Первый пункт\n2) Второй пункт", "Первый пункт. Второй пункт.", ""},
        {"Код: ```print(x)``` готов", "Код: print(x) готов", ""},
        {"Строка с © и ™ знаками", "Строка с и знаками", ""},
        // --- emotion tags
        {"[whispers] Подойди ближе", "Подойди ближе", ""},
        {"[whispers] Подойди ближе", "[whispers] Подойди ближе", "keep"},
        {"[sighs] Ну ладно [pause] ждём", "[sighs] Ну ладно [pause] ждём", "keep"},
        {"[sighs] Ну ладно [pause] ждём", "Ну ладно ждём", ""},
        {"[смеётся] Ха", "[смеётся] Ха", ""},
        {"Привет [whispers", "Привет", ""},
        {"Привет [whispers", "Привет", "keep"},
        // --- abbreviations
        {"Это т.е. иначе", "Это то есть иначе", ""},
        {"кошки, собаки и т.д.", "Кошки, собаки и так далее", ""},
        {"и т.п.", "И тому подобное", ""},
        {"т.к. поздно", "Так как поздно", ""},
        {"т. н. предмет", "Так называемый предмет", ""},
        {"это т. н. предмет", "Это так называемый предмет", ""},
        // --- whitespace / misc
        {"", "", ""},
        {"много    пробелов\n\nи переносов", "Много пробелов и переносов", ""},
        {"привет", "Привет", ""},
        {"Скорость 1,5 Мбит/с и 100 Мбайт", "Скорость одна целая пять десятых Мбит/с и сто Мбайт", ""},
    };

    /** {input, expected} for {@link SpeechText#forSpeech}: ellipses, punctuation runs, markdown, symbols. "" = nothing to say. */
    private static final String[][] CLEAN = {
        // --- ellipses (P-05/P-09: "..." was read for 5-10 s)
        {"...", ""},
        {"…", ""},
        {". . .", ""},
        {"....", ""},
        {"……", ""},
        {"… …", ""},
        {"!...", ""},
        {"Подожди...", "Подожди."},
        {"Подожди…", "Подожди."},
        {"Подожди . . .", "Подожди."},
        {"Подожди....", "Подожди."},
        {"Ну... не знаю", "Ну, не знаю"},
        {"Ну… не знаю", "Ну, не знаю"},
        {"Ну . . . не знаю", "Ну, не знаю"},
        {"Хм... Ладно.", "Хм. Ладно."},
        {"Хм… Ладно", "Хм. Ладно"},
        {"...привет", "Привет"},
        {"Привет, ...", "Привет"},
        {"Он сказал: «Не знаю...»", "Он сказал: «Не знаю.»"},
        {"Думаю...\nСейчас", "Думаю. Сейчас"},
        {"Секунду.....  ищу", "Секунду, ищу"},
        // --- repeated punctuation
        {"Ура!!!", "Ура!"},
        {"Что??", "Что?"},
        {"Серьёзно?!", "Серьёзно?"},
        {"Серьёзно!?!?", "Серьёзно?"},
        {"Да,, нет", "Да, нет"},
        {"Да, . Нет", "Да. Нет"},
        {"Внимание -- важно", "Внимание — важно"},
        {"Привет !", "Привет!"},
        {"Привет ,мир", "Привет,мир"},
        {"!!!", ""},
        {"?!", ""},
        {", ,", ""},
        // --- markdown
        {"Это *важно* и _тоже_", "Это важно и тоже"},
        {"**Жирный** и ~~зачёркнутый~~ текст", "Жирный и зачёркнутый текст"},
        {"## Итоги\n\n* первое\n* второе", "Итоги. первое. второе."},
        {"Смотри [документацию](https://docs.example.com/a/b) там", "Смотри документацию там"},
        {"![картинка](https://x.y/z.png) Готово", "Готово"},
        {"Вот код:\n```java\nint x = 1;\n```\nГотово.", "Вот код: Готово."},
        {"Вот код:\n```\nrm -rf /\n```", "Вот код:"},
        {"Вот код:\n```python\nprint(1)", "Вот код:"},
        {"```\nx\n```", ""},
        {"Запусти `ls -la` сейчас", "Запусти ls -la сейчас"},
        {"> цитата\nответ", "Цитата ответ"},
        {"---\nТекст\n---", "Текст"},
        {"| Имя | Возраст |\n|---|---|\n| Аня | 5 |", "Имя, Возраст Аня, пять"},
        {"Источник [1] и [23]", "Источник и"},
        {"имя_файла_с_подчёркиваниями", "Имя файла с подчёркиваниями"},
        // --- URLs
        {"Открой https://example.com/path?x=1&y=2, потом закрой", "Открой ссылка, потом закрой"},
        {"Сайт (www.example.com).", "Сайт (ссылка)."},
        {"http://localhost:8080/", "Ссылка"},
        {"См. https://a.b/c. Конец", "См. ссылка. Конец"},
        // --- emoji and symbols
        {"😀😀😀", ""},
        {"Отлично 👍🏽!", "Отлично!"},
        {"Готово ✅", "Готово"},
        {"☀️ Солнечно", "Солнечно"},
        {"→ дальше ←", "Дальше"},
        {"Привет ★★★ мир", "Привет мир"},
        {"A ~ B ^ C", "A B C"},
        {"Привет​ мир­!", "Привет мир!"},
        {"#хэштег", "Хэштег"},
        {"{}", ""},
        {"[]", ""},
        {"***", ""},
        {"• пункт", "Пункт."},
        // --- Russian and digits intact
        {"Ёжик в тумане, 3 раза!", "Ёжик в тумане, три раза!"},
        {"Версия 1.2.3 готова", "Версия 1.2.3 готова"},
        {"Номер 123-45-67", "Номер 123-45-67"},
        {"Цена 3.5 рубля", "Цена 3.5 рубля"},
        {"Съешь ещё этих мягких французских булок, да выпей чаю.", "Съешь ещё этих мягких французских булок, да выпей чаю."},
        {"Вопрос? Ответ! Точка.", "Вопрос? Ответ! Точка."},
    };

    @Test
    public void cleanTable() {
        StringBuilder bad = new StringBuilder();
        for (String[] row : CLEAN) {
            String got = SpeechText.forSpeech(row[0]);
            if (!row[1].equals(got)) bad.append("\n  [").append(row[0]).append("] expected [").append(row[1]).append("] got [").append(got).append(']');
        }
        assertEquals("mismatches:" + bad, 0, bad.length());
    }

    @Test
    public void cleanTableIsIdempotent() {
        for (String[] row : CLEAN) {
            String once = SpeechText.clean(row[0]);
            assertEquals("input: [" + row[0] + "]", once, SpeechText.clean(once));
        }
    }

    @Test
    public void hasSpeakable() {
        assertTrue(SpeechText.hasSpeakable("а"));
        assertTrue(SpeechText.hasSpeakable("... 5"));
        assertTrue(!SpeechText.hasSpeakable("...!?"));
        assertTrue(!SpeechText.hasSpeakable("  \n"));
        assertTrue(!SpeechText.hasSpeakable(null));
    }

    @Test
    public void emptyAfterCleaningIsEmpty() {
        for (String s : new String[] {"...", "…", ". . .", "!!!", "😀", "```\ncode\n```", "***", " , ; "}) {
            assertEquals("[" + s + "]", "", SpeechText.clean(s));
            assertEquals("[" + s + "]", "", SpeechText.forSpeech(s));
        }
    }

    @Test
    public void codeMarkerSpokenOnlyWhenConfigured() {
        String in = "Вот:\n```sh\nls\n```\nВсё.";
        assertEquals("Вот: Всё.", SpeechText.forSpeech(in));
        assertEquals("Вот. код. Всё.", SpeechText.clean(in, new Opts().codeMarker("код")));
    }

    @Test
    public void longMixedMarkdownReply() {
        String in = "# План на день...\n\n"
            + "Привет! Вот **что** я нашёл 😀:\n\n"
            + "1. Открой [почту](https://mail.example.com/inbox?u=1) — там 3 письма!!!\n"
            + "2. Проверь `config.yml`… он важный.\n"
            + "- Купить _молоко_ и хлеб...\n\n"
            + "```bash\nsudo rm -rf /\n```\n\n"
            + "Подробности: https://example.com/a/b?c=d. Всё ✅\n"
            + "> Помни: время 07:30 — это ~ важно!!?\n"
            + "...\n";
        String out = SpeechText.forSpeech(in);
        assertEquals("План на день. Привет! Вот что я нашёл: Открой почту — там три письма! "
            + "Проверь config.yml, он важный. Купить молоко и хлеб. "
            + "Подробности: ссылка. Всё Помни: время семь тридцать — это важно?", out);
        assertTrue(!out.contains("rm"));
        assertTrue(!out.contains(".."));
    }

    @Test
    public void unspeakableChunksAreDetectedBySplitter() {
        for (String c : SpeechText.splitSentences(SpeechText.forSpeech("Раз. ... Два! ?! Три."), 180)) assertTrue(SpeechText.hasSpeakable(c));
    }

    @Test
    public void table() {
        for (Object[] row : TABLE) {
            String in = (String) row[0], expected = (String) row[1];
            Opts opts = new Opts();
            if ("keep".equals(row[2])) opts.keepEmotionTags(true);
            assertEquals("input: [" + in + "]", expected, SpeechText.forSpeech(in, opts));
        }
    }

    @Test
    public void tableIsIdempotent() {
        for (Object[] row : TABLE) {
            String once = SpeechText.forSpeech((String) row[0]);
            assertEquals("input: [" + row[0] + "]", once, SpeechText.forSpeech(once));
        }
    }

    @Test
    public void nullAndBlank() {
        assertEquals("", SpeechText.forSpeech(null));
        assertEquals("", SpeechText.forSpeech("   \n\t "));
    }

    // ------------------------------------------------------------------ splitting

    @Test
    public void splitPacksShortSentencesIntoFirstChunk() {
        List<String> c = SpeechText.splitSentences("Привет! Как дела? Сегодня отличный день. Пойдём гулять?", 300);
        assertEquals(1, c.size());
        assertEquals("Привет! Как дела? Сегодня отличный день. Пойдём гулять?", c.get(0));
    }

    @Test
    public void splitKeepsFirstChunkShort() {
        StringBuilder sb = new StringBuilder("Слово ");
        for (int i = 0; i < 100; i++) sb.append("тест ");
        List<String> c = SpeechText.splitSentences(sb.toString(), 300);
        assertTrue("first chunk " + c.get(0).length(), c.get(0).length() <= 120);
        for (String s : c) assertTrue("chunk too long: " + s.length(), s.length() <= 300);
        assertTrue(c.size() >= 2);
    }

    @Test
    public void splitRespectsMaxChars() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append("Предложение номер ").append(i).append(". ");
        List<String> c = SpeechText.splitSentences(sb.toString(), 150);
        assertTrue(c.size() >= 3);
        for (String s : c) assertTrue(s.length() <= 150);
    }

    @Test
    public void splitDoesNotSplitOnInitialsOrDecimals() {
        List<String> c = SpeechText.splitSentences("А. С. Пушкин родился в 1799 году. Он писал стихи.", 300);
        assertEquals(1, c.size());
        c = SpeechText.splitSentences("Число 3.14 это пи. А это второе.", 300);
        assertEquals(1, c.size());
    }

    @Test
    public void splitLongSentenceBreaksAtComma() {
        List<String> c = SpeechText.splitSentences(
                "Купить молоко, хлеб, яйца, сахар, муку, масло, сыр, чай, кофе и соль", 40);
        assertTrue(c.size() >= 2);
        for (String s : c) assertTrue(s.length() <= 40);
    }

    @Test
    public void splitEmpty() {
        assertTrue(SpeechText.splitSentences("", 300).isEmpty());
        assertTrue(SpeechText.splitSentences("   ", 300).isEmpty());
        assertTrue(SpeechText.splitSentences(null, 300).isEmpty());
    }

    // ------------------------------------------------------------------ pronunciation dict integration

    @Test
    public void dictIsAppliedLast() {
        PronunciationDict d = new PronunciationDict().add("джарвис", "ДЖАРВИС");
        Opts opts = new Opts().dict(d);
        assertEquals("Привет, ДЖАРВИС!", SpeechText.forSpeech("Привет, Джарвис!", opts));
        // without the dict the word is untouched
        assertEquals("Привет, Джарвис!", SpeechText.forSpeech("Привет, Джарвис!"));
    }
}
