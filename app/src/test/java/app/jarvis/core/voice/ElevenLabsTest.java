package app.jarvis.core.voice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.util.CancelToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.json.JSONObject;

/** Protocol tests for ElevenLabsApi/Tts/Stt against a local fake server. No real network. */
public class ElevenLabsTest {
    private static final String KEY = "test-key-el-000111222333";

    private static ProviderConfig cfg(VoiceMockServer s) {
        ProviderConfig c = new ProviderConfig();
        c.put("apiKey", KEY);
        c.put("baseUrl", s.url(""));
        return c;
    }

    /** Collecting sink that records the AudioSpec and every write. */
    static final class CollectSink implements AudioSink {
        AudioSpec spec;
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        int writes;
        boolean ended, aborted;
        @Override public void begin(AudioSpec s) { spec = s; }
        @Override public void write(byte[] b, int off, int len) { writes++; data.write(b, off, len); }
        @Override public void end() { ended = true; }
        @Override public void abort() { aborted = true; }
        byte[] bytes() { return data.toByteArray(); }
    }

    private static byte[] pcm(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 7);
        return b;
    }

    // ---------------------------------------------------------------- HttpMultipart

    @Test public void multipart_exactBytesAndLength() throws Exception {
        String b = "bnd123";
        byte[] file = {1, 2, 3};
        byte[] out = HttpMultipart.build(b,
            new HttpMultipart.Part("model_id", "scribe_v1"),
            new HttpMultipart.Part("file", "a.wav", "audio/wav", file));
        ByteArrayOutputStream exp = new ByteArrayOutputStream();
        exp.write("--bnd123\r\nContent-Disposition: form-data; name=\"model_id\"\r\n\r\nscribe_v1\r\n".getBytes("UTF-8"));
        exp.write("--bnd123\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\nContent-Type: audio/wav\r\n\r\n".getBytes("UTF-8"));
        exp.write(file);
        exp.write("\r\n--bnd123--\r\n".getBytes("UTF-8"));
        assertArrayEquals(exp.toByteArray(), out);
        assertEquals(out.length, HttpMultipart.length(b,
            new HttpMultipart.Part("model_id", "scribe_v1"),
            new HttpMultipart.Part("file", "a.wav", "audio/wav", file)));
        assertTrue(HttpMultipart.contentType(b).startsWith("multipart/form-data; boundary=bnd123"));
    }

    // ---------------------------------------------------------------- Api: endpoints

    @Test public void voices_parsedWithKeyHeader() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            s.fallback = r -> {
                assertEquals(KEY, r.header("xi-api-key"));
                VoiceMockServer.json(r, 200, "{\"voices\":["
                    + "{\"voice_id\":\"v1\",\"name\":\"Sarah\",\"category\":\"premade\",\"labels\":{\"gender\":\"female\",\"accent\":\"american\"}},"
                    + "{\"voice_id\":\"v2\",\"name\":\"MyClone\",\"category\":\"cloned\",\"labels\":{\"gender\":\"male\"}}],\"has_more\":false}");
            };
            ElevenLabsApi api = new ElevenLabsApi();
            api.configure(cfg(s));
            List<Voice> vs = api.listVoices(new CancelToken());
            assertEquals(2, vs.size());
            assertEquals("Sarah", vs.get(0).name);
            assertEquals("v1", vs.get(0).id);
            assertFalse(vs.get(0).custom);
            assertTrue(vs.get(1).custom);
        }
    }

    @Test public void subscription_quotaText() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            s.fallback = r -> VoiceMockServer.json(r, 200,
                "{\"tier\":\"free\",\"character_count\":880,\"character_limit\":10000,\"next_character_count_reset_unix\":1795824000}");
            ElevenLabsApi api = new ElevenLabsApi();
            api.configure(cfg(s));
            assertEquals("осталось 9 120 из 10 000 символов (до 28.11.2026)", api.quotaText(new CancelToken()));
            ElevenLabsApi.Subscription sub = api.subscription(new CancelToken());
            assertEquals("free", sub.tier);
            assertEquals(880, sub.characterCount);
        }
    }

    @Test public void ttsStream_streamsChunksIntoSink() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            byte[] a = pcm(500), b = pcm(700);
            s.fallback = r -> {
                assertEquals("POST", r.method);
                assertTrue(r.path.startsWith("/v1/text-to-speech/v/"));
                assertTrue(r.path.endsWith("/stream"));
                assertTrue(r.query.contains("output_format=pcm_24000"));
                assertEquals(KEY, r.header("xi-api-key"));
                JSONObject body = new JSONObject(r.bodyText());
                assertEquals("Привет", body.getString("text"));
                assertEquals("eleven_flash_v2_5", body.getString("model_id"));
                assertEquals("ru", body.getString("language_code"));
                assertEquals(0.5, body.getJSONObject("voice_settings").getDouble("stability"), 1e-9);
                VoiceMockServer.chunkedBytes(r, a, b, 150);
            };
            ElevenLabsApi api = new ElevenLabsApi();
            api.configure(cfg(s));
            CollectSink sink = new CollectSink();
            long first = api.ttsStream("v", "Привет", "eleven_flash_v2_5", "pcm_24000", "ru", sink, new CancelToken());
            byte[] expected = new byte[a.length + b.length];
            System.arraycopy(a, 0, expected, 0, a.length);
            System.arraycopy(b, 0, expected, a.length, b.length);
            assertArrayEquals(expected, sink.bytes());
            assertEquals(AudioSpec.Codec.PCM_S16LE, sink.spec.codec);
            assertEquals(24000, sink.spec.sampleRate);
            assertTrue(sink.ended);
            assertTrue(sink.writes >= 2);      // real streaming, not one buffer
            assertTrue(first >= 0 && first < 10_000);
        }
    }

    @Test public void ttsStream_retriesWithoutLanguageCode() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            AtomicInteger n = new AtomicInteger();
            s.fallback = r -> {
                if (n.incrementAndGet() == 1) {
                    VoiceMockServer.json(r, 400, "{\"detail\":{\"status\":400,\"message\":\"language_code is not supported for this model\"}}");
                } else {
                    JSONObject body = new JSONObject(r.bodyText());
                    assertTrue(!body.has("language_code"));
                    VoiceMockServer.bytes(r, 200, pcm(100), "application/octet-stream");
                }
            };
            ElevenLabsApi api = new ElevenLabsApi();
            api.configure(cfg(s));
            CollectSink sink = new CollectSink();
            api.ttsStream("v", "т", "some_model", "pcm_24000", "ru", sink, new CancelToken());
            assertEquals(2, n.get());
            assertEquals(100, sink.bytes().length);
        }
    }

    @Test public void v4Model_omitsVoiceSettings() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            s.fallback = r -> {
                JSONObject body = new JSONObject(r.bodyText());
                assertTrue(!body.has("voice_settings"));
                VoiceMockServer.bytes(r, 200, pcm(10), "application/octet-stream");
            };
            ElevenLabsApi api = new ElevenLabsApi();
            api.configure(cfg(s));
            api.ttsStream("v", "т", "eleven_v4", "mp3_44100_64", "ru", new CollectSink(), new CancelToken());
        }
    }

    @Test public void errorMapping() throws Exception {
        VoiceException e;
        e = ElevenLabsApi.httpError(401, "{\"detail\":\"invalid api key\"}", "t");
        assertEquals(VoiceException.Kind.AUTH, e.kind);
        e = ElevenLabsApi.httpError(402, "insufficient characters", "t");
        assertEquals(VoiceException.Kind.QUOTA, e.kind);
        e = ElevenLabsApi.httpError(403, "{\"detail\":{\"status\":403,\"message\":\"subscription_required\"}}", "t");
        assertEquals(VoiceException.Kind.UNSUPPORTED, e.kind);
        assertTrue(e.userMessage.contains("платном тарифе"));
        e = ElevenLabsApi.httpError(403, "access from your country is blocked", "t");
        assertEquals(VoiceException.Kind.GEO_BLOCK, e.kind);
        e = ElevenLabsApi.httpError(429, "rate limit", "t");
        assertEquals(VoiceException.Kind.RATE_LIMIT, e.kind);
        e = ElevenLabsApi.httpError(503, "boom", "t");
        assertEquals(VoiceException.Kind.SERVER, e.kind);
        assertTrue(e.retryable());
        e = ElevenLabsApi.httpError(422, "bad", "t");
        assertEquals(VoiceException.Kind.CONFIG, e.kind);
    }

    @Test public void tts_403subscription_neverRetried() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            AtomicInteger n = new AtomicInteger();
            s.fallback = r -> {
                n.incrementAndGet();
                VoiceMockServer.json(r, 403, "{\"detail\":{\"status\":403,\"message\":\"pcm_44100 requires subscription_required upgrade\"}}");
            };
            ElevenLabsTts tts = new ElevenLabsTts();
            tts.configure(cfg(s));
            TtsRequest req = new TtsRequest();
            req.text = "тест";
            req.options.put("output_format", "pcm_44100");
            CollectSink sink = new CollectSink();
            try {
                tts.synthesize(req, sink, new CancelToken());
                throw new AssertionError("expected VoiceException");
            } catch (VoiceException e) {
                assertEquals(VoiceException.Kind.UNSUPPORTED, e.kind);
                assertTrue(sink.aborted);
            }
            assertEquals(1, n.get()); // 4xx never retried
            assertFalse(tts.lastStatus().ok);
        }
    }

    @Test public void tts_emptyBody_isNoAudioAndAbortsSink() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            s.fallback = r -> VoiceMockServer.bytes(r, 200, new byte[0], "application/octet-stream");
            ElevenLabsTts tts = new ElevenLabsTts();
            tts.configure(cfg(s));
            TtsRequest req = new TtsRequest();
            req.text = "тест";
            CollectSink sink = new CollectSink();
            try {
                tts.synthesize(req, sink, new CancelToken());
                throw new AssertionError("expected NO_AUDIO");
            } catch (VoiceException e) {
                assertEquals(VoiceException.Kind.NO_AUDIO, e.kind);
                assertTrue(sink.aborted);
                assertFalse(sink.ended);
            }
        }
    }

    @Test public void tts_cancelMidStream() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            s.fallback = r -> {
                try {
                    r.x.getResponseHeaders().add("Content-Type", "application/octet-stream");
                    r.x.sendResponseHeaders(200, 1_000_000); // promises far more than it sends
                    r.x.getResponseBody().write(pcm(1000));
                    r.x.getResponseBody().flush();
                    Thread.sleep(5000); // client must cancel us
                } catch (Exception ignored) { }
            };
            ElevenLabsTts tts = new ElevenLabsTts();
            tts.configure(cfg(s));
            CancelToken cancel = new CancelToken();
            CollectSink sink = new CollectSink();
            Thread thr = Thread.currentThread();
            Thread canceller = new Thread(() -> {
                try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                cancel.cancel();
            });
            canceller.start();
            try {
                tts.synthesize(req("тест"), sink, cancel);
                throw new AssertionError("expected CANCELLED");
            } catch (VoiceException e) {
                assertEquals(VoiceException.Kind.CANCELLED, e.kind);
                assertTrue(sink.aborted);
            }
        }
    }

    private static TtsRequest req(String text) {
        TtsRequest r = new TtsRequest();
        r.text = text;
        return r;
    }

    @Test public void stt_sendsMultipart_parsesText() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            byte[] wav = new PcmAudio(pcm(3200), 16000).toWav();
            s.fallback = r -> {
                assertTrue(r.contentType.startsWith("multipart/form-data; boundary="));
                java.util.Map<String, String> fields = VoiceMockServer.multipartTextFields(r.body, r.contentType);
                assertEquals("scribe_v1", fields.get("model_id"));
                assertEquals("rus", fields.get("language_code"));
                byte[] got = VoiceMockServer.multipartFileBytes(r.body, r.contentType, "file");
                assertArrayEquals(wav, got);
                VoiceMockServer.json(r, 200, "{\"text\":\"привет\",\"language_code\":\"rus\",\"language_probability\":0.98}");
            };
            ElevenLabsStt stt = new ElevenLabsStt();
            stt.configure(cfg(s));
            SttResult res = stt.transcribe(new PcmAudio(pcm(3200), 16000), opts("ru"), new CancelToken());
            assertEquals("привет", res.text);
            assertEquals("elevenlabs-stt", res.providerId);
            assertTrue(res.latencyMs >= 0);
        }
    }

    @Test public void stt_check_wordMatch() throws Exception {
        assertEquals(1.0, ElevenLabsStt.wordMatch("Привет, я Джарвис! Ваш голосовой помощник. Доброе утро.", ElevenLabsStt.SAMPLE_TEXT), 1e-9);
        assertEquals(0.25, ElevenLabsStt.wordMatch("привет джарвис", ElevenLabsStt.SAMPLE_TEXT), 1e-9); // 2 of 8 words
        assertEquals(0.0, ElevenLabsStt.wordMatch("", ElevenLabsStt.SAMPLE_TEXT), 1e-9);
        assertEquals("rus", ElevenLabsStt.languageCode("ru"));
        assertEquals("eng", ElevenLabsStt.languageCode("en"));
        assertEquals("deu", ElevenLabsStt.languageCode("deu"));
    }

    @Test public void stt_checkAgainstFakeServer() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            s.fallback = r -> VoiceMockServer.json(r, 200, "{\"text\":\"Привет! Я Джарвис, ваш голосовой помощник. Доброе утро.\"}");
            ElevenLabsStt stt = new ElevenLabsStt();
            stt.configure(cfg(s));
            ProviderStatus st = stt.check(new PcmAudio(pcm(3200), 16000), new CancelToken());
            assertTrue(st.ok);
            assertTrue(st.detail.contains("100%"));
        }
    }

    @Test public void design_onFree403_unsupportedWithPaidMessage() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            s.fallback = r -> VoiceMockServer.json(r, 403, "{\"detail\":{\"status\":403,\"message\":\"feature_not_available\"}}");
            ElevenLabsApi api = new ElevenLabsApi();
            api.configure(cfg(s));
            try {
                api.designVoice("calm russian male voice", new CancelToken());
                throw new AssertionError("expected UNSUPPORTED");
            } catch (VoiceException e) {
                assertEquals(VoiceException.Kind.UNSUPPORTED, e.kind);
                assertTrue(e.userMessage.contains("платном тарифе ElevenLabs"));
            }
        }
    }

    @Test public void tts_producesStatusDetail() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            s.fallback = r -> VoiceMockServer.bytes(r, 200, pcm(2048), "application/octet-stream");
            ElevenLabsTts tts = new ElevenLabsTts();
            tts.configure(cfg(s));
            tts.check("тест", "", new CancelToken());
            assertTrue(tts.lastStatus().ok);
            assertTrue(tts.lastStatus().detail.contains("eleven_flash_v2_5"));
            assertTrue(tts.lastStatus().detail.contains("2 КБ"));
        }
    }

    private static SttOptions opts(String lang) {
        SttOptions o = new SttOptions();
        o.language = lang;
        return o;
    }

    @Test public void ttsStream_sendsSpeedAsVoiceSetting() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            final double[] seen = new double[2];
            s.fallback = r -> {
                seen[0] = new JSONObject(r.bodyText()).getJSONObject("voice_settings").getDouble("speed");
                VoiceMockServer.chunkedBytes(r, pcm(200), pcm(0), 150);
            };
            ElevenLabsApi api = new ElevenLabsApi();
            api.configure(cfg(s));
            api.ttsStream("v", "т", "eleven_flash_v2_5", "pcm_24000", "ru", 0.8f, new CollectSink(), new CancelToken());
            assertEquals(0.8, seen[0], 1e-6);
            s.fallback = r -> {
                seen[1] = new JSONObject(r.bodyText()).getJSONObject("voice_settings").getDouble("speed");
                VoiceMockServer.chunkedBytes(r, pcm(200), pcm(0), 150);
            };
            api.ttsStream("v", "т", "eleven_flash_v2_5", "pcm_24000", "ru", 1.4f, new CollectSink(), new CancelToken());
            assertEquals(1.2, seen[1], 1e-6);
        }
    }
}
