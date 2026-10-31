package app.jarvis.core.voice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.util.CancelToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.junit.Test;

/** EdgeTts against a local fake Read-Aloud server (raw sockets, RFC 6455). */
public class EdgeTtsTest {
    private static final byte[] MP3 = "ID3fake-mp3-payload".getBytes(StandardCharsets.UTF_8);

    /** Serves the Read-Aloud protocol: optional first-connection 403, handshake, audio + turn.end. */
    static final class FakeEdge implements AutoCloseable {
        final ServerSocket ss;
        final Thread t;
        volatile boolean rejectFirst403 = false;
        volatile int connections = 0;
        final List<String> requestLines = new ArrayList<>();
        final List<String> clientMessages = new ArrayList<>();
        volatile String lastError;

        FakeEdge() throws IOException {
            ss = new ServerSocket(0);
            t = new Thread(() -> {
                try {
                    while (true) {
                        Socket s = ss.accept();
                        boolean reject = rejectFirst403 && connections == 0;
                        connections++;
                        serve(s, reject);
                    }
                } catch (Exception e) {
                    lastError = String.valueOf(e);
                }
            });
            t.setDaemon(true);
            t.start();
        }

        int port() { return ss.getLocalPort(); }

        private void serve(Socket s, boolean reject) {
            try (Socket socket = s) {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                StringBuilder req = new StringBuilder();
                String line;
                while (!(line = readLine(in)).isEmpty()) req.append(line).append('\n');
                requestLines.add(req.toString());
                if (reject) {
                    SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                    f.setTimeZone(TimeZone.getTimeZone("UTC"));
                    String date = f.format(new Date(System.currentTimeMillis() - 700_000L));
                    out.write(("HTTP/1.1 403 Forbidden\r\nDate: " + date + "\r\nContent-Length: 0\r\n\r\n")
                        .getBytes("ASCII"));
                    out.flush();
                    return;
                }
                String key = header(req, "Sec-WebSocket-Key");
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("ASCII")));
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes("ASCII"));
                out.flush();
                // read client text messages; after the ssml one, push audio + turn.end
                int texts = 0;
                while (texts < 2) {
                    Object[] frame = readClientFrame(in);
                    if (((Integer) frame[0]) == 0x1) {
                        clientMessages.add(new String((byte[]) frame[1], StandardCharsets.UTF_8));
                        texts++;
                    }
                }
                // turn.start arrives fragmented: exercises reassembly inside the provider
                sendFragmentedText(out, ("X-RequestId:x\r\nContent-Type:application/json; charset=utf-8\r\n"
                    + "Path:turn.start\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                String header = "X-RequestId:y\r\nContent-Type:audio/mpeg\r\nPath:audio\r\n\r\n";
                byte[] hb = header.getBytes(StandardCharsets.UTF_8);
                byte[] frame = new byte[2 + hb.length + MP3.length];
                frame[0] = (byte) (hb.length >> 8);
                frame[1] = (byte) hb.length;
                System.arraycopy(hb, 0, frame, 2, hb.length);
                System.arraycopy(MP3, 0, frame, 2 + hb.length, MP3.length);
                sendComplete(out, 0x2, frame);
                // a metadata text frame that must be ignored by the provider
                sendComplete(out, 0x1, ("X-RequestId:z\r\nContent-Type:application/json; charset=utf-8\r\n"
                    + "Path:audio.metadata\r\n\r\n{\"Metadata\":{}}").getBytes(StandardCharsets.UTF_8));
                sendComplete(out, 0x1, "X-RequestId:w\r\nContent-Type:application/json; charset=utf-8\r\n"
                    .getBytes(StandardCharsets.UTF_8), ("Path:turn.end\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                lastError = String.valueOf(e);
            }
        }

        /** One text message split in two fragments, to exercise reassembly mid-stream. */
        private void sendFragmentedText(OutputStream out, byte[] payload) throws IOException {
            byte[] all = new byte[payload.length * 2];
            System.arraycopy(payload, 0, all, 0, payload.length);
            System.arraycopy(payload, 0, all, payload.length, payload.length);
            writeFrame(out, 0x1, java.util.Arrays.copyOf(payload, payload.length / 2 + 1), false);
            writeFrame(out, 0x0, java.util.Arrays.copyOfRange(all, payload.length / 2 + 1, all.length), true);
        }

        private void sendComplete(OutputStream out, int opcode, byte[] payload) throws IOException {
            writeFrame(out, opcode, payload, true);
        }

        private void sendComplete(OutputStream out, int opcode, byte[] p1, byte[] p2) throws IOException {
            byte[] all = new byte[p1.length + p2.length];
            System.arraycopy(p1, 0, all, 0, p1.length);
            System.arraycopy(p2, 0, all, p1.length, p2.length);
            writeFrame(out, opcode, all, true);
        }

        private static void writeFrame(OutputStream out, int opcode, byte[] payload, boolean fin) throws IOException {
            out.write((fin ? 0x80 : 0) | opcode);
            if (payload.length < 126) {
                out.write(payload.length);
            } else {
                out.write(126);
                out.write(payload.length >>> 8);
                out.write(payload.length);
            }
            out.write(payload);
            out.flush();
        }

        private static Object[] readClientFrame(InputStream in) throws IOException {
            int b1 = in.read(), b2 = in.read();
            int opcode = b1 & 0x0f;
            long len = b2 & 0x7f;
            byte[] mask = new byte[4];
            if (len == 126) len = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
            else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xff); }
            for (int i = 0; i < 4; i++) mask[i] = (byte) in.read();
            byte[] payload = new byte[(int) len];
            for (int off = 0; off < payload.length; ) {
                int n = in.read(payload, off, payload.length - off);
                if (n < 0) throw new IOException("eof");
                off += n;
            }
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            return new Object[] { opcode, payload };
        }

