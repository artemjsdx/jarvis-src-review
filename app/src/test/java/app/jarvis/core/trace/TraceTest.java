package app.jarvis.core.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;

public class TraceTest {
    /** Manual clock: nanos advance only when the test says so. */
    static final class FakeClock implements TurnTrace.Clock {
        long ns = 1_000_000_000L;
        @Override public long nanos() { return ns; }
        @Override public long wallMs() { return 1_700_000_000_000L + ns / 1_000_000L; }
        void ms(long d) { ns += d * 1_000_000L; }
    }

    private static TurnTrace voiceTurn(TraceStore st, FakeClock c) {
        TurnTrace t = st.create("voice");
        st.publish(t);
        t.mark(TurnTrace.SPEECH_END);
        c.ms(650); t.mark(TurnTrace.SPEECH_END_DETECTED);
        t.mark(TurnTrace.STT_START);
        c.ms(900); t.mark(TurnTrace.STT_FINAL);
        c.ms(20); t.mark(TurnTrace.SUBMIT); t.mark(TurnTrace.LLM_REQUEST);
        c.ms(500); t.mark(TurnTrace.LLM_FIRST_TOKEN); t.mark(TurnTrace.LLM_FIRST_TEXT);
        c.ms(10); t.mark(TurnTrace.TTS_REQUEST); t.mark(TurnTrace.TTS_SYNTH_START);
        c.ms(400); t.mark(TurnTrace.TTS_FIRST_AUDIO);
        c.ms(60); t.mark(TurnTrace.PLAY_START);
        return t;
    }

    @Test
    public void spansAndSummaryOfAVoiceTurn() {
        FakeClock c = new FakeClock();
        TraceStore st = new TraceStore(null, null, c);
        TurnTrace t = voiceTurn(st, c);
        java.util.Map<String, Long> sp = t.spans();
        assertEquals(650L, (long) sp.get("endpoint"));
        assertEquals(900L, (long) sp.get("stt"));
        assertEquals(20L, (long) sp.get("stt_to_llm"));
        assertEquals(500L, (long) sp.get("llm_ttft"));
        assertEquals(400L, (long) sp.get("tts_total"));
        assertEquals(400L, (long) sp.get("tts_ttfa"));
        assertEquals(60L, (long) sp.get("play_gap"));
        assertEquals(2540L, (long) sp.get("to_sound"));
        assertEquals("Ход 2,5 с до звука: STT 0,9 | LLM 0,5 | TTS 0,4 | пауза 0,7", t.summaryRu());
    }

    @Test
    public void marksAreFirstWinsAndLastWinsWhereStated() {
        FakeClock c = new FakeClock();
        TurnTrace t = new TurnTrace(1, "text", c);
        t.mark(TurnTrace.LLM_REQUEST);
        c.ms(100); t.mark(TurnTrace.LLM_REQUEST);
        assertEquals(-1, t.span(TurnTrace.LLM_REQUEST, TurnTrace.LLM_DONE));
        t.markLast(TurnTrace.LLM_DONE);
        assertEquals(100, t.span(TurnTrace.LLM_REQUEST, TurnTrace.LLM_DONE));
        c.ms(50); t.markLast(TurnTrace.LLM_DONE);
        assertEquals(150, t.span(TurnTrace.LLM_REQUEST, TurnTrace.LLM_DONE));
    }

    @Test
    public void jsonSchemaHasMarksSpansGroupsAndTools() throws Exception {
        FakeClock c = new FakeClock();
        TraceStore st = new TraceStore(null, null, c);
        TurnTrace t = voiceTurn(st, c);
        t.put("stt", "provider", "google");
        t.put("stt", "audio_ms", 2100);
        t.put("llm", "model", "inception/mercury-3");
        t.add("tts", "chars", 12);
        t.add("tts", "chars", 30);
        int h = t.toolStart("music_play");
        c.ms(300);
        t.toolEnd(h, true);
        JSONObject j = t.toJson();
        assertEquals("voice", j.getString("source"));
        assertEquals(0, j.getJSONObject("marks").getLong("speech_end"));
        assertEquals(650, j.getJSONObject("marks").getLong("speech_end_detected"));
        assertEquals("google", j.getJSONObject("stt").getString("provider"));
        assertEquals(42, j.getJSONObject("tts").getLong("chars"));
        assertEquals("music_play", j.getJSONArray("tools").getJSONObject(0).getString("name"));
        assertEquals(300, j.getJSONArray("tools").getJSONObject(0).getLong("ms"));
        assertTrue(j.getJSONObject("spans").getLong("to_sound") > 0);
        assertTrue(j.getString("summary").startsWith("Ход "));
    }

    @Test
    public void turnFinishesWhenAgentDoneAndSpeechQuiet() {
        FakeClock c = new FakeClock();
        TraceStore st = new TraceStore(null, null, c);
        final List<TurnTrace> done = new ArrayList<>();
        st.setListener(new TraceStore.Listener() { @Override public void onTurnFinished(TurnTrace t) { done.add(t); } });
        TurnTrace t = voiceTurn(st, c);
        st.speakingChanged(true);
        st.agentDone(t);
        assertTrue("speech still playing", done.isEmpty());
        assertSame(t, st.current());
        c.ms(1500);
        st.speakingChanged(false);
        assertEquals(1, done.size());
        assertNull(st.current());
        assertTrue(t.has(TurnTrace.PLAY_END));
        assertEquals(1500, t.span(TurnTrace.PLAY_START, TurnTrace.PLAY_END) - 0);
        assertEquals(1, st.size());
    }

