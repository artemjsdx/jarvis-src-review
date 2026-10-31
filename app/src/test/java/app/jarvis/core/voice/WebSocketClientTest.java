package app.jarvis.core.voice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Exercises WebSocketClient against a real local RFC 6455 server (raw sockets). */
public class WebSocketClientTest {
    private static final byte[] MASK_DUMMY = { 0, 0, 0, 0 };

    /** Minimal server-side WS: accepts one client, serves a scripted sequence of frames. */
    static final class WsServer implements AutoCloseable {
        final ServerSocket ss;
        final Thread t;
        volatile Socket client;
        volatile OutputStream out;
        volatile InputStream in;
        final CountDownLatch open = new CountDownLatch(1);
        final AtomicReference<String> acceptKey = new AtomicReference<>();
        final AtomicReference<String> lastText = new AtomicReference<>("");

        WsServer() throws IOException {
            ss = new ServerSocket(0);
            t = new Thread(() -> {
                try {
                    Socket s = ss.accept();
                    client = s;
                    in = s.getInputStream();
                    out = s.getOutputStream();
                    String line = readLine(in);
                    // read request headers until blank line
                    while (!(line = readLine(in)).isEmpty()) {
                        String l = line.toLowerCase();
                        if (l.startsWith("sec-websocket-key:")) acceptKey.set(line.substring(18).trim());
                    }
                    String key = acceptKey.get();
                    String accept = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("ASCII")));
                    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes("ASCII"));
                    out.flush();
                    open.countDown();
                } catch (Exception ignored) { }
            });
            t.setDaemon(true);
        t.start();
        }

        int port() { return ss.getLocalPort(); }

        /** Waits for the handshake, then serves one frame (unmasked, server->client). */
        void awaitOpen() throws Exception {
            assertTrue(open.await(5, TimeUnit.SECONDS));
        }

        void sendFrame(int opcode, byte[] payload, boolean fin) throws IOException {
            byte head1 = (byte) ((fin ? 0x80 : 0) | opcode);
            out.write(head1);
            if (payload.length < 126) {
                out.write(payload.length);
            } else if (payload.length < 65536) {
                out.write(126);
                out.write(payload.length >>> 8);
                out.write(payload.length);
            } else {
                out.write(127);
                for (int i = 7; i >= 0; i--) out.write((int) ((long) payload.length >>> (8 * i)));
            }
            out.write(payload);
            out.flush();
        }

        /** Reads one client frame (masked) with a raw reader; returns opcode and payload. */
        Object[] readClientFrame() throws IOException {
            int b1 = in.read();
            int b2 = in.read();
            int opcode = b1 & 0x0f;
            long len = b2 & 0x7f;
            byte[] mask = new byte[4];
            if (len == 126) { len = ((in.read() & 0xff) << 8) | (in.read() & 0xff); }
            else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xff); }
            if ((b2 & 0x80) != 0) {
                for (int i = 0; i < 4; i++) mask[i] = (byte) in.read();
            }
            byte[] payload = new byte[(int) len];
            for (int off = 0; off < payload.length; ) {
                int n = in.read(payload, off, payload.length - off);
                if (n < 0) throw new EOFException();
                off += n;
            }
            if ((b2 & 0x80) != 0) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            return new Object[] { opcode, payload };
        }

        @Override public void close() throws IOException {
            try { if (client != null) client.close(); } catch (IOException ignored) { }
            ss.close();
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') if (c != '\r') sb.append((char) c);
        return sb.toString();
    }

    private static WebSocketClient connect(WsServer s) throws Exception {
        return WebSocketClient.connect("ws://127.0.0.1:" + s.port() + "/echo", null, 3000, 5000, null);
    }

    @Test
    public void textEchoAndLargePayloads() throws Exception {
        try (WsServer s = new WsServer(); WebSocketClient c = connect(s)) {
            s.awaitOpen();
            // client -> server text (masked)
            String hello = "Привет, websocket!";
            c.sendText(hello);
            Object[] f = s.readClientFrame();
            assertEquals(0x1, f[0]);
            assertEquals(hello, new String((byte[]) f[1], StandardCharsets.UTF_8));
            // server -> client text
            s.sendFrame(0x1, "эфо".getBytes(StandardCharsets.UTF_8), true);
            WebSocketClient.Frame r = c.receive();
            assertEquals(WebSocketClient.Type.TEXT, r.type);
            assertEquals("эфо", r.text());
            // 64-bit length: 200 KB binary both directions
            byte[] big = new byte[200_000];
            for (int i = 0; i < big.length; i++) big[i] = (byte) i;
            c.sendBinary(big);
            Object[] fb = s.readClientFrame();
            assertEquals(0x2, fb[0]);
            assertArrayEquals(big, (byte[]) fb[1]);
            s.sendFrame(0x2, big, true);
            WebSocketClient.Frame rb = c.receive();
            assertEquals(WebSocketClient.Type.BINARY, rb.type);
            assertArrayEquals(big, rb.data);
        }
    }

    @Test
    public void fragmentedMessageReassembles() throws Exception {
        try (WsServer s = new WsServer(); WebSocketClient c = connect(s)) {
            s.awaitOpen();
            byte[] p1 = "fragment-1 ".getBytes(StandardCharsets.UTF_8);
            byte[] p2 = "fragment-2".getBytes(StandardCharsets.UTF_8);
            s.sendFrame(0x1, p1, false);
            s.sendFrame(0x0, p2, true);
            WebSocketClient.Frame r = c.receive();
            assertEquals(WebSocketClient.Type.TEXT, r.type);
            assertEquals("fragment-1 fragment-2", r.text());
        }
    }

    @Test
    public void pingAnsweredPongDelivered() throws Exception {
        try (WsServer s = new WsServer(); WebSocketClient c = connect(s)) {
            s.awaitOpen();
            // the client answers pings inside receive(); follow the ping with a text frame so receive returns
            s.sendFrame(0x9, "hb".getBytes(StandardCharsets.UTF_8), true);
            s.sendFrame(0x1, "after-ping".getBytes(StandardCharsets.UTF_8), true);
            WebSocketClient.Frame r = c.receive();
            assertEquals(WebSocketClient.Type.TEXT, r.type);
            assertEquals("after-ping", r.text());
            // now the auto-pong must be on the wire
            Object[] pong = s.readClientFrame();
            assertEquals(0xA, pong[0]);
            assertArrayEquals("hb".getBytes(StandardCharsets.UTF_8), (byte[]) pong[1]);
            // client-initiated ping -> server sees it; client receives PONG frame
            c.sendPing("my-ping".getBytes(StandardCharsets.UTF_8));
            Object[] pin = s.readClientFrame();
            assertEquals(0x9, pin[0]);
            assertArrayEquals("my-ping".getBytes(StandardCharsets.UTF_8), (byte[]) pin[1]);
            s.sendFrame(0xA, "my-ping".getBytes(StandardCharsets.UTF_8), true);
            WebSocketClient.Frame rp = c.receive();
            assertEquals(WebSocketClient.Type.PONG, rp.type);
            assertEquals("my-ping", rp.text());
        }
    }

    @Test
    public void closeHandshakeAndCode() throws Exception {
        try (WsServer s = new WsServer(); WebSocketClient c = connect(s)) {
            s.awaitOpen();
            s.sendFrame(0x8, new byte[] { 3, (byte) 232, 'b', 'y', 'e' }, true);
            WebSocketClient.Frame r = c.receive();
            assertEquals(WebSocketClient.Type.CLOSE, r.type);
            assertEquals(1000, r.code);
            assertEquals("bye", r.text());
        }
    }

    @Test
    public void serverPushesWhileClientSends() throws Exception {
        // order: send text, receive ping+text, verify interleaving works
        try (WsServer s = new WsServer(); WebSocketClient c = connect(s)) {
            s.awaitOpen();
            c.sendText("one");
            s.sendFrame(0x9, new byte[0], true);
            s.sendFrame(0x1, "two".getBytes(StandardCharsets.UTF_8), true);
            WebSocketClient.Frame r = c.receive(); // ping consumed, text returned
            assertEquals("two", r.text());
        }
    }

    @Test
    public void handshakeFailureCarriesStatusAndHeaders() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        Thread t = new Thread(() -> {
            try {
                Socket s = ss.accept();
                InputStream in = s.getInputStream();
                while (!readLine(in).isEmpty()) { }
                s.getOutputStream().write(("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n"
                    + "Date: Tue, 28 Oct 2026 06:00:00 GMT\r\n\r\n").getBytes("ASCII"));
                s.getOutputStream().flush();
            } catch (Exception ignored) { }
        });
        t.setDaemon(true);
        t.start();
        try {
            WebSocketClient.connect("ws://127.0.0.1:" + ss.getLocalPort() + "/", null, 2000, 2000, null);
            throw new AssertionError("expected HandshakeFailure");
        } catch (WebSocketClient.HandshakeFailure e) {
            assertEquals(403, e.status);
            assertEquals("Tue, 28 Oct 2026 06:00:00 GMT", e.headers.get("date"));
        } finally {
            ss.close();
            t.join(2000);
        }
    }

    @Test
    public void cancelUnblocksReceive() throws Exception {
        try (WsServer s = new WsServer(); final WebSocketClient c = connect(s)) {
            s.awaitOpen();
            final CountDownLatch done = new CountDownLatch(1);
            Thread reader = new Thread(() -> {
                try {
                    c.receive();
                } catch (IOException expected) {
                    done.countDown();
                }
            });
            reader.start();
            Thread.sleep(200);
            c.close();
            assertTrue("receive() must unblock after close()", done.await(3, TimeUnit.SECONDS));
        }
    }

    @Test
    public void multipleTextsInSequence() throws Exception {
        try (WsServer s = new WsServer(); WebSocketClient c = connect(s)) {
            s.awaitOpen();
            List<String> got = new ArrayList<>();
            s.sendFrame(0x1, "a".getBytes(StandardCharsets.UTF_8), true);
            s.sendFrame(0x1, "б".getBytes(StandardCharsets.UTF_8), true);
            got.add(c.receive().text());
            got.add(c.receive().text());
            assertEquals("a", got.get(0));
            assertEquals("б", got.get(1));
        }
    }
}