        private static String readLine(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) >= 0 && c != '\n') if (c != '\r') sb.append((char) c);
            return sb.toString();
        }

        private static String header(StringBuilder req, String name) {
            for (String l : req.toString().split("\n")) {
                if (l.toLowerCase(Locale.US).startsWith(name.toLowerCase(Locale.US) + ":")) {
                    return l.substring(name.length() + 1).trim();
                }
            }
            return "";
        }

        @Override public void close() throws Exception {
            ss.close();
            t.join(2000);
        }
    }

    static final class RecordingSink implements AudioSink {
        AudioSpec spec;
        final java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
        boolean began, ended, aborted;

        @Override public void begin(AudioSpec s) { spec = s; began = true; }
        @Override public void write(byte[] b, int off, int len) { data.write(b, off, len); }
        @Override public void end() { ended = true; }
        @Override public void abort() { aborted = true; }
    }

    private static EdgeTts configured(FakeEdge server) {
        EdgeTts p = new EdgeTts();
        ProviderConfig cfg = new ProviderConfig();
        cfg.put("baseUrl", "ws://127.0.0.1:" + server.port() + "/consumer/speech/synthesize/readaloud/edge/v1");
        p.configure(cfg);
        return p;
    }

    @Test
    public void happyPathStreamsAudioAndValidatesMessages() throws Exception {
        try (FakeEdge server = new FakeEdge()) {
            EdgeTts p = configured(server);
            RecordingSink sink = new RecordingSink();
            TtsRequest req = new TtsRequest();
            req.text = "Привет, Джарвис!";
            long t0 = System.currentTimeMillis();
            p.synthesize(req, sink, new CancelToken());
            assertArrayEquals(MP3, sink.data.toByteArray());
            assertTrue(sink.began && sink.ended && !sink.aborted);
            assertEquals(1, server.connections);
            assertTrue(server.clientMessages.get(0).contains("Path:speech.config"));
            assertTrue(server.clientMessages.get(0).contains("audio-24khz-48kbitrate-mono-mp3"));
            String ssml = server.clientMessages.get(1);
            assertTrue(ssml.contains("Path:ssml"));
            assertTrue(ssml.contains("<voice name='ru-RU-SvetlanaNeural'>"));
            assertTrue(ssml.contains("Привет, Джарвис!"));
            assertTrue(ssml.contains("rate='+0%'"));
            // handshake sanity: Origin, cookie with muid, no-cache
            String r = server.requestLines.get(0);
            assertTrue(r.contains("Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"));
            assertTrue(r.matches("(?s).*[Cc]ookie: muid=[0-9A-F]{32};\\s.*"));
            assertTrue(r.contains("Pragma: no-cache"));
            assertTrue(r.contains("Sec-MS-GEC="));
            assertTrue(r.contains("Sec-MS-GEC-Version=2-143.0.3650.75"));
            ProviderStatus st = p.lastStatus();
            assertTrue(st.ok);
            assertTrue(st.firstAudioMs >= 0 && st.firstAudioMs <= System.currentTimeMillis() - t0 + 1);
        }
    }

    @Test
    public void ssmlEscapingAndRateMapping() {
        String s = EdgeTts.buildSsml("ru-RU-X", "a<b & \"q\" 'd'", 1.5f);
        assertTrue(s.contains("a&lt;b &amp; &quot;q&quot; &apos;d"));
        assertTrue(s.contains("rate='+50%'"));
        assertTrue(EdgeTts.buildSsml("v", "т", 0.75f).contains("rate='-25%'"));
        assertTrue(EdgeTts.buildSsml("v", "т", 9f).contains("rate='+100%'"));
        assertTrue(EdgeTts.buildSsml("v", "т", 0f).contains("rate='-50%'"));
    }

    @Test
    public void audioChunkParsing() {
        byte[] h = "Path:audio\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[2 + h.length + 3];
        frame[0] = (byte) (h.length >> 8);
        frame[1] = (byte) h.length;
        System.arraycopy(h, 0, frame, 2, h.length);
        frame[2 + h.length] = 1;
        frame[2 + h.length + 1] = 2;
        frame[2 + h.length + 2] = 3;
        assertArrayEquals(new byte[] { 1, 2, 3 }, EdgeTts.audioChunk(frame));
        byte[] meta = "Path:audio.metadata\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] metaFrame = new byte[2 + meta.length + 1];
        metaFrame[0] = (byte) (meta.length >> 8);
        metaFrame[1] = (byte) meta.length;
        System.arraycopy(meta, 0, metaFrame, 2, meta.length);
        assertNull(EdgeTts.audioChunk(metaFrame));
        assertNull(EdgeTts.audioChunk(new byte[] { 0, 5 }));
    }

    @Test
    public void jsDateMatchesJsFormat() {
        // 2026-10-28 06:00:00 UTC
        assertEquals("Wed Oct 28 2026 06:00:00 GMT+0000 (Coordinated Universal Time)",
            EdgeTts.jsDate(1793167200000L));
    }

    @Test
    public void four03CorrectsSkewAndRetriesOnce() throws Exception {
        try (FakeEdge server = new FakeEdge()) {
            server.rejectFirst403 = true;
            EdgeTts p = configured(server);
            RecordingSink sink = new RecordingSink();
            TtsRequest req = new TtsRequest();
            req.text = "Проверка";
            p.synthesize(req, sink, new CancelToken());
            assertArrayEquals(MP3, sink.data.toByteArray());
            assertEquals(2, server.connections);
        }
    }

    @Test
    public void droppedConnectionIsNetworkError() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        Thread t = new Thread(() -> {
            try {
                Socket s = ss.accept();
                InputStream in = s.getInputStream();
                String line;
                StringBuilder req = new StringBuilder();
                while (!(line = readLineRaw(in)).isEmpty()) req.append(line);
                String key = "";
                for (String l : req.toString().split("\n")) {
                    if (l.startsWith("Sec-WebSocket-Key:")) key = l.substring(18).trim();
                }
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("ASCII")));
                s.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes("ASCII"));
                s.getOutputStream().flush();
                // read the two client messages, then drop the connection without audio
                for (int i = 0; i < 2; i++) readOne(in);
                s.close();
            } catch (Exception ignored) { }
        });
        t.setDaemon(true);
        t.start();
        try {
            EdgeTts p = new EdgeTts();
            ProviderConfig cfg = new ProviderConfig();
            cfg.put("baseUrl", "ws://127.0.0.1:" + ss.getLocalPort() + "/");
            p.configure(cfg);
            try {
                p.synthesize(req("тест"), new RecordingSink(), new CancelToken());
                throw new AssertionError("expected VoiceException");
            } catch (VoiceException e) {
                assertEquals(VoiceException.Kind.NETWORK, e.kind);
                assertNotNull(e.userMessage);
            }
        } finally {
            ss.close();
            t.join(2000);
        }
    }

    private static TtsRequest req(String text) {
        TtsRequest r = new TtsRequest();
        r.text = text;
        return r;
    }

    private static void readOne(InputStream in) throws IOException {
        int b1 = in.read(), b2 = in.read();
        long len = b2 & 0x7f;
        byte[] mask = new byte[4];
        if (len == 126) len = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
        for (int i = 0; i < 4; i++) mask[i] = (byte) in.read();
        byte[] payload = new byte[(int) len];
        for (int off = 0; off < payload.length; ) {
            int n = in.read(payload, off, payload.length - off);
            if (n < 0) return;
            off += n;
        }
    }

    private static String readLineRaw(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') if (c != '\r') sb.append((char) c);
        return sb.toString();
    }
}
