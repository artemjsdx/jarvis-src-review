package app.jarvis.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WakeMatcherTest {

    private final WakeMatcher m = WakeMatcher.defaultRu();

    @Test public void exactWakeWordAlone() {
        WakeMatcher.Match r = m.match("джарвис");
        assertTrue(r.found);
        assertEquals("", r.command);
        assertEquals(1f, r.score, 0.01f);
        assertTrue(r.positionOk);
    }

    @Test public void withCommand() {
        WakeMatcher.Match r = m.match("Джарвис, включи музыку");
        assertTrue(r.found);
        assertEquals("включи музыку", r.command);
        assertEquals("Джарвис", r.heard);
    }

    @Test public void asrMishearingsMatch() {
        for (String t : new String[]{"жарвис папочка проснулся", "джарвиз", "джервис стоп", "джарвес", "ярвис включи свет", "ДЖАРВИС!", "эй джарвис как погода"}) {
            WakeMatcher.Match r = m.match(t);
            assertTrue("must match: " + t, r.found);
        }
        assertEquals("папочка проснулся", m.match("жарвис папочка проснулся").command);
        assertEquals("стоп", m.match("джервис стоп").command);
        assertEquals("как погода", m.match("эй джарвис как погода").command);
    }

    @Test public void greetingPhrasesStripGreetingFromCommand() {
        WakeMatcher.Match r = m.match("ну привет джарвис что там");
        assertTrue(r.found);
        assertEquals("что там", r.command);
        assertEquals("привет джарвис", r.heard);
        r = m.match("хей джарвис который час");
        assertTrue(r.found);
        assertEquals("который час", r.command);
    }

    @Test public void latinJarvisMatches() {
        WakeMatcher.Match r = m.match("Jarvis open the pod bay doors");
        assertTrue(r.found);
        assertEquals("open the pod bay doors", r.command);
    }

    @Test public void falsePositivesAreRejected() {
        for (String t : new String[]{"гарвис", "карвис", "дарвин", "джаз", "сервис", "вис", "автосервис", "жара", "джигурда"}) {
            assertFalse("must not match: " + t, m.match(t).found);
        }
    }

    @Test public void wakeWordLateInALongSentenceIsIgnored() {
        WakeMatcher.Match r = m.match("и потом он рассказал что джарвис ему помог вчера вечером с домашкой");
        assertFalse(r.found);
        assertFalse(r.positionOk);
    }

    @Test public void shortLeadingWordsStillOk() {
        assertTrue(m.match("а джарвис погоду скажи").found);
        assertTrue(m.match("ну ну джарвис давай").found);
    }

    @Test public void emptyAndNullAreSafe() {
        assertFalse(m.match(null).found);
        assertFalse(m.match("").found);
        assertFalse(m.match("...").found);
    }

    @Test public void normalizeDoesTheDocumentedFolding() {
        assertEquals("елка жарвис", WakeMatcher.normalize("Ёлка, Джарвис!"));
        assertEquals("жарвис", WakeMatcher.normalize("ДЖАРВИС"));
        assertEquals("жарвиз", WakeMatcher.normalize("Джарвиз,"));
        // Latin homoglyphs fold to Cyrillic, so both spellings meet in the same normalized form
        assertEquals(WakeMatcher.normalize("Jarvis"), WakeMatcher.normalize("jarvis"));
        assertEquals("jаrviс", WakeMatcher.normalize("jarvis"));
    }

    @Test public void customPhrasesWork() {
        WakeMatcher custom = new WakeMatcher(java.util.Arrays.asList("алиса", "алиса привет"));
        assertTrue(custom.match("Алиса, включи свет").found);
        assertTrue(custom.match("алиса привет как дела").found);
        assertFalse(custom.match("джарвис включи свет").found);
    }

    @Test public void scoreReflectsDistance() {
        float exact = m.match("джарвис").score;
        float fuzzy = m.match("джарвисс").score; // trailing junk: cost 1 of 6
        assertTrue("exact " + exact + " > fuzzy " + fuzzy, exact > fuzzy);
        assertTrue(fuzzy >= 0.5f);
    }
}
