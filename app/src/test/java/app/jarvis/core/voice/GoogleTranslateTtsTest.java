package app.jarvis.core.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.util.CancelToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** GoogleTranslateTts against a local fake translate_tts endpoint. */
public class GoogleTranslateTtsTest {

    static final class FakeGtts implements AutoCloseable {
        final HttpServer server;
        final List<Map<String, String>> requests = new ArrayList<>();
        final AtomicInteger counter = new AtomicInteger();
        volatile boolean failLongWith400 = true;
        volatile int errorStatus = 0;

        FakeGtts() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/translate_tts", this::handle);
            server.start();
        }

        int port() { return server.getAddress().getPort(); }

        private void handle(HttpExchange ex) throws IOException {
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            synchronized (requests) { requests.add(q); }
            if (errorStatus != 0) {
                ex.sendResponseHeaders(errorStatus, -1);
                ex.close();
                return;
            }
            String text = q.get("q");
            if (failLongWith400 && text != null && text.length() > 190) {
                ex.sendResponseHeaders(400, -1);
                ex.close();
                return;
            }
            byte[] body = fakeMp3(counter.incrementAndGet());
            ex.getResponseHeaders().set("Content-Type", "audio/mpeg");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(body); }
        }

        static byte[] fakeMp3(int n) {
            byte[] b = new byte[100];
            b[0] = (byte) 'I';
            b[1] = (byte) 'D';
            b[2] = (byte) '3';
            for (int i = 3; i < b.length; i++) b[i] = (byte) (n * 7 + i);
            return b;
        }

        private static Map<String, String> query(String raw) {
            Map<String, String> out = new HashMap<>();
            if (raw == null) return out;
            for (String p : raw.split("&")) {
                int eq = p.indexOf('=');
                if (eq < 0) { out.put(urlDecode(p), ""); continue; }
                out.put(urlDecode(p.substring(0, eq)), urlDecode(p.substring(eq + 1)));
            }
            return out;
        }

        private static String urlDecode(String s) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '+') { bo.write(' '); }
                else if (c == '%' && i + 2 < s.length()) {
                    bo.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                } else bo.write(c);
            }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }

        @Override public void close() { server.stop(0); }
    }

    static final class RecordingSink implements AudioSink {
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        boolean began, ended, aborted;

        @Override public void begin(AudioSpec s) { began = true; }
        @Override public void write(byte[] b, int off, int len) { data.write(b, off, len); }
        @Override public void end() { ended = true; }
        @Override public void abort() { aborted = true; }
    }

    private static GoogleTranslateTts configured(FakeGtts s) {
        GoogleTranslateTts p = new GoogleTranslateTts();
        ProviderConfig cfg = new ProviderConfig();
        cfg.put("baseUrl", "http://127.0.0.1:" + s.port());
        p.configure(cfg);
        return p;
    }

    private static TtsRequest req(String text) {
        TtsRequest r = new TtsRequest();
        r.text = text;
        return r;
    }

    @Test
    public void shortTextSingleRequest() throws Exception {
        try (FakeGtts s = new FakeGtts()) {
            GoogleTranslateTts p = configured(s);
            RecordingSink sink = new RecordingSink();
            p.synthesize(req("Привет!"), sink, new CancelToken());
            assertTrue(sink.began && sink.ended);
            assertEquals(1, s.requests.size());
            Map<String, String> q = s.requests.get(0);
            assertEquals("Привет!", q.get("q"));
            assertEquals("ru", q.get("tl"));
            assertEquals("gtx", q.get("client"));
            assertTrue(sink.data.size() > 0);
            assertTrue(p.lastStatus().ok);
        }
    }

    @Test
    public void longTextSplitsAndConcatenates() throws Exception {
        String text = "Первое предложение про кота и тучу. Второе предложение, немного длиннее предыдущего! "
            + "Третье предложение; ещё длиннее чем второе и третье вместе взятые? "
            + "Четвёртое предложение без всякого смысла и продолжается дальше и дальше. ";
        assertTrue(text.length() > 190);
        try (FakeGtts s = new FakeGtts()) {
            GoogleTranslateTts p = configured(s);
            RecordingSink sink = new RecordingSink();
            p.synthesize(req(text), sink, new CancelToken());
            int n;
            synchronized (s.requests) { n = s.requests.size(); }
            assertEquals(GoogleTranslateTts.splitChunks(text, GoogleTranslateTts.MAX_CHUNK).size(), n);
            // every chunk ≤ 180 chars and the concatenation of per-request mp3s equals the sink
            synchronized (s.requests) {
                for (Map<String, String> q : s.requests) {
                    assertTrue(q.get("q").length() <= GoogleTranslateTts.MAX_CHUNK);
                }
            }
            // responses are numbered in arrival order and the prefetch preserves chunk order
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            for (int i = 1; i <= n; i++) expected.write(FakeGtts.fakeMp3(i), 0, 100);
            assertArrayEqualsRelaxed(expected.toByteArray(), sink.data.toByteArray());
            assertTrue(p.lastStatus().ok);
        }
    }

    private static void assertArrayEqualsRelaxed(byte[] a, byte[] b) {
        assertEquals(a.length, b.length);
        for (int i = 0; i < a.length; i++) assertEquals("at " + i, a[i], b[i]);
    }

    @Test
    public void ttsspeedOnlyWhenSlowing() throws Exception {
        try (FakeGtts s = new FakeGtts()) {
            GoogleTranslateTts p = configured(s);
            TtsRequest r = req("медленно");
            r.speed = 0.5f;
            p.synthesize(r, new RecordingSink(), new CancelToken());
            assertEquals("0.50", s.requests.get(0).get("ttsspeed"));
        }
        try (FakeGtts s = new FakeGtts()) {
            GoogleTranslateTts p = configured(s);
            TtsRequest r = req("быстро");
            r.speed = 1.5f; // not supported by the service: must not appear in the URL
            p.synthesize(r, new RecordingSink(), new CancelToken());
            assertEquals(null, s.requests.get(0).get("ttsspeed"));
        }
    }

    @Test
    public void http400SurfacesAsUnsupported() throws Exception {
        try (FakeGtts s = new FakeGtts()) {
            s.errorStatus = 400;
            GoogleTranslateTts p = configured(s);
            try {
                p.synthesize(req("текст"), new RecordingSink(), new CancelToken());
                throw new AssertionError("expected failure");
            } catch (VoiceException e) {
                assertEquals(VoiceException.Kind.UNSUPPORTED, e.kind);
            }
        }
    }

    @Test
    public void serverErrorSurfaces() throws Exception {
        try (FakeGtts s = new FakeGtts()) {
            s.errorStatus = 503;
            GoogleTranslateTts p = configured(s);
            try {
                p.synthesize(req("текст"), new RecordingSink(), new CancelToken());
                throw new AssertionError("expected failure");
            } catch (VoiceException e) {
                assertEquals(VoiceException.Kind.SERVER, e.kind);
            }
        }
    }

    @Test
    public void splitChunksPrefersSentencesThenClausesThenSpaces() {
        List<String> one = GoogleTranslateTts.splitChunks("коротко", 180);
        assertEquals(1, one.size());
        assertEquals("коротко", one.get(0));

        List<String> sent = GoogleTranslateTts.splitChunks(
            "Первая фраза. Вторая фраза здесь!", 20);
        assertEquals(java.util.Arrays.asList("Первая фраза.", "Вторая фраза здесь!"), sent);

        List<String> clause = GoogleTranslateTts.splitChunks("слово раз, слово два, слово три", 14);
        assertEquals(java.util.Arrays.asList("слово раз,", "слово два,", "слово три"), clause);

        // no punctuation: space cut
        List<String> spaces = GoogleTranslateTts.splitChunks("ааа ббб ввв", 6);
        assertEquals(java.util.Arrays.asList("ааа", "ббб", "ввв"), spaces);

        // no separators at all: hard cut
        List<String> hard = GoogleTranslateTts.splitChunks("абвгдеёжзий", 4);
        assertEquals(java.util.Arrays.asList("абвг", "деёж", "зий"), hard);

        // every chunk within the limit, concatenation preserved (modulo trimmed whitespace)
        String long_ = "Раз. Два. Три. Четыре. Пять. Шесть. Семь. Восемь. Девять. Десять. Одиннадцать. Двенадцать.";
        List<String> chunks = GoogleTranslateTts.splitChunks(long_, 25);
        int total = 0;
        for (String c : chunks) {
            assertTrue(c.length() <= 25);
            assertTrue(!c.trim().isEmpty());
            total += c.length() + 1;
        }
        assertTrue(total >= long_.length());
        // punctuation+quote stays in the chunk
        List<String> quoted = GoogleTranslateTts.splitChunks("Он сказал: «Привет, мир!» и ушёл", 18);
        assertEquals(java.util.Arrays.asList("Он сказал:", "«Привет, мир!»", "и ушёл"), quoted);
    }
}
