package app.jarvis.core.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.util.CancelToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** F5SpaceTts against a local fake Gradio 5 Space (upload → join → SSE → download). */
public class F5SpaceTtsTest {
    private static final byte[] REF = new PcmAudio(new byte[8000], 24000).toWav();
    private static final byte[] OUT = new PcmAudio(new byte[6400], 24000).toWav();

    static final class FakeF5 implements AutoCloseable {
        final HttpServer server;
        final AtomicReference<byte[]> uploaded = new AtomicReference<>();
        final AtomicReference<JSONObject> joined = new AtomicReference<>();
        /** null = complete successfully; otherwise the failure mode. */
        volatile String mode = "complete";

        FakeF5() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/config", ex -> SileroSpaceTtsTest.respond(ex, configJson().toString()
                .getBytes(StandardCharsets.UTF_8), "application/json"));
            server.createContext("/gradio_api/upload", this::upload);
            server.createContext("/gradio_api/queue/join", this::join);
            server.createContext("/gradio_api/queue/data", this::stream);
            server.createContext("/gradio_api/file=", ex ->
                SileroSpaceTtsTest.respond(ex, OUT, "audio/wav"));
            server.start();
        }

        int port() { return server.getAddress().getPort(); }

        JSONObject configJson() {
            return new JSONObject()
                .put("version", "5.9.1")
                .put("components", new JSONArray()
                    .put(new JSONObject().put("id", 0).put("type", "audio"))
                    .put(new JSONObject().put("id", 1).put("type", "textbox"))
                    .put(new JSONObject().put("id", 2).put("type", "textbox"))
                    .put(new JSONObject().put("id", 3).put("type", "dropdown"))
                    .put(new JSONObject().put("id", 4).put("type", "checkbox"))
                    .put(new JSONObject().put("id", 5).put("type", "slider"))
                    .put(new JSONObject().put("id", 6).put("type", "slider"))
                    .put(new JSONObject().put("id", 7).put("type", "slider"))
                    .put(new JSONObject().put("id", 8).put("type", "image"))
                    .put(new JSONObject().put("id", 9).put("type", "textbox")))
                .put("dependencies", new JSONArray()
                    .put(new JSONObject().put("api_name", "something_else")
                        .put("inputs", new JSONArray().put(1)).put("outputs", new JSONArray()))
                    .put(new JSONObject().put("api_name", "basic_tts")
                        .put("inputs", new JSONArray().put(0).put(1).put(2).put(3).put(4).put(5).put(6).put(7))
                        .put("outputs", new JSONArray().put(8).put(9))));
        }

        private void upload(HttpExchange ex) throws IOException {
            String ctype = ex.getRequestHeaders().getFirst("Content-Type");
            String boundary = ctype.substring(ctype.indexOf("boundary=") + 9);
            byte[] body = SileroSpaceTtsTest.readBodyBytes(ex.getRequestBody());
            // part payload sits between the first CRLFCRLF (end of part header) and the closing boundary
            int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
            assertTrue("multipart too small: " + body.length, body.length > headerEnd + 4 + tail.length);
            byte[] part = new byte[body.length - headerEnd - 4 - tail.length];
            System.arraycopy(body, headerEnd + 4, part, 0, part.length);
            uploaded.set(part);
            SileroSpaceTtsTest.respond(ex, new JSONArray().put("/tmp/gradio/xyz/ref.wav").toString()
                .getBytes(StandardCharsets.UTF_8), "application/json");
        }

        private static int indexOf(byte[] hay, byte[] needle) {
            outer:
            for (int i = 0; i + needle.length <= hay.length; i++) {
                for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
                return i;
            }
            return -1;
        }

        private void join(HttpExchange ex) throws IOException {
            joined.set(new JSONObject(SileroSpaceTtsTest.readBody(ex.getRequestBody())));
            SileroSpaceTtsTest.respond(ex, new JSONObject().put("event_id", "e1").toString()
                .getBytes(StandardCharsets.UTF_8), "application/json");
        }

        private void stream(HttpExchange ex) throws IOException {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            try {
                sse(out, "heartbeat", new JSONObject().put("msg", "heartbeat"));
                if ("error".equals(mode)) {
                    sse(out, "error", new JSONObject().put("msg", "error").put("message", "GPU quota exceeded"));
                    return;
                }
                if ("stall".equals(mode)) {
                    // never completes; the client is expected to cancel
                    while (true) {
                        Thread.sleep(150);
                        sse(out, "heartbeat", new JSONObject().put("msg", "heartbeat"));
                    }
                }
                sse(out, "process_starts", new JSONObject().put("msg", "process_starts").put("eta", 120));
                JSONObject audio = new JSONObject()
                    .put("path", "/tmp/gradio/out/o.wav")
                    .put("url", "http://127.0.0.1:" + port() + "/gradio_api/file=/tmp/gradio/out/o.wav")
                    .put("orig_name", "o.wav")
                    .put("meta", new JSONObject().put("_type", "gradio.FileData"));
                sse(out, "process_completed", new JSONObject()
                    .put("msg", "process_completed")
                    .put("output", new JSONObject().put("data", new JSONArray()
                        .put(audio)
                        .put(JSONObject.NULL)
                        .put("done")))
                    .put("success", true));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                ex.close();
            }
        }

        private static void sse(OutputStream out, String event, JSONObject data) throws IOException {
            out.write(("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        @Override public void close() { server.stop(0); }
    }

    static class F5SpaceTtsTestSink implements AudioSink {
        final java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
        AudioSpec spec;
        boolean began, ended, aborted;

        @Override public void begin(AudioSpec s) { spec = s; began = true; }
        @Override public void write(byte[] b, int off, int len) { data.write(b, off, len); }
        @Override public void end() { ended = true; }
        @Override public void abort() { aborted = true; }
    }

    private static F5SpaceTts configured(FakeF5 s) {
        F5SpaceTts p = new F5SpaceTts();
        ProviderConfig cfg = new ProviderConfig();
        cfg.put("baseUrl", "http://127.0.0.1:" + s.port());
        p.configure(cfg);
        return p;
    }

    private static TtsRequest req(String text) {
        TtsRequest r = new TtsRequest();
        r.text = text;
        r.refAudioPath = writeTempRef();
        r.refText = "это эталонный текст";
        return r;
    }

    private static String writeTempRef() {
        try {
            java.io.File f = java.io.File.createTempFile("f5ref", ".wav");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            fo.write(REF);
            fo.close();
            f.deleteOnExit();
            return f.getAbsolutePath();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void happyPath() throws Exception {
        try (FakeF5 s = new FakeF5()) {
            F5SpaceTts p = configured(s);
            F5SpaceTtsTestSink sink = new F5SpaceTtsTestSink();
            TtsRequest r = req("Привет, мир!");
            r.speed = 1.25f;
            p.synthesize(r, sink, new CancelToken());
            // upload: exact reference bytes reached the server
            assertNotNull(s.uploaded.get());
            assertEquals(REF.length, s.uploaded.get().length);
            for (int i = 0; i < REF.length; i++) assertEquals(REF[i], s.uploaded.get()[i]);
            // join: correct data layout and defaults (basic_tts is dep #1 in the fake config)
            JSONObject j = s.joined.get();
            assertEquals(1, j.getInt("fn_index"));
            JSONArray data = j.getJSONArray("data");
            assertEquals("/tmp/gradio/xyz/ref.wav", data.getJSONObject(0).getString("path"));
            assertEquals("это эталонный текст", data.getString(1));
            assertEquals("Привет, мир!", data.getString(2));
            assertEquals("ru", data.getString(3));
            assertEquals(false, data.getBoolean(4));
            assertEquals(0.15, data.getDouble(5), 1e-9);
            assertEquals(32, data.getInt(6));
            assertEquals(1.25, data.getDouble(7), 1e-9);
            // result audio downloaded into the sink
            assertEquals(OUT.length, sink.data.size());
            assertTrue(sink.began && sink.ended);
            assertTrue(p.lastStatus().ok);
            assertTrue(p.slow());
        }
    }

    @Test
    public void missingReferenceFailsWithConfig() throws Exception {
        F5SpaceTts p = new F5SpaceTts();
        TtsRequest r = new TtsRequest();
        r.text = "нет образца";
        try {
            p.synthesize(r, new F5SpaceTtsTestSink(), new CancelToken());
            throw new AssertionError("expected failure");
        } catch (VoiceException e) {
            assertEquals(VoiceException.Kind.CONFIG, e.kind);
        }
    }

    @Test
    public void spaceErrorSurfaces() throws Exception {
        try (FakeF5 s = new FakeF5()) {
            s.mode = "error";
            F5SpaceTts p = configured(s);
            try {
                p.synthesize(req("текст"), new F5SpaceTtsTestSink(), new CancelToken());
                throw new AssertionError("expected failure");
            } catch (VoiceException e) {
                assertEquals(VoiceException.Kind.SERVER, e.kind);
            }
        }
    }

    @Test
    public void cancelDuringQueueReturnsCancelled() throws Exception {
        try (FakeF5 s = new FakeF5()) {
            s.mode = "stall";
            F5SpaceTts p = configured(s);
            final CancelToken token = new CancelToken();
            new Thread(() -> {
                try { Thread.sleep(500); } catch (InterruptedException ignored) { }
                token.cancel("user");
            }).start();
            try {
                p.synthesize(req("текст"), new F5SpaceTtsTestSink(), token);
                throw new AssertionError("expected cancellation");
            } catch (VoiceException e) {
                assertEquals(VoiceException.Kind.CANCELLED, e.kind);
            }
        }
    }

    @Test
    public void findBasicTtsByApiName() {
        assertEquals(1, F5SpaceTts.findBasicTts(new FakeF5ConfigOnly().json()));
    }

    static final class FakeF5ConfigOnly {
        JSONObject json() {
            return new JSONObject()
                .put("version", "5.9.1")
                .put("components", new JSONArray()
                    .put(new JSONObject().put("id", 0).put("type", "textbox")))
                .put("dependencies", new JSONArray()
                    .put(new JSONObject().put("api_name", "other"))
                    .put(new JSONObject().put("api_name", "basic_tts")));
        }
    }
}
