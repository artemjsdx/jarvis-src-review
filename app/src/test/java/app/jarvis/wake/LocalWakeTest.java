package app.jarvis.wake;

import static org.junit.Assert.*;

import app.jarvis.core.audio.WakeMatcher;
import app.jarvis.core.voice.PcmAudio;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Grammar/decoding-decision tests plus the no-crash guarantees when vosk is unusable (no natives, no pack). */
public class LocalWakeTest {

    private static List<String> jsons(String... chunks) { return Arrays.asList(chunks); }

    private static LocalWake.Decision decide(List<String> chunks) {
        return LocalWake.decide(chunks, WakeMatcher.defaultRu(), 0, 8000);
    }

    private static final String GRM_HIT =
        "{\"result\":[{\"conf\":1.0,\"end\":2.79,\"start\":2.17,\"word\":\"джарвис\"}],\"text\":\"джарвис\"}";
    private static final String GRM_UNK =
        "{\"result\":[{\"conf\":1.0,\"end\":1.0,\"start\":0.6,\"word\":\"[unk]\"}],\"text\":\"[unk]\"}";

    @Test public void grammarContainsVariantsAndUnk() {
        String g = LocalWake.buildGrammar(WakeMatcher.defaultRu().phraseList());
        assertTrue(g.startsWith("[") && g.endsWith("]"));
        // NB: "жарвис" is absent on purpose — WakeMatcher folds it with "джарвис" (дж→ж) as a duplicate,
        // and the grammar token "джарвис" catches the same acoustic event.
        for (String w : new String[]{"[unk]", "джарвис", "джарвиз", "джервис", "джарвес", "ярвис", "эй", "привет", "хей", "jarvis"})
            assertTrue("grammar must contain " + w, g.contains("\"" + w + "\""));
        assertEquals(10, new org.json.JSONArray(g).length()); // [unk] + 9 distinct words from defaultRu
    }

    @Test public void grammarEscapesQuotes() {
        String g = LocalWake.buildGrammar(Arrays.asList("say \"hi\""));
        assertTrue(g.contains("\\\"hi\\\""));   // the quote became \" inside the JSON token
        assertFalse(g.contains("\"\"hi"));      // no raw unescaped quote leaked in
    }

    @Test public void decideFindsWakeWordWithPosition() {
        LocalWake.Decision d = decide(jsons(GRM_HIT));
        assertTrue(d.found);
        assertEquals(2170, d.positionMs);
        assertEquals("джарвис", d.text);
        assertEquals(1.0f, d.score, 0.001f);
    }

    @Test public void decideAggregatesChunks() {
        LocalWake.Decision d = decide(jsons(GRM_HIT, GRM_UNK));
        assertTrue(d.found);
        assertEquals("джарвис [unk]", d.text);
        assertEquals(2170, d.positionMs); // position still from the wake word
    }

    @Test public void decideRejectsUnknownOnlySpeech() {
        LocalWake.Decision d = decide(jsons(GRM_UNK, GRM_UNK));
        assertFalse(d.found);
        assertEquals(-1f, d.score, 0.001f);
    }

    @Test public void decideRejectsDeepPosition() {
        // wake word buried 4 words deep: the shared position rule must reject it
        String deep = "{\"result\":["
            + "{\"conf\":1,\"start\":0.1,\"end\":0.2,\"word\":\"[unk]\"},"
            + "{\"conf\":1,\"start\":0.3,\"end\":0.4,\"word\":\"[unk]\"},"
            + "{\"conf\":1,\"start\":0.5,\"end\":0.6,\"word\":\"[unk]\"},"
            + "{\"conf\":1,\"start\":0.7,\"end\":0.8,\"word\":\"[unk]\"},"
            + "{\"conf\":1,\"start\":1.0,\"end\":1.6,\"word\":\"джарвис\"}],"
            + "\"text\":\"[unk] [unk] [unk] [unk] джарвис\"}";
        LocalWake.Decision d = decide(jsons(deep));
        assertFalse("deep wake word must not trigger", d.found);
        assertEquals("[unk] [unk] [unk] [unk] джарвис", d.text);
    }

    @Test public void decideHandlesTwoWordPhrase() {
        String ej = "{\"result\":["
            + "{\"conf\":1,\"start\":0.0,\"end\":0.3,\"word\":\"эй\"},"
            + "{\"conf\":1,\"start\":0.4,\"end\":1.0,\"word\":\"джарвис\"}],"
            + "\"text\":\"эй джарвис\"}";
        LocalWake.Decision d = decide(jsons(ej));
        assertTrue(d.found);
        assertEquals("position from the wake word, not from «эй»", 400, d.positionMs);
    }

    @Test public void decideSubtractsCumulativeClockOffset() {
        // vosk timestamps keep running across utterances: the wake word at 2.17 s of the current utterance
        // carries the cumulative timestamp 32.17 s when 30 s were fed before it
        String shifted = GRM_HIT.replace("\"start\":2.17", "\"start\":32.17").replace("\"end\":2.79", "\"end\":32.79");
        LocalWake.Decision d = LocalWake.decide(jsons(shifted), WakeMatcher.defaultRu(), 30_000, 4000);
        assertTrue(d.found);
        assertEquals(2170, d.positionMs);   // 32170 - 30000
        d = LocalWake.decide(jsons(shifted), WakeMatcher.defaultRu(), 32_000, 4000);
        assertEquals("still inside the utterance", 170, d.positionMs);
        d = LocalWake.decide(jsons(shifted), WakeMatcher.defaultRu(), 33_000, 4000);
        assertEquals("clamped to utterance start", 0, d.positionMs);
        String late = "{\"result\":[{\"conf\":1.0,\"end\":36.5,\"start\":35.9,\"word\":\"джарвис\"}],\"text\":\"джарвис\"}";
        d = LocalWake.decide(jsons(late), WakeMatcher.defaultRu(), 30_000, 4000);
        assertEquals("clamped to utterance end", 4000, d.positionMs);
    }

    @Test public void decideSurvivesGarbage() {
        assertFalse(decide(jsons("not json", "", null)).found);
    }

    @Test public void detectRejectsWrongRateWithoutLoading() {
        LocalWake w = new LocalWake(new File("/nonexistent/wake"));
        LocalWake.Detection d = w.detect(new PcmAudio(new byte[100], 8000));
        assertFalse(d.ok());
        assertFalse(d.found);
        assertTrue(d.error.contains("16"));
    }

    @Test public void detectWithMissingPackReturnsRussianErrorNotCrash() {
        LocalWake w = new LocalWake(new File("/nonexistent/wake"));
        assertTrue("availability reported up front", w.availabilityProblem().contains("не установлена"));
        LocalWake.Detection d = w.detect(new PcmAudio(new byte[3200], 16000));
        assertFalse(d.ok());
        assertFalse(d.found);
        assertFalse("user-safe Russian message", d.error.isEmpty());
        assertTrue(d.error, d.error.matches("(?s).*[а-яА-Я].*"));
    }

    @Test public void unloadIsIdempotent() {
        LocalWake w = new LocalWake(new File("/nonexistent/wake"));
        w.unload();
        w.unload();
        assertFalse(w.isLoaded());
    }
}
