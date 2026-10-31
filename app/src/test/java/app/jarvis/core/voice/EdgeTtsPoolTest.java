package app.jarvis.core.voice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.util.CancelToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** EdgeTts keep-alive pool against a fake Read-Aloud server that accepts several requests per connection. */
public class EdgeTtsPoolTest {
    private static final byte[] MP3 = "ID3fake-mp3-payload".getBytes(StandardCharsets.UTF_8);

    static final class Server implements AutoCloseable {
        final ServerSocket ss;
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger requests = new AtomicInteger();
        /** Server closes a connection after this many requests (0 = never). */
        volatile int closeAfter = 0;
        /** When set, the server sends one audio frame, signals midStream and waits for release before turn.end. */
        volatile CountDownLatch midStream, release;

        Server() throws IOException {
            ss = new ServerSocket(0);
            Thread t = new Thread(() -> {
                try {
                    while (true) {
                        Socket s = ss.accept();
                        connections.incrementAndGet();
                        Thread w = new Thread(() -> serve(s));
                        w.setDaemon(true);
                        w.start();
                    }
                } catch (IOException ignored) { }
            });
            t.setDaemon(true);
            t.start();
        }

        String baseUrl() { return "ws://127.0.0.1:" + ss.getLocalPort() + "/consumer/speech/synthesize/readaloud/edge/v1"; }