    private static void assertSame(Object a, Object b) { org.junit.Assert.assertSame(a, b); }

    @Test
    public void silenceBetweenPiecesDoesNotEndTheTurn() {
        FakeClock c = new FakeClock();
        TraceStore st = new TraceStore(null, null, c);
        TurnTrace t = voiceTurn(st, c);
        st.speakingChanged(true);
        c.ms(800);
        st.speakingChanged(false);          // piece 1 finished, model still writing
        assertNotNull(st.current());
        st.speakingChanged(true);
        c.ms(500);
        st.agentDone(t);
        assertNotNull(st.current());
        st.speakingChanged(false);
        assertNull(st.current());
        assertEquals(1, st.size());
    }

    @Test
    public void textTurnWithoutSpeechFinishesAtAgentDone() {
        FakeClock c = new FakeClock();
        TraceStore st = new TraceStore(null, null, c);
        TurnTrace t = st.publish(st.create("text"));
        t.mark(TurnTrace.SUBMIT); t.mark(TurnTrace.LLM_REQUEST);
        c.ms(700); t.mark(TurnTrace.LLM_FIRST_TEXT);
        c.ms(300); t.markLast(TurnTrace.LLM_DONE);
        st.agentDone(t);
        assertEquals(1, st.size());
        assertEquals("Ход 0,7 с до ответа: LLM 0,7", t.summaryRu());
    }

    @Test
    public void newTurnFinishesTheOpenOneAsIncomplete() throws Exception {
        FakeClock c = new FakeClock();
        TraceStore st = new TraceStore(null, null, c);
        TurnTrace a = st.publish(st.create("voice"));
        a.mark(TurnTrace.STT_START);
        st.publish(st.create("voice"));
        assertEquals(1, st.size());
        assertFalse(st.recent(1).get(0).getBoolean("complete"));
    }

    @Test
    public void recentIsBoundedAndOldestFirst() {
        FakeClock c = new FakeClock();
        TraceStore st = new TraceStore(null, null, c);
        for (int i = 0; i < 130; i++) {
            TurnTrace t = st.publish(st.create("text"));
            t.mark(TurnTrace.SUBMIT);
            st.agentDone(t);
        }
        assertEquals(TraceStore.CAPACITY, st.size());
        List<JSONObject> r = st.recent(5);
        assertEquals(5, r.size());
        assertEquals(130, r.get(4).optLong("id"));
        assertEquals(126, r.get(0).optLong("id"));
        assertEquals(TraceStore.CAPACITY, st.recent(1000).size());
        assertEquals(0, st.recent(0).size());
    }

    @Test
    public void handoffIsTakenOnceAndExpires() {
        FakeClock c = new FakeClock();
        TraceStore st = new TraceStore(null, null, c);
        TurnTrace t = st.create("voice");
        st.handoff(t);
        assertSame(t, st.takeHandoff());
        assertNull(st.takeHandoff());
        st.handoff(t);
        c.ms(61_000);
        assertNull(st.takeHandoff());
    }

    @Test
    public void fileGetsOneJsonLinePerTurnAndIsCapped() throws Exception {
        File dir = Files.createTempDirectory("trace").toFile();
        FakeClock c = new FakeClock();
        TraceStore st = new TraceStore(dir, new java.util.concurrent.Executor() { @Override public void execute(Runnable r) { r.run(); } }, c);
        for (int i = 0; i < 3; i++) {
            TurnTrace t = st.publish(st.create("text"));
            t.mark(TurnTrace.SUBMIT);
            st.agentDone(t);
        }
        File f = new File(dir, "turns.jsonl");
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        assertEquals(3, new JSONObject(lines.get(2)).getLong("id"));
        // grow past the cap: file never exceeds it and stays valid JSON lines
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 3000; i++) big.append('x');
        int n = (int) (TraceStore.MAX_FILE_BYTES / 3000) + 50;
        for (int i = 0; i < n; i++) {
            TurnTrace t = st.publish(st.create("text"));
            t.put("pad", "s", big.toString());
            st.agentDone(t);
        }
        assertTrue("size " + f.length(), f.length() <= TraceStore.MAX_FILE_BYTES);
        List<String> after = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        assertTrue(after.size() > 100);
        for (String l : after) new JSONObject(l);
        assertEquals(3 + n, new JSONObject(after.get(after.size() - 1)).getLong("id"));
    }

    @Test
    public void concurrentHooksDoNotCorruptTheTrace() throws Exception {
        TraceStore st = new TraceStore(null, null, TurnTrace.SYSTEM);
        final TurnTrace t = st.publish(st.create("voice"));
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicInteger errors = new AtomicInteger();
        Thread[] ts = new Thread[8];
        for (int i = 0; i < ts.length; i++) {
            final int n = i;
            ts[i] = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        go.await();
                        for (int k = 0; k < 500; k++) {
                            t.mark("m" + (k % 7));
                            t.add("tts", "chars", 1);
                            int h = t.toolStart("t" + n);
                            t.toolEnd(h, true);
                            t.toJson();
                        }
                    } catch (Throwable e) { errors.incrementAndGet(); }
                }
            });
            ts[i].start();
        }
        go.countDown();
        for (Thread x : ts) x.join();
        assertEquals(0, errors.get());
        assertEquals(8 * 500, t.toJson().getJSONObject("tts").optLong("chars"));
    }

    @Test
    public void globalStoreIsUsableWithoutInstall() {
        TraceStore g = TraceStore.global();
        TurnTrace t = g.publish(g.create("text"));
        t.mark(TurnTrace.SUBMIT);
        g.agentDone(t);
        assertTrue(g.size() >= 1);
    }
}
