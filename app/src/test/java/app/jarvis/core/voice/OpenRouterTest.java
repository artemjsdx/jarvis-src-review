package app.jarvis.core.voice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.util.CancelToken;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Protocol tests for OpenRouterTts/OpenRouterStt against a local fake server. No real network. */
public class OpenRouterTest {
    private static final String KEY = "sk-or-test-000111222333444";

    private static ProviderConfig cfg(VoiceMockServer s) {
        ProviderConfig c = new ProviderConfig();
        c.put("apiKey", KEY);
        c.put("baseUrl", s.url(""));
        return c;
    }

    private static byte[] pcm(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 3);
        return b;
    }

    @Test public void tts_sendsModelInputFormat_bearer() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            byte[] mp3 = pcm(900);
            s.fallback = r -> {
                assertEquals("Bearer " + KEY, r.header("Authorization"));
                assertEquals("/audio/speech", r.path);
                JSONObject b = new JSONObject(r.bodyText());
                assertEquals("fish-audio/s2.1-pro-free:free", b.getString("model"));
                assertEquals("Привет!", b.getString("input"));
                assertEquals("mp3", b.getString("response_format"));
                assertFalse(b.has("input_references"));
                VoiceMockServer.bytes(r, 200, mp3, "audio/mpeg");
            };
            OpenRouterTts tts = new OpenRouterTts();
            tts.configure(cfg(s));
            ElevenLabsTest.CollectSink sink = new ElevenLabsTest.CollectSink();
            TtsRequest req = new TtsRequest();
            req.text = "Привет!";
            tts.synthesize(req, sink, new CancelToken());
            assertArrayEquals(mp3, sink.bytes());
            assertEquals(AudioSpec.Codec.MP3, sink.spec.codec);
            assertTrue(sink.ended);
            assertTrue(tts.lastStatus().ok);
            assertTrue(tts.lastStatus().detail.contains("0 КБ") || tts.lastStatus().detail.contains("1 КБ"));
        }
    }

    @Test public void tts_cloning_sendsInputReferences() throws Exception {
        java.io.File ref = Files.createTempFile("ref", ".mp3").toFile();
        byte[] refBytes = {9, 8, 7, 6};
        Files.write(ref.toPath(), refBytes);
        String expectedDataUri = "data:audio/mpeg;base64," + java.util.Base64.getEncoder().encodeToString(refBytes);
        try (VoiceMockServer s = new VoiceMockServer()) {
            s.fallback = r -> {
                JSONObject b = new JSONObject(r.bodyText());
                JSONArray refs = b.getJSONArray("input_references");
                assertEquals(2, refs.length());
                assertEquals("input_audio", refs.getJSONObject(0).getString("type"));
                assertEquals(expectedDataUri, refs.getJSONObject(0).getJSONObject("input_audio").getString("data"));
                assertEquals("text", refs.getJSONObject(1).getString("type"));
                assertEquals("точный текст", refs.getJSONObject(1).getString("text"));
                VoiceMockServer.bytes(r, 200, pcm(50), "audio/mpeg");
            };
            OpenRouterTts tts = tts(s);
            TtsRequest req = new TtsRequest();
            req.text = "клон";
            req.refAudioPath = ref.getAbsolutePath();
            req.refText = "точный текст";
            ElevenLabsTest.CollectSink sink = new ElevenLabsTest.CollectSink();
            tts.synthesize(req, sink, new CancelToken());
            assertEquals(50, sink.bytes().length);
        } finally {
            ref.delete();
        }
    }

    private static OpenRouterTts tts(VoiceMockServer s) {
        OpenRouterTts t = new OpenRouterTts();
        t.configure(cfg(s));
        return t;
    }

    @Test public void tts_cloningWithoutRefText_configError() throws Exception {
        java.io.File ref = java.nio.file.Files.createTempFile("ref", ".mp3").toFile();
        Files.write(ref.toPath(), new byte[] {1});
        try (VoiceMockServer s = new VoiceMockServer()) {
            OpenRouterTts tts = tts(s);
            TtsRequest req = new TtsRequest();
            req.text = "клон";
            req.refAudioPath = ref.getAbsolutePath();
            req.refText = "";
            try {
                tts.synthesize(req, new ElevenLabsTest.CollectSink(), new CancelToken());
                throw new AssertionError("expected CONFIG");
            } catch (VoiceException e) {
                assertEquals(VoiceException.Kind.CONFIG, e.kind);
                assertTrue(e.userMessage.contains("расшифровка"));
            }
            assertEquals(0, s.requests.size());
        } finally {
            ref.delete();
        }
    }

    @Test public void tts_connectionReset_retriedOnce() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            s.enqueue(VoiceMockServer::drop); // first attempt: connection dies before a response
            s.fallback = r -> VoiceMockServer.bytes(r, 200, pcm(300), "audio/mpeg");
            OpenRouterTts tts = tts(s);
            ElevenLabsTest.CollectSink sink = new ElevenLabsTest.CollectSink();
            TtsRequest req = new TtsRequest();
            req.text = "тест";
            tts.synthesize(req, sink, new CancelToken());
            assertEquals(2, s.requests.size());
            assertEquals(300, sink.bytes().length);
            assertTrue(tts.lastStatus().ok);
        }
    }

    @Test public void tts_400_notRetried_withHeygenHint() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            AtomicInteger n = new AtomicInteger(0);
            s.fallback = r -> {
                n.incrementAndGet();
                VoiceMockServer.json(r, 400, "{\"error\":{\"message\":\"heygen requires a voice or reference recording\"}}");
            };
            OpenRouterTts tts = tts(s);
            try {
                tts.synthesize(req("тест"), new ElevenLabsTest.CollectSink(), new CancelToken());
                throw new AssertionError("expected CONFIG");
            } catch (VoiceException e) {
                assertEquals(VoiceException.Kind.CONFIG, e.kind);
                assertTrue(e.userMessage.contains("образец"));
            }
            assertEquals(1, n.get()); // 4xx never retried
        }
    }

    @Test public void errorMapping() {
        assertEquals(VoiceException.Kind.AUTH, OpenRouterTts.httpError(401, "no auth", "t").kind);
        assertEquals(VoiceException.Kind.QUOTA, OpenRouterTts.httpError(402, "insufficient credits", "t").kind);
        assertEquals(VoiceException.Kind.RATE_LIMIT, OpenRouterTts.httpError(429, "rate limit exceeded", "t").kind);
        assertEquals(VoiceException.Kind.GEO_BLOCK, OpenRouterTts.httpError(403, "not available in your country", "t").kind);
        assertEquals(VoiceException.Kind.SERVER, OpenRouterTts.httpError(502, "bad gateway", "t").kind);
        assertEquals(VoiceException.Kind.CONFIG, OpenRouterTts.httpError(404, "model not found", "t").kind);
    }

    @Test public void listSpeechModels() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            s.fallback = r -> {
                assertTrue(r.query.contains("output_modalities=speech"));
                VoiceMockServer.json(r, 200, "{\"data\":[{\"id\":\"fish-audio/s2.1-pro-free:free\"},{\"id\":\"heygen/heygen-voice-1-turbo:free\"}]}");
            };
            OpenRouterTts tts = tts(s);
            List<String> m = tts.listSpeechModels(new CancelToken());
            assertEquals(2, m.size());
            assertTrue(m.get(0).endsWith(":free"));
        }
    }

    @Test public void stt_sendsBase64Wav_parsesText_retriesReset() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            byte[] wav = new PcmAudio(pcm(1600), 16000).toWav();
            String expectedB64 = java.util.Base64.getEncoder().encodeToString(wav);
            s.enqueue(VoiceMockServer::drop);
            s.fallback = r -> {
                assertEquals("/audio/transcriptions", r.path);
                JSONObject b = new JSONObject(r.bodyText());
                assertEquals("openai/whisper-large-v3-turbo", b.getString("model"));
                assertEquals("ru", b.getString("language"));
                assertEquals("wav", b.getJSONObject("input_audio").getString("format"));
                assertEquals(expectedB64, b.getJSONObject("input_audio").getString("data"));
                VoiceMockServer.json(r, 200, "{\"text\":\"привет я джарвис\"}");
            };
            OpenRouterStt stt = new OpenRouterStt();
            stt.configure(cfg(s));
            SttResult res = stt.transcribe(new PcmAudio(pcm(1600), 16000), opts(), new CancelToken());
            assertEquals("привет я джарвис", res.text);
            assertEquals("openrouter-stt", res.providerId);
            assertEquals(2, s.requests.size());
        }
    }

    @Test public void stt_check_401auth() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            s.fallback = r -> VoiceMockServer.json(r, 401, "{\"error\":{\"message\":\"invalid key\"}}");
            OpenRouterStt stt = new OpenRouterStt();
            stt.configure(cfg(s));
            ProviderStatus st = stt.check(new PcmAudio(pcm(1600), 16000), new CancelToken());
            assertFalse(st.ok);
            assertTrue(st.lastError.contains("Ключ"));
        }
    }

    private static TtsRequest req(String text) {
        TtsRequest r = new TtsRequest();
        r.text = text;
        return r;
    }

    private static SttOptions opts() { return new SttOptions(); }
}
