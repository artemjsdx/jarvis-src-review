package app.jarvis.core.voice;

import static org.junit.Assert.assertEquals;
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
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** SileroSpaceTts against a local fake Gradio 3 Space. */
public class SileroSpaceTtsTest {
    private static final byte[] WAV = new PcmAudio(new byte[3200], 16000).toWav();

    static final class FakeSilero implements AutoCloseable {
        final HttpServer server;
        volatile String version = "3.39.0";
        volatile boolean predictError = false;
        final AtomicReference<JSONObject> lastPredict = new AtomicReference<>();
        volatile int predictCount = 0;

        FakeSilero() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/config", ex -> respond(ex, configJson().toString().getBytes("UTF-8"), "application/json"));
            server.createContext("/run/predict", this::predict);
            server.createContext("/file=", ex -> respond(ex, WAV, "audio/wav"));
            server.start();
        }

        int port() { return server.getAddress().getPort(); }

        JSONObject configJson() {
            // gradio 3 shape: components have id+type, dependencies reference them by id
            return new JSONObject()
                .put("version", version)
                .put("components", new JSONArray()
                    .put(new JSONObject().put("id", 0).put("type", "textbox"))
                    .put(new JSONObject().put("id", 1).put("type", "radio"))
                    .put(new JSONObject().put("id", 2).put("type", "dropdown"))
                    .put(new JSONObject().put("id", 3).put("type", "audio")))
                .put("dependencies", new JSONArray()
                    .put(new JSONObject().put("inputs", new JSONArray().put(0)).put("outputs", new JSONArray()))
                    .put(new JSONObject().put("inputs", new JSONArray().put(0)).put("outputs", new JSONArray()))
                    .put(new JSONObject()
                        .put("inputs", new JSONArray().put(0).put(1).put(2))
                        .put("outputs", new JSONArray().put(3))));
        }

        private void predict(HttpExchange ex) throws IOException {
            String body = readBody(ex.getRequestBody());
            lastPredict.set(new JSONObject(body));
            predictCount++;
            if (predictError) {
                respond(ex, new JSONObject().put("error", "GPU queue is full").toString().getBytes("UTF-8"),
                    "application/json");
                return;
            }
            respond(ex, new JSONObject().put("data", new JSONArray().put(new JSONObject()
                    .put("name", "/tmp/gradio/abc123/out.wav").put("is_file", true)))
                    .toString().getBytes("UTF-8"), "application/json");
        }

        @Override public void close() { server.stop(0); }
    }

    static void respond(HttpExchange ex, byte[] body, String type) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(body); }
    }

    static String readBody(InputStream in) throws IOException {
        return new String(readBodyBytes(in), "UTF-8");
    }

    static byte[] readBodyBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    static final class RecordingSink implements AudioSink {
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        AudioSpec spec;
        boolean began, ended, aborted;

        @Override public void begin(AudioSpec s) { spec = s; began = true; }
        @Override public void write(byte[] b, int off, int len) { data.write(b, off, len); }
        @Override public void end() { ended = true; }
        @Override public void abort() { aborted = true; }
    }

    private static SileroSpaceTts configured(FakeSilero s) {
        SileroSpaceTts p = new SileroSpaceTts();
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
    public void happyPath() throws Exception {
        try (FakeSilero s = new FakeSilero()) {
            SileroSpaceTts p = configured(s);
            RecordingSink sink = new RecordingSink();
            TtsRequest r = req("Привет из теста!");
            r.voiceId = "baya";
            p.synthesize(r, sink, new CancelToken());
            byte[] got = sink.data.toByteArray();
            assertEquals(WAV.length, got.length);
            for (int i = 0; i < WAV.length; i++) assertEquals(WAV[i], got[i]);
            assertTrue(sink.began && sink.ended);
            assertEquals(1, s.predictCount);
            JSONObject pred = s.lastPredict.get();
            assertEquals("Привет из теста!", pred.getJSONArray("data").getString(0));
            assertEquals("Common", pred.getJSONArray("data").getString(1));
            assertEquals("baya", pred.getJSONArray("data").getString(2));
            assertEquals(2, pred.getInt("fn_index"));
            assertEquals(11, pred.getString("session_hash").length());
            assertTrue(p.lastStatus().ok);
        }
    }

    @Test
    public void defaultSpeakerFromConfig() throws Exception {
        try (FakeSilero s = new FakeSilero()) {
            SileroSpaceTts p = new SileroSpaceTts();
            ProviderConfig cfg = new ProviderConfig();
            cfg.put("baseUrl", "http://127.0.0.1:" + s.port());
            cfg.put("speaker", "xenia");
            p.configure(cfg);
            p.synthesize(req("текст"), new RecordingSink(), new CancelToken());
            assertEquals("xenia", s.lastPredict.get().getJSONArray("data").getString(2));
        }
    }

    @Test
    public void newGradioVersionFailsWithProtocol() throws Exception {
        try (FakeSilero s = new FakeSilero()) {
            s.version = "4.44.1";
            SileroSpaceTts p = configured(s);
            try {
                p.synthesize(req("т"), new RecordingSink(), new CancelToken());
                throw new AssertionError("expected failure");
            } catch (VoiceException e) {
                assertEquals(VoiceException.Kind.PROTOCOL, e.kind);
            }
        }
    }

    @Test
    public void predictErrorSurfaces() throws Exception {
        try (FakeSilero s = new FakeSilero()) {
            s.predictError = true;
            SileroSpaceTts p = configured(s);
            try {
                p.synthesize(req("т"), new RecordingSink(), new CancelToken());
                throw new AssertionError("expected failure");
            } catch (VoiceException e) {
                assertEquals(VoiceException.Kind.SERVER, e.kind);
            }
        }
    }

    @Test
    public void findAudioDepOnRealShape() {
        // the real Space's audio dep is at index 2 (textbox, radio, dropdown → audio)
        JSONObject config = new JSONObject()
            .put("version", "3.39.0")
            .put("components", new JSONArray()
                .put(new JSONObject().put("id", 7).put("type", "textbox"))
                .put(new JSONObject().put("id", 8).put("type", "radio"))
                .put(new JSONObject().put("id", 9).put("type", "dropdown"))
                .put(new JSONObject().put("id", 10).put("type", "audio"))
                .put(new JSONObject().put("id", 11).put("type", "button")))
            .put("dependencies", new JSONArray()
                .put(new JSONObject().put("inputs", new JSONArray().put(7)).put("outputs", new JSONArray()))
                .put(new JSONObject().put("inputs", new JSONArray()).put("outputs", new JSONArray()))
                .put(new JSONObject()
                    .put("inputs", new JSONArray().put(7).put(8).put(9))
                    .put("outputs", new JSONArray().put(10)))
                .put(new JSONObject().put("inputs", new JSONArray().put(11)).put("outputs", new JSONArray())));
        assertEquals(2, SileroSpaceTts.findAudioDep(config));
    }

    @Test
    public void emptyTextRejected() {
        SileroSpaceTts p = new SileroSpaceTts();
        try {
            p.synthesize(req("  "), new RecordingSink(), new CancelToken());
            throw new AssertionError("expected failure");
        } catch (VoiceException e) {
            assertEquals(VoiceException.Kind.CONFIG, e.kind);
        }
    }
}
