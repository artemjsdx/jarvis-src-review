package app.jarvis.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class SuggestionPromptTest {
    private static SuggestionPrompt.Input input() {
        SuggestionPrompt.Input in = new SuggestionPrompt.Input();
        in.hour = 9;
        in.weekday = 2;
        return in;
    }

    @Test public void promptCarriesTimeWeekdayAndMemory() {
        SuggestionPrompt.Input in = input();
        in.memory = "пользователь предпочитает краткие ответы";
        String p = SuggestionPrompt.user(in);
        assertTrue(p, p.contains("Сейчас: утро, среда, 09:00."));
        assertTrue(p, p.contains("Что известно о пользователе: пользователь предпочитает краткие ответы"));
        assertTrue(p, p.contains("Последних реплик пользователя нет."));
    }

    @Test public void memoryIsClippedToBudget() {
        SuggestionPrompt.Input in = input();
        StringBuilder m = new StringBuilder();
        for (int i = 0; i < 200; i++) m.append("факт").append(i).append(' ');
        in.memory = m.toString();
        String p = SuggestionPrompt.user(in);
        String line = p.split("\n")[1];
        assertTrue(line, line.length() <= "Что известно о пользователе: ".length() + SuggestionPrompt.MEMORY_CHARS + 1);
        assertTrue(line.endsWith("…"));
    }

    @Test public void onlyLastSixLinesAreKeptAndEachIsCutTo120() {
        SuggestionPrompt.Input in = input();
        for (int i = 0; i < 10; i++) in.recentUserLines.add("реплика " + i);
        in.recentUserLines.set(9, repeat('я', 300));
        String p = SuggestionPrompt.user(in);
        assertFalse(p, p.contains("реплика 3"));
        assertTrue(p, p.contains("реплика 4"));
        String last = null;
        for (String l : p.split("\n")) if (l.startsWith("- ")) last = l;
        assertTrue(last, last.codePointCount(0, last.length()) <= 2 + SuggestionPrompt.LINE_CHARS);
        assertTrue(last.endsWith("…"));
    }

    @Test public void promptIsCappedForLongHistory() {
        SuggestionPrompt.Input in = input();
        for (int i = 0; i < 50; i++) in.recentUserLines.add(repeat('a', 500));
        in.memory = repeat('m', 3000);
        assertTrue(SuggestionPrompt.user(in).length() < 1400);
    }

    @Test public void contextHashIgnoresHourInsideTheSamePartOfDay() {
        SuggestionPrompt.Input a = input();
        SuggestionPrompt.Input b = input();
        b.hour = 10;
        assertEquals(SuggestionPrompt.contextHash(a), SuggestionPrompt.contextHash(b));
        b.hour = 13;
        assertNotEquals(SuggestionPrompt.contextHash(a), SuggestionPrompt.contextHash(b));
    }

    @Test public void contextHashChangesWithNewUserMessage() {
        SuggestionPrompt.Input a = input();
        a.recentUserLines.add("включи музыку");
        SuggestionPrompt.Input b = input();
        b.recentUserLines.add("включи музыку");
        b.recentUserLines.add("что в календаре");
        assertNotEquals(SuggestionPrompt.contextHash(a), SuggestionPrompt.contextHash(b));
        assertEquals(16, SuggestionPrompt.contextHash(a).length());
    }

    @Test public void parsesPlainJsonArray() {
        assertEquals(Arrays.asList("Что нового?", "Включи музыку", "Какие дела на завтра?"),
            SuggestionPrompt.parse("[\"Что нового?\", \"Включи музыку\", \"Какие дела на завтра?\"]"));
    }

    @Test public void parsesFencedJsonWithChatter() {
        String raw = "Вот варианты:\n```json\n[\"Покажи файлы\", \"Открой журнал\", \"Таймер на 5 минут\"]\n```\nГотово.";
        assertEquals(Arrays.asList("Покажи файлы", "Открой журнал", "Таймер на 5 минут"), SuggestionPrompt.parse(raw));
    }

    @Test public void parsesBulletedLinesWhenNoJson() {
        String raw = "- Напомни про встречу\n2. «Включи радио»\n3) Что я делал вчера?";
        assertEquals(Arrays.asList("Напомни про встречу", "Включи радио", "Что я делал вчера?"), SuggestionPrompt.parse(raw));
    }

    @Test public void dropsDuplicatesEmptyLongAndEmojiAndKeepsThree() {
        String raw = "[\"Что нового?\", \"что нового?\", \"\", \"  \", \"Это очень длинная подсказка которая не влезает\", "
            + "\"Включи 🎵 музыку\", \"Открой журнал\", \"Покажи файлы\", \"Четвёртая\"]";
        assertEquals(Arrays.asList("Что нового?", "Открой журнал", "Покажи файлы"), SuggestionPrompt.parse(raw));
    }

    @Test public void fewerThanTwoValidMeansNothing() {
        assertTrue(SuggestionPrompt.parse("[\"Одна подсказка\", \"\"]").isEmpty());
        assertTrue(SuggestionPrompt.parse("").isEmpty());
        assertTrue(SuggestionPrompt.parse(null).isEmpty());
        assertTrue(SuggestionPrompt.parse("{\"oops\": true}").isEmpty());
    }

    @Test public void lengthLimitIsCountedInCodePoints() {
        String twentyEight = repeat('я', SuggestionPrompt.CHIP_CHARS);
        List<String> out = SuggestionPrompt.parse("[\"" + twentyEight + "\", \"Включи музыку\"]");
        assertEquals(2, out.size());
        assertFalse(SuggestionPrompt.parse("[\"" + twentyEight + "я\", \"Включи музыку\"]").contains(twentyEight + "я"));
    }

    @Test public void timeOfDayBuckets() {
        assertEquals("утро", SuggestionPrompt.timeOfDay(5));
        assertEquals("день", SuggestionPrompt.timeOfDay(11));
        assertEquals("вечер", SuggestionPrompt.timeOfDay(17));
        assertEquals("ночь", SuggestionPrompt.timeOfDay(23));
        assertEquals("ночь", SuggestionPrompt.timeOfDay(2));
    }

    private static String repeat(char c, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(c);
        return b.toString();
    }
}
