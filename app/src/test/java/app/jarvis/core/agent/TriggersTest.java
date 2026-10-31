package app.jarvis.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.junit.Test;

public class TriggersTest {
    private static Triggers one(String phrase, Triggers.Mode mode) {
        return new Triggers(Collections.singletonList(new Triggers.Trigger("x", Collections.singletonList(phrase), mode,
            "music.playFavorite", "Поздоровайся", true)));
    }

    /** (utterance as recognised, phrase in the trigger, should match) */
    @Test public void matcherTable() {
        String[][] cases = {
            {"я проснулся", "я проснулся", "1"},
            {"Я ПРОСНУЛСЯ!", "я проснулся", "1"},
            {"Джарвис, я проснулся", "я проснулся", "1"},
            {"жарвис я проснулся", "я проснулся", "1"},
            {"джервис, доброе утро", "доброе утро", "1"},
            {"Jarvis я проснулся", "я проснулся", "1"},
            {"я проснулся, Джарвис", "я проснулся", "1"},
            {"я проснулся сегодня рано", "я проснулся", "1"},
            {"ну я проснулся", "я проснулся", "0"},
            {"ёлка сегодня", "елка", "1"},
            {"Ёлка сегодня", "ёлка", "1"},
            {"включи музыку пожалуйста", "включи музыку", "1"},
            {"я проснулся вчера", "я проснулся", "1"},
            {"с добрым утром", "с добрым утром", "1"},
            {"папочка проснулся", "папочка проснулся", "1"},
            {"папочка проснулся рано", "папочка проснулся", "1"},
            {"я не проснулся", "я проснулся", "0"},
            {"проснулся", "я проснулся", "0"},
            {"я проснулся", "проснулся", "0"},
            {"я почти проснулся", "я проснулся", "0"},
            {"доброе утро всем друзьям", "доброе утро", "1"},
            {"добрый вечер", "доброе утро", "0"},
            {"музыка", "включи музыку", "0"},
            {"какая сегодня погода", "погода", "0"},
            {"погода", "погода сегодня", "0"},
            {"проснулся и встал", "я проснулся", "0"},
            {"я проснулся и я встал и умылся и поел и вышел на улицу и пошёл на работу", "я проснулся", "0"},
            {"стоп", "стоп", "1"},
            {"стоп музыка", "стоп", "1"},
            {"остановись", "стоп", "0"},
            {"поставь таймер на пять минут", "поставь таймер", "1"},
            {"поставь будильник", "поставь таймер", "0"},
            {"Джарвис включи любимую песню", "включи любимую песню", "1"},
            {"включи любимую песню", "любимую песню", "0"},
            {"Джарвис, ЖАРВИС, я проснулся", "я проснулся", "0"},
            {"я встала", "я встала", "1"},
            {"я встала рано", "я встал", "0"},
            {"привет", "", "0"},
            {"", "я проснулся", "0"},
            {"?!", "я проснулся", "0"},
            {"проснулся проснулся", "проснулся", "1"},
            {"ты проснулся?", "проснулся", "0"},
            {"доброе утро, Джарвис", "доброе утро", "1"},
            {"скажи доброе утро", "доброе утро", "0"},
            {"музыка играет", "музыка", "1"},
            {"ёж", "еж", "1"},
        };
        int n = 0;
        for (String[] c : cases) {
            n++;
            boolean want = c[2].equals("1");
            Triggers t = one(c[1], Triggers.Mode.DIRECT);
            boolean got = t.match(c[0]) != null;
            assertEquals("case " + n + ": «" + c[0] + "» vs «" + c[1] + "»", want, got);
        }
        assertTrue(n >= 40);
    }

    @Test public void wakeWordPrefixVariantsAreStripped() {
        Triggers t = one("я проснулся", Triggers.Mode.DIRECT);
        for (String w : new String[]{"Джарвис", "джарвис", "Жарвис", "ДЖЕРВИС", "jarvis", "Jarvis,"}) {
            assertNotNull(w, t.match(w + ", я проснулся"));
        }
    }

    @Test public void disabledTriggersNeverMatch() {
        Triggers t = new Triggers(Collections.singletonList(new Triggers.Trigger("x", Collections.singletonList("тест"),
            Triggers.Mode.DIRECT, "morning", "", false)));
        assertNull(t.match("тест"));
    }

