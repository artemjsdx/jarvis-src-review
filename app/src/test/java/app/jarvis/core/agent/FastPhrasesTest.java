package app.jarvis.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.net.LatencyBook;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;

public class FastPhrasesTest {
    @Test public void closedSetIsAboutTwentyFiveToFortyShortPhrases() {
        List<String> all = FastPhrases.all();
        assertTrue("size " + all.size(), all.size() >= 25 && all.size() <= 40);
        Set<String> uniq = new HashSet<>(all);
        assertEquals(all.size(), uniq.size());
        for (String p : all) assertTrue(p, p.length() <= 40 && p.endsWith(".") || p.endsWith("!") || p.endsWith("."));
    }

    @Test public void neverTheSamePhraseTwiceInARow() {
        FastPhrases ph = new FastPhrases();
        String prev = "";
        for (int i = 0; i < 200; i++) {
            String s = ph.pick(FastPhrases.Cat.DONE);
            assertNotEquals(prev, s);
            assertTrue(FastPhrases.contains(s));
            prev = s;
        }
        assertEquals("Создал папку.", ph.pick(FastPhrases.Cat.FOLDER));
        assertEquals("Создал папку.", ph.pick(FastPhrases.Cat.FOLDER));
    }

    @Test public void hintsExistOnlyForSuccessAndComeFromTheSet() {
        FastPhrases ph = new FastPhrases();
        String[] tools = {"music_play", "music_play_latest_received", "music_pause", "music_resume", "music_stop", "music_next", "music_prev", "app_open", "fs_mkdir", "fs_write", "note_create"};
        for (String t : tools) {
            String h = SpokenHints.forTool(t, new JSONObject(), ToolResult.ok("x"), ph);
            assertTrue(t + " -> " + h, h != null && FastPhrases.contains(h));
            assertEquals(t, null, SpokenHints.forTool(t, new JSONObject(), ToolResult.fail("нет"), ph));
        }
        assertEquals("", SpokenHints.forTool("speech_stop", new JSONObject(), ToolResult.ok("x"), ph));
        assertEquals(null, SpokenHints.forTool("app_setting", new JSONObject().put("key", "tts.speed"), ToolResult.ok("x"), ph));   // a read must say the value
        assertTrue(FastPhrases.contains(SpokenHints.forTool("app_setting", new JSONObject().put("key", "k").put("value", "1"), ToolResult.ok("x"), ph)));
        assertEquals(null, SpokenHints.forTool("web_search", new JSONObject(), ToolResult.ok("x"), ph));
        assertEquals("Своё.", SpokenHints.forTool("web_search", new JSONObject(), ToolResult.ok("x").spoken("Своё."), ph));
        assertTrue(FastPhrases.contains(SpokenHints.forTool("app_open", new JSONObject().put("screen", "files"), ToolResult.ok("x"), ph)));
    }

    @Test public void confirmPolicyFollowsModeAndRecentLatency() {
        LatencyBook b = new LatencyBook(null);
        assertTrue(ConfirmPolicy.useFast(ConfirmPolicy.Mode.FAST, b, "p"));
        assertFalse(ConfirmPolicy.useFast(ConfirmPolicy.Mode.LIVE, b, "p"));
        assertFalse("no data yet: live", ConfirmPolicy.useFast(ConfirmPolicy.Mode.AUTO, b, "p"));
        for (long ms : new long[]{900, 1200, 1100, 1000, 1300}) b.record("llm:p", ms);
        assertFalse(ConfirmPolicy.useFast(ConfirmPolicy.Mode.AUTO, b, "p"));
        for (long ms : new long[]{3500, 4200, 3900}) b.record("llm:p", ms);
        assertTrue("median of the last 5 is 3500", ConfirmPolicy.useFast(ConfirmPolicy.Mode.AUTO, b, "p"));
        LatencyBook w = new LatencyBook(null);
        for (long ms : new long[]{2100, 2500, 1900}) w.record("llm.headers:q", ms);
        assertTrue("weak network by headers", ConfirmPolicy.useFast(ConfirmPolicy.Mode.AUTO, w, "q"));
        assertEquals(ConfirmPolicy.Mode.AUTO, ConfirmPolicy.Mode.parse(""));
        assertEquals(ConfirmPolicy.Mode.FAST, ConfirmPolicy.Mode.parse("fast"));
    }
}
