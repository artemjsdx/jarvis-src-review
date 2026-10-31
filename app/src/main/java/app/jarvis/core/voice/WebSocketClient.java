package app.jarvis.core.voice;

import app.jarvis.core.util.CancelToken;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import app.jarvis.core.net.NetThrottle;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal blocking RFC 6455 WebSocket client (no deps, works on Android's runtime).
 * One message in flight per reader: {@link #receive()} reassembles fragmented data frames,
 * answers pings automatically, and returns TEXT/BINARY/PONG/CLOSE frames to the caller.
 * Writes are synchronized; closing the socket from another thread (see cancel hook) unblocks a
 * pending read with a SocketException.
 */
public final class WebSocketClient implements Closeable {
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    /** Safety net against a broken peer (Edge streams can push ~1 MB per text frame at most). */
    private static final int MAX_MESSAGE = 64 << 20;

    /** The upgrade failed: status != 101. Carries the response headers (e.g. Date for clock skew). */
    public static final class HandshakeFailure extends IOException {
        public final int status;
        public final String statusLine;
        public final Map<String, String> headers;

        public HandshakeFailure(int status, String statusLine, Map<String, String> headers) {
            super("websocket handshake " + statusLine);
            this.status = status;
            this.statusLine = statusLine;
            this.headers = headers;
        }
    }

    public enum Type { TEXT, BINARY, PING, PONG, CLOSE }

    public static final class Frame {
        public final Type type;
        public final byte[] data;
        /** Only for CLOSE. */
        public final int code;

        Frame(Type type, byte[] data, int code) { this.type = type; this.data = data; this.code = code; }

        public String text() { return new String(data, StandardCharsets.UTF_8); }
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private final SecureRandom rnd = new SecureRandom();
    private volatile boolean closed;

    private WebSocketClient(Socket socket, InputStream in, OutputStream out) {
        this.socket = socket;
        this.in = in;
        this.out = out;
    }

    /**
     * Connects and performs the upgrade handshake. {@code url} is ws:// or wss://.
     * readTimeoutMs also applies to receive(); 0 = forever.
     */
    public static WebSocketClient connect(String url, Map<String, String> headers, int connectTimeoutMs, int readTimeoutMs,
                                          CancelToken cancel) throws IOException {
        URI u = URI.create(url);
        boolean tls = "wss".equalsIgnoreCase(u.getScheme());
        int port = u.getPort() > 0 ? u.getPort() : (tls ? 443 : 80);
        String path = u.getRawPath() == null ? "/" : u.getRawPath();
        if (u.getRawQuery() != null) path += "?" + u.getRawQuery();
        String host = u.getHost();

        Socket s;
        if (tls) {
            s = SSLSocketFactory.getDefault().createSocket();
        } else {
            s = new Socket();
        }
        try {
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            if (readTimeoutMs > 0) s.setSoTimeout(readTimeoutMs);
            if (tls) ((SSLSocket) s).startHandshake();
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw e;
        }
        if (cancel != null) cancel.onCancel(() -> { try { s.close(); } catch (IOException ignored) { } });

        String key = Base64.getEncoder().encodeToString(randomBytes(16));
        StringBuilder req = new StringBuilder(512);
        req.append("GET ").append(path).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(host);
        if (port != (tls ? 443 : 80)) req.append(':').append(port);
        req.append("\r\n");
        req.append("Upgrade: websocket\r\nConnection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        req.append("\r\n");
        OutputStream out = NetThrottle.out(s.getOutputStream());
        out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();

        InputStream in = new BufferedInputStream(NetThrottle.in(s.getInputStream()), 16384);
        String statusLine = readLine(in);
        Map<String, String> resp = new LinkedHashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int c = line.indexOf(':');
            if (c > 0) resp.put(line.substring(0, c).trim().toLowerCase(), line.substring(c + 1).trim());
        }
        int status = 0;
        if (statusLine != null && statusLine.startsWith("HTTP/1.") && statusLine.length() > 12) {
            try { status = Integer.parseInt(statusLine.substring(9, 12).trim()); } catch (NumberFormatException ignored) { }
        }
        if (status != 101) {
            try { s.close(); } catch (IOException ignored) { }
            throw new HandshakeFailure(status, statusLine == null ? "no response" : statusLine, resp);
        }
        String expect;
        try {
            expect = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw new IOException("websocket: SHA-1 недоступен", e);
        }
        if (!expect.equals(resp.get("sec-websocket-accept"))) {
            try { s.close(); } catch (IOException ignored) { }
            throw new IOException("websocket: неверный Sec-WebSocket-Accept");
        }
        return new WebSocketClient(s, in, out);
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(80);
        int prev = -1;
        for (; ; ) {
            int c = in.read();
            if (c < 0) return sb.length() == 0 && prev < 0 ? null : sb.toString();
            if (prev == '\r' && c == '\n') return sb.substring(0, sb.length() - 1);
            sb.append((char) c);
            prev = c;
        }
    }

    public void sendText(String text) throws IOException { sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8)); }

    public void sendBinary(byte[] data) throws IOException { sendFrame(0x2, data); }

    public void sendPing(byte[] payload) throws IOException { sendFrame(0x9, payload == null ? new byte[0] : payload); }

    public void sendPong(byte[] payload) throws IOException { sendFrame(0xA, payload == null ? new byte[0] : payload); }

    /** Sends a CLOSE frame; the socket stays open until {@link #close()}. */
    public void sendClose(int code, String reason) throws IOException {
        byte[] r = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + r.length];
        payload[0] = (byte) (code >>> 8);
        payload[1] = (byte) code;
        System.arraycopy(r, 0, payload, 2, r.length);
        sendFrame(0x8, payload);
    }

    private void sendFrame(int opcode, byte[] data) throws IOException {
        if (closed) throw new IOException("websocket closed");
        synchronized (writeLock) {
            byte[] mask = randomBytes(4);
            int len = data.length;
            int header = 2 + (len >= 65536 ? 8 : len >= 126 ? 2 : 0) + 4;
            byte[] frame = new byte[header + len];
            int p = 0;
            frame[p++] = (byte) (0x80 | opcode);
            if (len < 126) {
                frame[p++] = (byte) (0x80 | len);
            } else if (len < 65536) {
                frame[p++] = (byte) (0x80 | 126);
                frame[p++] = (byte) (len >>> 8);
                frame[p++] = (byte) len;
            } else {
                frame[p++] = (byte) (0x80 | 127);
                for (int i = 7; i >= 0; i--) frame[p++] = (byte) (len >>> (8 * i));
            }
            System.arraycopy(mask, 0, frame, p, 4);
            p += 4;
            for (int i = 0; i < len; i++) frame[p + i] = (byte) (data[i] ^ mask[i & 3]);
            out.write(frame);
            out.flush();
        }
    }

    /** Blocking receive of the next complete message. Pings are answered transparently. */
    public Frame receive() throws IOException {
        ByteArrayOutputStreamish msg = new ByteArrayOutputStreamish();
        int msgOpcode = -1;
        for (; ; ) {
            int b1 = in.read();
            if (b1 < 0) throw new IOException("websocket: соединение закрыто");
            int b2 = in.read();
            if (b2 < 0) throw new IOException("websocket: соединение закрыто");
            boolean fin = (b1 & 0x80) != 0;
            int opcode = b1 & 0x0f;
            boolean masked = (b2 & 0x80) != 0;
            long len = b2 & 0x7f;
            if (len == 126) len = readShort();
            else if (len == 127) len = readLong();
            if (len < 0 || len > MAX_MESSAGE) throw new IOException("websocket: слишком большой кадр (" + len + " байт)");
            byte[] maskKey = null;
            if (masked) {
                maskKey = new byte[4];
                readFully(in, maskKey);
            }
            byte[] payload = new byte[(int) len];
            readFully(in, payload);
            if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= maskKey[i & 3];

            switch (opcode) {
                case 0x0: // continuation
                    if (msgOpcode < 0) throw new IOException("websocket: неожиданный continuation-кадр");
                    msg.append(payload);
                    if (fin) return dataFrame(msgOpcode, msg.take());
                    break;
                case 0x1:
                case 0x2:
                    if (!fin) { msgOpcode = opcode; msg.append(payload); break; }
                    return dataFrame(opcode, payload);
                case 0x8: {
                    int code = payload.length >= 2 ? ((payload[0] & 0xff) << 8) | (payload[1] & 0xff) : 1005;
                    return new Frame(Type.CLOSE, payload.length > 2 ? slice(payload, 2) : new byte[0], code);
                }
                case 0x9: // ping -> pong, keep reading
                    sendPong(payload);
                    break;
                case 0xA:
                    return new Frame(Type.PONG, payload, -1);
                default:
                    throw new IOException("websocket: неизвестный opcode " + opcode);
            }
        }
    }

    private static Frame dataFrame(int opcode, byte[] data) {
        return new Frame(opcode == 0x1 ? Type.TEXT : Type.BINARY, data, -1);
    }

    private static byte[] slice(byte[] b, int from) {
        byte[] r = new byte[b.length - from];
        System.arraycopy(b, from, r, 0, r.length);
        return r;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("websocket: соединение закрыто посреди кадра");
            off += n;
        }
    }

    private int readShort() throws IOException {
        int a = in.read(), b = in.read();
        if (b < 0) throw new IOException("websocket: соединение закрыто посреди кадра");
        return ((a & 0xff) << 8) | (b & 0xff);
    }

    private long readLong() throws IOException {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            int c = in.read();
            if (c < 0) throw new IOException("websocket: соединение закрыто посреди кадра");
            v = (v << 8) | (c & 0xff);
        }
        return v;
    }

    @Override public void close() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) { }
    }

    /** Changes the read timeout of the underlying socket (0 = forever). */
    public void setReadTimeout(int ms) {
        try { socket.setSoTimeout(ms); } catch (IOException ignored) { }
    }

    public boolean isClosed() { return closed || socket.isClosed(); }

    /** Growable byte buffer without the resize cost of ByteArrayOutputStream(byte[]) copies on take. */
    private static final class ByteArrayOutputStreamish {
        private byte[] buf = new byte[1024];
        private int size;

        void append(byte[] b) {
            if (size + b.length > buf.length) {
                int cap = buf.length;
                while (cap < size + b.length) cap <<= 1;
                byte[] nb = new byte[cap];
                System.arraycopy(buf, 0, nb, 0, size);
                buf = nb;
            }
            System.arraycopy(b, 0, buf, size, b.length);
            size += b.length;
        }

        byte[] take() {
            byte[] r = new byte[size];
            System.arraycopy(buf, 0, r, 0, size);
            size = 0;
            return r;
        }
    }
}
