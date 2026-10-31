package app.jarvis.core.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.jarvis.core.MockServer;
import app.jarvis.core.log.Journal;
import app.jarvis.core.util.CancelToken;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class HedgedChatTest {
    private static LlmConfig cfg(MockServer s) {
        LlmConfig c = new LlmConfig();
        c.baseUrl = s.base();
        c.apiKey = "k";
        c.maxRetries = 0;
        c.idleTimeoutMs = 8000;
        return c;
    }

    private static LlmRequest req(String model) {
        LlmRequest r = new LlmRequest();
        r.model = model;
        r.messages.add(ChatMessage.user("привет"));
        return r;
    }

    private static MockServer server(final String text, final long delayBeforeMs, final AtomicInteger hits) throws IOException {
        return new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                hits.incrementAndGet();
                try { Thread.sleep(delayBeforeMs); } catch (InterruptedException ignored) { }
                MockServer.sse(x, 0, MockServer.chunkText(text), MockServer.chunkFinish("stop"), "[DONE]");
            }
        });
    }

    private static MockServer failing(final AtomicInteger hits, final long delayMs) throws IOException {
        return new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                hits.incrementAndGet();
                try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { }
                MockServer.json(x, 500, "{\"error\":{\"message\":\"boom\"}}");
            }
        });
    }

    private final OpenAiClient client = new OpenAiClient(Journal.NOP);

    @Test public void slowPrimaryFastBackupBackupWinsAndPrimaryOutputIsDropped() throws Exception {
        AtomicInteger ha = new AtomicInteger(), hb = new AtomicInteger();
        try (MockServer a = server("ПЕРВЫЙ", 1500, ha); MockServer b = server("Запасной.", 0, hb)) {
            final List<String> deltas = Collections.synchronizedList(new ArrayList<String>());
            HedgedChat.Info info = new HedgedChat.Info();
            long t0 = System.nanoTime();
            LlmResult r = HedgedChat.chat(client, cfg(a), req("m1"), cfg(b), req("m2"), 300, new LlmListener() {
                @Override public void onTextDelta(String d) { deltas.add(d); }
            }, new CancelToken(), info);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            assertEquals("Запасной.", r.content);
            assertEquals("[Запасной.]", deltas.toString());
            assertTrue(info.backupStarted && info.backupWon);
            assertTrue("answer must not wait for the slow primary: " + ms, ms < 1200);
            Thread.sleep(1700);                                   // the primary finally answers: nothing may leak into the listener
            assertEquals("[Запасной.]", deltas.toString());
            assertEquals(1, ha.get());
            assertEquals(1, hb.get());
        }
    }

    @Test public void fastPrimaryNeverStartsTheBackup() throws Exception {
        AtomicInteger ha = new AtomicInteger(), hb = new AtomicInteger();
        try (MockServer a = server("Основной.", 0, ha); MockServer b = server("Запасной.", 0, hb)) {
            HedgedChat.Info info = new HedgedChat.Info();
            LlmResult r = HedgedChat.chat(client, cfg(a), req("m1"), cfg(b), req("m2"), 500, null, new CancelToken(), info);
            assertEquals("Основной.", r.content);
            assertFalse(info.backupStarted);
            Thread.sleep(700);
            assertEquals(0, hb.get());
        }
    }

    @Test public void bothFastAfterTheHedgeTimePrimaryStillCountsFirstDelivered() throws Exception {
        AtomicInteger ha = new AtomicInteger(), hb = new AtomicInteger();
        try (MockServer a = server("Основной.", 400, ha); MockServer b = server("Запасной.", 380, hb)) {
            final List<String> deltas = Collections.synchronizedList(new ArrayList<String>());
            LlmResult r = HedgedChat.chat(client, cfg(a), req("m1"), cfg(b), req("m2"), 100, new LlmListener() {
                @Override public void onTextDelta(String d) { deltas.add(d); }
            }, new CancelToken(), null);
            assertEquals(1, deltas.size());                       // exactly one route delivers, never both
            assertEquals(deltas.get(0), r.content);
        }
    }

    @Test public void primaryErrorBeforeTheHedgeTimeIsRaisedAndBackupIsNotUsed() throws Exception {
        AtomicInteger ha = new AtomicInteger(), hb = new AtomicInteger();
        try (MockServer a = failing(ha, 0); MockServer b = server("Запасной.", 0, hb)) {
            try {
                HedgedChat.chat(client, cfg(a), req("m1"), cfg(b), req("m2"), 2000, null, new CancelToken(), null);
                fail("expected the primary's error");
            } catch (LlmException e) {
                assertEquals(LlmException.Kind.SERVER, e.kind);
            }
            assertEquals(0, hb.get());
        }
    }

    @Test public void backupFailsPrimaryAnswersLate() throws Exception {
        AtomicInteger ha = new AtomicInteger(), hb = new AtomicInteger();
        try (MockServer a = server("Основной.", 700, ha); MockServer b = failing(hb, 0)) {
            LlmResult r = HedgedChat.chat(client, cfg(a), req("m1"), cfg(b), req("m2"), 200, null, new CancelToken(), null);
            assertEquals("Основной.", r.content);
            assertEquals(1, hb.get());
        }
    }

    @Test public void bothFailThrows() throws Exception {
        AtomicInteger ha = new AtomicInteger(), hb = new AtomicInteger();
        try (MockServer a = failing(ha, 500); MockServer b = failing(hb, 0)) {
            try {
                HedgedChat.chat(client, cfg(a), req("m1"), cfg(b), req("m2"), 100, null, new CancelToken(), null);
                fail("expected failure");
            } catch (LlmException e) {
                assertEquals(LlmException.Kind.SERVER, e.kind);
            }
        }
    }

    @Test public void cancelStopsBothRoutes() throws Exception {
        AtomicInteger ha = new AtomicInteger(), hb = new AtomicInteger();
        try (MockServer a = server("A", 3000, ha); MockServer b = server("B", 3000, hb)) {
            final CancelToken c = new CancelToken();
            new Thread(new Runnable() { @Override public void run() { try { Thread.sleep(400); } catch (InterruptedException ignored) { } c.cancel("test"); } }).start();
            long t0 = System.nanoTime();
            try {
                HedgedChat.chat(client, cfg(a), req("m1"), cfg(b), req("m2"), 100, null, c, null);
                fail("expected cancel");
            } catch (LlmException e) {
                assertEquals(LlmException.Kind.CANCELLED, e.kind);
            }
            assertTrue((System.nanoTime() - t0) / 1_000_000L < 2000);
        }
    }
}