        private void serve(Socket s) {
            try (Socket socket = s) {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                StringBuilder req = new StringBuilder();
                String line;
                while (!(line = readLine(in)).isEmpty()) req.append(line).append('\n');
                String key = "";
                for (String l : req.toString().split("\n")) if (l.startsWith("Sec-WebSocket-Key:")) key = l.substring(18).trim();
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("ASCII")));
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes("ASCII"));
                out.flush();
                int served = 0;
                while (true) {
                    String msg = new String(readClientText(in), StandardCharsets.UTF_8);
                    if (!msg.contains("Path:ssml")) continue;
                    requests.incrementAndGet();
                    served++;
                    boolean silent = msg.contains("<prosody pitch='+0Hz' rate='+0%' volume='+0%'>...</prosody>");
                    frame(out, 0x1, "X-RequestId:x\r\nPath:turn.start\r\n\r\n{}".getBytes(StandardCharsets.UTF_8));
                    if (!silent) {
                        String header = "X-RequestId:y\r\nContent-Type:audio/mpeg\r\nPath:audio\r\n\r\n";
                        byte[] hb = header.getBytes(StandardCharsets.UTF_8);
                        byte[] f = new byte[2 + hb.length + MP3.length];
                        f[0] = (byte) (hb.length >> 8);
                        f[1] = (byte) hb.length;
                        System.arraycopy(hb, 0, f, 2, hb.length);
                        System.arraycopy(MP3, 0, f, 2 + hb.length, MP3.length);
                        frame(out, 0x2, f);
                    }
                    CountDownLatch mid = midStream, rel = release;
                    if (mid != null && rel != null) { mid.countDown(); rel.await(5, TimeUnit.SECONDS); }
                    frame(out, 0x1, "X-RequestId:w\r\nPath:turn.end\r\n\r\n{}".getBytes(StandardCharsets.UTF_8));
                    if (closeAfter > 0 && served >= closeAfter) return;
                }
            } catch (Exception ignored) { }
        }

        private static void frame(OutputStream out, int opcode, byte[] p) throws IOException {
            synchronized (out) {
                out.write(0x80 | opcode);
                if (p.length < 126) out.write(p.length);
                else { out.write(126); out.write(p.length >>> 8); out.write(p.length); }
                out.write(p);
                out.flush();
            }
        }

        private static byte[] readClientText(InputStream in) throws IOException {
            for (; ; ) {
                int b1 = in.read(), b2 = in.read();
                if (b1 < 0 || b2 < 0) throw new IOException("eof");
                long len = b2 & 0x7f;
                if (len == 126) len = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
                byte[] mask = new byte[4];
                for (int i = 0; i < 4; i++) mask[i] = (byte) in.read();
                byte[] payload = new byte[(int) len];
                for (int off = 0; off < payload.length; ) {
                    int n = in.read(payload, off, payload.length - off);
                    if (n < 0) throw new IOException("eof");
                    off += n;
                }
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
                if ((b1 & 0x0f) == 0x1) return payload;
            }
        }

        private static String readLine(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) >= 0 && c != '\n') if (c != '\r') sb.append((char) c);
            if (c < 0) throw new IOException("eof");
            return sb.toString();
        }

        @Override public void close() throws Exception { ss.close(); }
    }

    private static EdgeTts provider(Server srv, String keepAlive) {
        EdgeTts p = new EdgeTts();
        ProviderConfig cfg = new ProviderConfig();
        cfg.put("baseUrl", srv.baseUrl());
        if (keepAlive != null) cfg.put("keepAlive", keepAlive);
        p.configure(cfg);
        return p;
    }

    private static TtsRequest req(String text) {
        TtsRequest r = new TtsRequest();
        r.text = text;
        return r;
    }

    private static EdgeTtsTest.RecordingSink say(EdgeTts p, String text) throws VoiceException {
        EdgeTtsTest.RecordingSink sink = new EdgeTtsTest.RecordingSink();
        p.synthesize(req(text), sink, new CancelToken());
        assertArrayEquals(MP3, sink.data.toByteArray());
        assertTrue(sink.began && sink.ended && !sink.aborted);
        return sink;
    }

    @Test
    public void sequentialPhrasesShareOneConnection() throws Exception {
        try (Server srv = new Server()) {
            EdgeTts p = provider(srv, null);
            for (int i = 0; i < 5; i++) say(p, "Фраза " + i);
            assertEquals(1, srv.connections.get());
            assertEquals(5, srv.requests.get());
            assertEquals(1, p.connectCount());
            assertEquals(4, p.reuseCount());
        }
    }

    @Test
    public void keepAliveOffOpensOneConnectionPerPhrase() throws Exception {
        try (Server srv = new Server()) {
            EdgeTts p = provider(srv, "false");
            for (int i = 0; i < 3; i++) say(p, "Фраза " + i);
            assertEquals(3, srv.connections.get());
            assertEquals(0, p.reuseCount());
        }
    }

    @Test
    public void staleSocketIsRetriedTransparentlyOnce() throws Exception {
        try (Server srv = new Server()) {
            srv.closeAfter = 1;     // like the real service dropping an idle socket: every connection serves one request
            EdgeTts p = provider(srv, null);
            for (int i = 0; i < 3; i++) say(p, "Фраза " + i);   // each reuse hits a dead socket, retries on a new one
            assertEquals(3, srv.requests.get());
            assertEquals(3, srv.connections.get());
        }
    }

    @Test
    public void expiredIdleSocketIsNotReused() throws Exception {
        try (Server srv = new Server()) {
            EdgeTts p = provider(srv, null);
            say(p, "Раз");
            java.lang.reflect.Field f = EdgeTts.class.getDeclaredField("pool");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.ArrayDeque<Object> pool = (java.util.ArrayDeque<Object>) f.get(p);
            Object pooled = pool.peekFirst();
            java.lang.reflect.Field idle = pooled.getClass().getDeclaredField("idleSince");
            idle.setAccessible(true);
            idle.setLong(pooled, System.currentTimeMillis() - EdgeTts.IDLE_MAX_MS - 1000);
            say(p, "Два");
            assertEquals(2, srv.connections.get());
            assertEquals(0, p.reuseCount());
        }
    }

    @Test
    public void cancelledRequestDoesNotPoisonThePool() throws Exception {
        try (Server srv = new Server()) {
            EdgeTts p = provider(srv, null);
            say(p, "Раз");
            srv.midStream = new CountDownLatch(1);
            srv.release = new CountDownLatch(1);
            CancelToken c = new CancelToken();
            EdgeTtsTest.RecordingSink sink = new EdgeTtsTest.RecordingSink();
            Thread t = new Thread(() -> {
                try { Thread.sleep(0); } catch (InterruptedException ignored) { }
                try { srv.midStream.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                c.cancel("test");
                srv.release.countDown();
            });
            t.start();
            try {
                p.synthesize(req("Два"), sink, c);
                throw new AssertionError("expected cancellation");
            } catch (VoiceException e) {
                assertEquals(VoiceException.Kind.CANCELLED, e.kind);
            }
            t.join();
            srv.midStream = null;
            srv.release = null;
            say(p, "Три");
            assertEquals(2, srv.connections.get());     // the cancelled socket was dropped, not pooled
        }
    }

    @Test
    public void punctuationOnlyAnswerKeepsSocketAndReportsNoAudio() throws Exception {
        try (Server srv = new Server()) {
            EdgeTts p = provider(srv, null);
            say(p, "Раз");
            try {
                p.synthesize(req("..."), new EdgeTtsTest.RecordingSink(), new CancelToken());
                throw new AssertionError("expected NO_AUDIO");
            } catch (VoiceException e) {
                assertEquals(VoiceException.Kind.NO_AUDIO, e.kind);
            }
            say(p, "Три");
            assertEquals(1, srv.connections.get());
        }
    }

    @Test
    public void parallelRequestsUseSeparateSockets() throws Exception {
        try (Server srv = new Server()) {
            final EdgeTts p = provider(srv, null);
            final AtomicInteger ok = new AtomicInteger();
            Thread[] ts = new Thread[4];
            for (int i = 0; i < ts.length; i++) {
                final int n = i;
                ts[i] = new Thread(() -> {
                    try { say(p, "Фраза " + n); ok.incrementAndGet(); } catch (Throwable ignored) { }
                });
                ts[i].start();
            }
            for (Thread t : ts) t.join(10_000);
            assertEquals(4, ok.get());
            assertTrue(srv.connections.get() >= 1 && srv.connections.get() <= 4);
            // at most POOL_MAX idle sockets are kept
            say(p, "Ещё");
            say(p, "И ещё");
        }
    }

    @Test
    public void prewarmOpensASocketTheNextPhraseUses() throws Exception {
        try (Server srv = new Server()) {
            EdgeTts p = provider(srv, null);
            p.prewarm();
            say(p, "Раз");     // arrives while the prewarm may still be connecting: must join it, not open a second socket
            assertEquals(1, srv.connections.get());
            assertEquals(1, p.reuseCount());
        }
    }

    @Test
    public void closeIdleDropsPooledSockets() throws Exception {
        try (Server srv = new Server()) {
            EdgeTts p = provider(srv, null);
            say(p, "Раз");
            p.closeIdle();
            say(p, "Два");
            assertEquals(2, srv.connections.get());
            assertFalse(p.reuseCount() > 0);
        }
    }
}
