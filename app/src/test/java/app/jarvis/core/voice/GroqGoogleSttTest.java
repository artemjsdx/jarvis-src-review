package app.jarvis.core.voice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.util.CancelToken;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;

/** Protocol tests for GroqStt and GoogleSpeechStt against local fake servers. No real network. */
public class GroqGoogleSttTest {
    private static final String GROQ_KEY = "gsk_test_000111222333444";

    private static byte[] pcm(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 5);
        return b;
    }

    // ---------------------------------------------------------------- Groq

    @Test public void groq_multipartFields() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            byte[] wav = new PcmAudio(pcm(1600), 16000).toWav();
            s.fallback = r -> {
                assertEquals("Bearer " + GROQ_KEY, r.header("Authorization"));
                assertEquals("/audio/transcriptions", r.path);
                Map<String, String> f = VoiceMockServer.multipartTextFields(r.body, r.contentType);
                assertEquals("whisper-large-v3-turbo", f.get("model"));
                assertEquals("ru", f.get("language"));
                assertEquals("json", f.get("response_format"));
                assertArrayEquals(wav, VoiceMockServer.multipartFileBytes(r.body, r.contentType, "file"));
                VoiceMockServer.json(r, 200, "{\"text\":\"привет\"}");
            };
            GroqStt stt = groq(s);
            SttResult res = stt.transcribe(new PcmAudio(pcm(1600), 16000), opts(), new CancelToken());
            assertEquals("привет", res.text);
            assertEquals("groq-stt", res.providerId);
        }
    }

    @Test public void groq_formatRejected_unsupported() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            s.fallback = r -> VoiceMockServer.json(r, 400,
                "{\"error\":{\"message\":\"audio/ADTS codec not supported\"}}");
            GroqStt stt = groq(s);
            ProviderStatus st = stt.check(new PcmAudio(pcm(1600), 16000), new CancelToken());
            assertFalse(st.ok);
            assertTrue(st.lastError.contains("WAV"));
        }
    }

    @Test public void groq_rateLimit_and_auth() throws Exception {
        assertEquals(VoiceException.Kind.RATE_LIMIT, groqError(429, "rate limit exceeded").kind);
        assertEquals(VoiceException.Kind.AUTH, groqError(401, "invalid").kind);
        assertEquals(VoiceException.Kind.CONFIG, groqError(413, "too large").kind);
        assertEquals(VoiceException.Kind.SERVER, groqError(503, "x").kind);
        VoiceException e = groqError(429, "x");
        assertTrue(e.userMessage.contains("лимит"));
    }

    private static VoiceException groqError(int code, String body) {
        try (VoiceMockServer s = new VoiceMockServer()) {
            s.fallback = r -> VoiceMockServer.json(r, code, body);
            GroqStt stt = groq(s);
            try {
                stt.transcribe(new PcmAudio(pcm(100), 16000), opts(), new CancelToken());
                throw new AssertionError("expected VoiceException for HTTP " + code);
            } catch (VoiceException ve) {
                return ve;
            }
        } catch (java.io.IOException | RuntimeException e) {
            throw new AssertionError(e);
        }
    }

    private static GroqStt groq(VoiceMockServer s) {
        GroqStt stt = new GroqStt();
        ProviderConfig c = new ProviderConfig();
        c.put("apiKey", GROQ_KEY);
        c.put("baseUrl", s.url(""));
        stt.configure(c);
        return stt;
    }

    // ---------------------------------------------------------------- Google

    @Test public void redactedReviewCredentialCannotBeUsedAsARealKey() throws Exception {
        GoogleSpeechStt stt = new GoogleSpeechStt();
        ProviderConfig cfg = new ProviderConfig();
        cfg.put("key", "REDACTED");
        stt.configure(cfg);
        assertFalse("Sanitized APK must not call Google with a fake key", stt.isConfigured());
        try {
            stt.transcribe(new PcmAudio(pcm(80), 16000), opts(), new CancelToken());
            throw new AssertionError("expected a configuration error, not an HTTP request");
        } catch (VoiceException e) {
            assertEquals(VoiceException.Kind.CONFIG, e.kind);
        }
    }

    @Test public void google_multilineJsonParsing() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            byte[] pcm = pcm(3200);
            s.fallback = r -> {
                assertEquals("POST", r.method);
                assertTrue("ct: " + r.contentType, r.contentType.startsWith("audio/l16; rate=16000"));
                assertTrue("q: " + r.query, r.query.contains("lang=ru-RU"));
                assertTrue("q: " + r.query, r.query.contains("maxresults=3"));
                assertTrue("q: " + r.query, r.query.contains("key="));
                assertArrayEquals(pcm, r.body);
                VoiceMockServer.bytes(r, 200, ("{\"result\":[]}\n"
                    + "{\"result\":[{\"alternative\":[{\"transcript\":\"привет\",\"confidence\":0.71}],\"final\":false}]}\n"
                    + "{\"result\":[{\"alternative\":[{\"transcript\":\"привет я джарвис\",\"confidence\":0.93},{\"transcript\":\"привет джарвис\",\"confidence\":0.4}],\"final\":true}]}\n")
                    .getBytes("UTF-8"), "application/json; charset=UTF-8");
            };
            GoogleSpeechStt stt = google(s);
            SttResult res = stt.transcribe(new PcmAudio(pcm, 16000), opts(), new CancelToken());
            assertEquals("привет я джарвис", res.text);
            assertEquals(0.93f, res.confidence, 1e-6f);
            assertEquals(1, res.alternatives.size());
            assertEquals("привет джарвис", res.alternatives.get(0));
            assertEquals("google-stt", res.providerId);
            assertTrue(res.latencyMs >= 0);
        }
    }

    @Test public void google_check_okOnSample() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            s.fallback = r -> VoiceMockServer.bytes(r, 200,
                ("{\"result\":[]}\n{\"result\":[{\"alternative\":[{\"transcript\":\"Привет! Я Джарвис, ваш голосовой помощник. Доброе утро.\",\"confidence\":0.9}],\"final\":true}]}")
                    .getBytes("UTF-8"), "application/json; charset=UTF-8");
            GoogleSpeechStt stt = google(s);
            ProviderStatus st = stt.check(new PcmAudio(pcm(3200), 16000), new CancelToken());
            assertTrue(st.ok);
            assertTrue(st.detail.contains("100%"));
        }
    }

    @Test public void google_emptyResult_isEmptyTextNotError() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            s.fallback = r -> VoiceMockServer.bytes(r, 200, "{\"result\":[]}\n".getBytes("UTF-8"), "application/json");
            GoogleSpeechStt stt = google(s);
            SttResult res = stt.transcribe(new PcmAudio(pcm(100), 16000), opts(), new CancelToken());
            assertEquals("", res.text);
        }
    }

    @Test public void google_errorMapping() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            AtomicInteger n = new AtomicInteger();
            s.fallback = r -> {
                int i = n.incrementAndGet();
                if (i == 1) VoiceMockServer.json(r, 403, "Daily Limit Exceeded");
                else if (i == 2) VoiceMockServer.json(r, 400, "invalid sample rate");
                else VoiceMockServer.json(r, 503, "down");
            };
            GoogleSpeechStt stt = google(s);
            assertEquals(VoiceException.Kind.QUOTA, catchKind(stt));
            assertEquals(VoiceException.Kind.CONFIG, catchKind(stt));
            assertEquals(VoiceException.Kind.SERVER, catchKind(stt));
        }
    }

    private static VoiceException.Kind catchKind(SttProvider stt) {
        try {
            stt.transcribe(new PcmAudio(pcm(100), 16000), opts(), new CancelToken());
            throw new AssertionError("expected VoiceException");
        } catch (VoiceException e) {
            return e.kind;
        }
    }

    private static GoogleSpeechStt google(VoiceMockServer s) {
        GoogleSpeechStt stt = new GoogleSpeechStt();
        ProviderConfig c = new ProviderConfig();
        c.put("key", "AIza-test-key-000111222333");
        c.put("baseUrl", s.url(""));
        stt.configure(c);
        return stt;
    }

    private static SttOptions opts() { return new SttOptions(); }
}