    @Test public void firstMatchingTriggerWinsAndModeIsKept() {
        Triggers t = new Triggers(Arrays.asList(
            new Triggers.Trigger("a", Collections.singletonList("погода"), Triggers.Mode.LLM, "", "Ответь про погоду коротко", true),
            new Triggers.Trigger("b", Collections.singletonList("погода"), Triggers.Mode.DIRECT, "morning", "", true)));
        Triggers.Trigger hit = t.match("погода сегодня");
        assertEquals("a", hit.id);
        assertFalse(hit.direct());
        assertEquals("Ответь про погоду коротко", hit.instruction);
    }

    @Test public void longUtterancesAreNotTriggers() {
        Triggers t = one("я", Triggers.Mode.DIRECT);
        assertNull(t.match("я один два три четыре пять шесть семь восемь девять десять"));
        assertNotNull(t.match("я один два три четыре пять шесть семь восемь"));
    }

    @Test public void actionsAreValidated() {
        HashSet<String> tools = new HashSet<>(Arrays.asList("music_play", "memory_save"));
        assertNull(Triggers.checkAction("morning", tools));
        assertNull(Triggers.checkAction("music.playFavorite", tools));
        assertNull(Triggers.checkAction("app.open:settings/music", tools));
        assertNull(Triggers.checkAction("tools.call:memory_save:{\"key\":\"а\",\"value\":\"б\"}", tools));
        assertNotNull(Triggers.checkAction("tools.call:rm_rf:{}", tools));
        assertNotNull(Triggers.checkAction("tools.call:memory_save:[1,2]", tools));
        assertNotNull(Triggers.checkAction("tools.call:memory_save:{oops", tools));
        assertNotNull(Triggers.checkAction("app.open:", tools));
        assertNotNull(Triggers.checkAction("speech.stop", tools));
        assertNotNull(Triggers.checkAction("", tools));
    }

    @Test public void presetMigrationWritesTheMorningTriggerOnce() throws Exception {
        File dir = Files.createTempDirectory("trig").toFile();
        File f = new File(dir, "triggers.json");
        Triggers first = Triggers.load(f);
        assertTrue(f.isFile());
        assertEquals(1, first.all().size());
        Triggers.Trigger morning = first.all().get(0);
        assertEquals("morning", morning.action);
        assertTrue(morning.direct());
        assertNotNull(first.match("доброе утро"));
        assertNotNull(first.match("Джарвис, я проснулась"));

        first.remove(morning.id);
        first.save(f);
        Triggers again = Triggers.load(f);
        assertEquals("a deleted preset must not come back", 0, again.all().size());
    }

    @Test public void saveAndLoadRoundTripsEveryField() throws Exception {
        File dir = Files.createTempDirectory("trig").toFile();
        File f = new File(dir, "triggers.json");
        Triggers t = new Triggers(Collections.<Triggers.Trigger>emptyList());
        t.upsert("", Arrays.asList("включи радио", "радио"), Triggers.Mode.DIRECT, "tools.call:music_play:{}", "", true);
        t.upsert("n1", Collections.singletonList("устал"), Triggers.Mode.LLM, "", "Спроси, нужен ли перерыв, и не списывай это в память", false);
        t.save(f);
        Triggers back = Triggers.load(f);
        assertEquals(2, back.all().size());
        Triggers.Trigger a = back.all().get(0);
        assertEquals(Arrays.asList("включи радио", "радио"), a.phrases);
        assertEquals("tools.call:music_play:{}", a.action);
        assertTrue(a.enabled);
        Triggers.Trigger b = back.all().get(1);
        assertEquals("n1", b.id);
        assertFalse(b.direct());
        assertFalse(b.enabled);
        assertEquals("Спроси, нужен ли перерыв, и не списывай это в память", b.instruction);
        assertNull(back.match("устал"));
        assertNotNull(back.match("радио"));
    }

    @Test public void phraseListIsSplitAndDeduplicated() {
        assertEquals(Arrays.asList("включи радио", "радио"), Triggers.splitPhrases("включи радио|радио"));
        assertEquals(Arrays.asList("а", "б", "в"), Triggers.splitPhrases(" а , б;в|а\n"));
        assertTrue(Triggers.splitPhrases(" | , ").isEmpty());
    }

    @Test public void corruptFileGivesNoTriggersInsteadOfCrashing() throws Exception {
        File dir = Files.createTempDirectory("trig").toFile();
        File f = new File(dir, "triggers.json");
        Files.write(f.toPath(), "не json".getBytes("UTF-8"));
        assertEquals(0, Triggers.load(f).all().size());
    }
}
