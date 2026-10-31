package app.jarvis.core.mcp;

import app.jarvis.core.util.J;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/**
 * Minimal HTTP/1.1 server for MCP Streamable HTTP, bound to 127.0.0.1 only. Keep-alive, Content-Length bodies,
 * 4 MiB body cap, 8 simultaneous connections, 30 s read timeout, constant-time bearer check, Host/Origin validation.
 */
public final class McpServer implements Closeable {
    public static final int MAX_BODY = 4 * 1024 * 1024;
    public static final int MAX_CONNECTIONS = 8;
    public static final int READ_TIMEOUT_MS = 30_000;
    private static final int DRAIN_MAX = 64 * 1024;
    private static final int MAX_LINE = 8192, MAX_HEADERS = 64 * 1024;

    public interface TokenSource { String token(); }
    public interface HealthSource { JSONObject health(); }

    private final McpDispatcher dispatcher;
    private final TokenSource tokens;
    private final HealthSource health;
    private final long startedMs = System.currentTimeMillis();
    private final int readTimeoutMs;
    private final Semaphore slots = new Semaphore(MAX_CONNECTIONS);
    private final AtomicInteger connN = new AtomicInteger();
    private final ExecutorService conns = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "jv-mcp-conn-" + connN.incrementAndGet()); t.setDaemon(true); return t; }
    });
    private volatile ServerSocket ss;
    private volatile boolean running;
    private volatile int port;

    public McpServer(McpDispatcher dispatcher, TokenSource tokens, HealthSource health) { this(dispatcher, tokens, health, READ_TIMEOUT_MS); }

    public McpServer(McpDispatcher dispatcher, TokenSource tokens, HealthSource health, int readTimeoutMs) {
        this.dispatcher = dispatcher;
        this.tokens = tokens;
        this.health = health;
        this.readTimeoutMs = readTimeoutMs;
    }

    /** Binds 127.0.0.1:preferred (0 = any), trying the next ports when taken. Returns the real port. */
    public synchronized int start(int preferred, int tries) throws IOException {
        if (running) return port;
        InetAddress lo = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        IOException last = null;
        for (int i = 0; i < Math.max(1, tries); i++) {
            ServerSocket s = new ServerSocket();
            try {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(lo, preferred == 0 ? 0 : preferred + i), 16);
                ss = s;
                port = s.getLocalPort();
                running = true;
                Thread t = new Thread(new Runnable() { @Override public void run() { acceptLoop(); } }, "jv-mcp-accept");
                t.setDaemon(true);
                t.start();
                return port;
            } catch (BindException e) {
                last = e;
                try { s.close(); } catch (IOException ignored) { }
            }
        }
        throw last != null ? last : new IOException("cannot bind");
    }

    public int port() { return port; }
    public String bindAddress() { ServerSocket s = ss; return s == null ? "" : s.getInetAddress().getHostAddress() + ":" + s.getLocalPort(); }
    public boolean isRunning() { return running; }

    @Override public synchronized void close() {
        running = false;
        try { if (ss != null) ss.close(); } catch (IOException ignored) { }
        conns.shutdownNow();
        dispatcher.shutdown();
    }

    // ---- accept / connection ----------------------------------------------------------------------------------------
    private void acceptLoop() {
        while (running) {
            final Socket s;
            try { s = ss.accept(); } catch (IOException e) { if (!running) return; continue; }
            if (!slots.tryAcquire()) { rejectBusy(s); continue; }
            try {
                conns.execute(new Runnable() { @Override public void run() { try { serve(s); } finally { slots.release(); } } });
            } catch (RuntimeException e) { slots.release(); closeQuiet(s); }
        }
    }

    private void rejectBusy(Socket s) {
        try {
            s.setSoTimeout(1000);
            OutputStream o = s.getOutputStream();
            writeResponse(o, 503, "application/json", "{\"error\":\"too many connections\"}".getBytes(StandardCharsets.UTF_8), false, null);
            o.flush();
        } catch (IOException ignored) { }
        closeQuiet(s);
    }

    private static void closeQuiet(Socket s) { try { s.close(); } catch (IOException ignored) { } }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(readTimeoutMs);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream(), 8192);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 8192);
            while (running) {
                Req r;
                try {
                    r = readRequest(in, out);
                } catch (HttpError e) {
                    writeResponse(out, e.status, "application/json", errJson(e.getMessage()), false, null);
                    out.flush();
                    return;
                }
                if (r == null) return;
                boolean keep = handle(r, out);
                out.flush();
                if (!keep) return;
            }
        } catch (IOException ignored) {
            // peer went away or read timed out: nothing to report
        } catch (Throwable t) {
            // never leak a stack trace to the peer; the socket just closes
        } finally { closeQuiet(s); }
    }

    // ---- request parsing --------------------------------------------------------------------------------------------
    private static final class HttpError extends Exception {
        final int status;
        HttpError(int status, String msg) { super(msg); this.status = status; }
    }

    private static final class Req {
        String method, path, version;
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];
        String header(String k) { return headers.get(k); }
        boolean keepAlive() {
            String c = headers.get("connection");
            if (c != null) { c = c.toLowerCase(Locale.ROOT); if (c.contains("close")) return false; if (c.contains("keep-alive")) return true; }
            return "HTTP/1.1".equals(version);
        }
    }

    /** Reads one request; null on clean EOF / idle timeout before the first byte. */
    private Req readRequest(InputStream in, OutputStream out) throws IOException, HttpError {
        String line;
        try {
            line = readLine(in, true);
        } catch (SocketTimeoutException e) {
            return null;
        }
        if (line == null) return null;
        while (line.isEmpty()) { line = readLine(in, true); if (line == null) return null; } // tolerate stray CRLF between requests
        try {
            String[] p = line.split(" ");
            if (p.length != 3 || !p[2].startsWith("HTTP/1.")) throw new HttpError(400, "bad request line");
            Req r = new Req();
            r.method = p[0];
            r.path = p[1];
            r.version = p[2];
            int total = 0;
            while (true) {
                String h = readLine(in, false);
                if (h == null) throw new HttpError(400, "unexpected EOF in headers");
                if (h.isEmpty()) break;
                total += h.length();
                if (total > MAX_HEADERS) throw new HttpError(431, "headers too large");
                int c = h.indexOf(':');
                if (c <= 0) throw new HttpError(400, "bad header");
                r.headers.put(h.substring(0, c).trim().toLowerCase(Locale.ROOT), h.substring(c + 1).trim());
            }
            if (r.headers.containsKey("transfer-encoding")) throw new HttpError(411, "chunked bodies are not supported, send Content-Length");
            String cl = r.headers.get("content-length");
            long len = 0;
            if (cl != null) {
                try { len = Long.parseLong(cl); } catch (NumberFormatException e) { throw new HttpError(400, "bad Content-Length"); }
                if (len < 0) throw new HttpError(400, "bad Content-Length");
                if (len > MAX_BODY) throw new HttpError(413, "body larger than " + MAX_BODY + " bytes");
            }
            // Headers are validated before the body is consumed, so an unauthenticated peer cannot make us buffer 4 MiB.
            if (len > 0) {
                String reject = preCheck(r);
                if (reject != null) {
                    r.headers.put("x-jv-reject", reject);
                    if (len > DRAIN_MAX) { r.body = null; return r; } // too big to drain: the connection will be closed
                    drain(in, len);
                    return r;
                }
                if ("100-continue".equalsIgnoreCase(r.headers.get("expect"))) { out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); out.flush(); }
                byte[] b = new byte[(int) len];
                int off = 0;
                while (off < b.length) {
                    int n = in.read(b, off, b.length - off);
                    if (n < 0) throw new HttpError(400, "unexpected EOF in body");
                    off += n;
                }
                r.body = b;
            }
            return r;
        } catch (SocketTimeoutException e) {
            throw new HttpError(408, "read timeout");
        }
    }

    private static void drain(InputStream in, long len) throws IOException {
        byte[] buf = new byte[4096];
        long left = len;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) throw new IOException("EOF while draining");
            left -= n;
        }
    }

    /** Returns "host"/"origin"/"auth" when the request must be rejected without reading its body. */
    private String preCheck(Req r) {
        if (!hostOk(r.header("host"))) return "host";
        if (!originOk(r.header("origin"))) return "origin";
        if (!r.path.startsWith("/health") && !authOk(r)) return "auth";
        return null;
    }

    private static String readLine(InputStream in, boolean first) throws IOException, HttpError {
        ByteArrayOutputStream b = new ByteArrayOutputStream(64);
        while (true) {
            int c = in.read();
            if (c < 0) return b.size() == 0 && first ? null : (b.size() == 0 ? null : b.toString("ISO-8859-1"));
            if (c == '\n') {
                byte[] a = b.toByteArray();
                int n = a.length;
                if (n > 0 && a[n - 1] == '\r') n--;
                return new String(a, 0, n, StandardCharsets.ISO_8859_1);
            }
            b.write(c);
            if (b.size() > MAX_LINE) throw new HttpError(431, "line too long");
        }
    }

    // ---- security checks --------------------------------------------------------------------------------------------
    boolean hostOk(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(Locale.ROOT);
        String name = h;
        int p = -1;
        if (h.startsWith("[")) {
            int e = h.indexOf(']');
            if (e < 0) return false;
            name = h.substring(0, e + 1);
            if (h.length() > e + 1) { if (h.charAt(e + 1) != ':') return false; p = parsePort(h.substring(e + 2)); } else return false;
        } else {
            int c = h.lastIndexOf(':');
            if (c < 0) return false;
            name = h.substring(0, c);
            p = parsePort(h.substring(c + 1));
        }
        return p == port && (name.equals("127.0.0.1") || name.equals("localhost") || name.equals("[::1]"));
    }

    private static int parsePort(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; } }

    boolean originOk(String origin) {
        if (origin == null) return true;
        try {
            String h = new URI(origin.trim()).getHost();
            if (h == null) return false;
            h = h.toLowerCase(Locale.ROOT);
            return h.equals("127.0.0.1") || h.equals("localhost") || h.equals("[::1]") || h.equals("::1");
        } catch (Exception e) { return false; }
    }

    private boolean authOk(Req r) {
        String expected = tokens.token();
        if (expected == null || expected.isEmpty()) return false;
        String a = r.header("authorization");
        if (a == null || a.length() < 8 || !a.regionMatches(true, 0, "Bearer ", 0, 7)) {
            // still burn comparable time so the failure mode does not depend on the shape of the header
            MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), new byte[expected.length()]);
            return false;
        }
        byte[] got = a.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(got, expected.getBytes(StandardCharsets.UTF_8));
    }

    // ---- routing ----------------------------------------------------------------------------------------------------
    /** @return whether the connection stays open. */
    private boolean handle(Req r, OutputStream out) throws IOException {
        boolean keep = r.keepAlive();
        String rej = r.headers.get("x-jv-reject");
        if (rej == null) rej = preCheck(r);
        if (rej != null) {
            boolean bodyUnread = r.body == null;
            if (rej.equals("auth")) writeResponse(out, 401, "application/json", errJson("unauthorized"), keep && !bodyUnread, "WWW-Authenticate: Bearer realm=\"jarvis-mcp\"");
            else writeResponse(out, 403, "application/json", errJson("forbidden " + rej), keep && !bodyUnread, null);
            return keep && !bodyUnread;
        }
        String path = r.path;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.equals("/health")) {
            if (!r.method.equals("GET")) { writeResponse(out, 405, "application/json", errJson("method not allowed"), keep, "Allow: GET"); return keep; }
            JSONObject h = health == null ? new JSONObject() : health.health();
            J.put(h, "ok", true);
            J.put(h, "uptimeMs", System.currentTimeMillis() - startedMs);
            writeResponse(out, 200, "application/json", h.toString().getBytes(StandardCharsets.UTF_8), keep, null);
            return keep;
        }
        if (path.equals("/mcp")) {
            switch (r.method) {
                case "POST": {
                    String text = new String(r.body, StandardCharsets.UTF_8);
                    McpDispatcher.Out o = dispatcher.handleBody(text, r.header("mcp-session-id"));
                    String extra = o.sessionId != null ? "Mcp-Session-Id: " + o.sessionId : null;
                    if (o.body == null) { writeResponse(out, o.status, null, new byte[0], keep, extra); return keep; }
                    byte[] body = o.body.getBytes(StandardCharsets.UTF_8);
                    String accept = r.header("accept");
                    boolean sse = accept != null && accept.contains("text/event-stream") && !accept.contains("application/json") && !accept.contains("*/*");
                    if (sse) {
                        body = ("event: message\ndata: " + o.body + "\n\n").getBytes(StandardCharsets.UTF_8);
                        writeResponse(out, o.status, "text/event-stream", body, keep, extra);
                    } else writeResponse(out, o.status, "application/json", body, keep, extra);
                    return keep;
                }
                case "DELETE": writeResponse(out, 200, null, new byte[0], keep, null); return keep;
                default: writeResponse(out, 405, "application/json", errJson("only POST is supported"), keep, "Allow: POST, DELETE"); return keep;
            }
        }
        writeResponse(out, 404, "application/json", errJson("not found"), keep, null);
        return keep;
    }

    private static byte[] errJson(String m) { return J.put(new JSONObject(), "error", m).toString().getBytes(StandardCharsets.UTF_8); }

    private static void writeResponse(OutputStream out, int status, String type, byte[] body, boolean keep, String extraHeader) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        if (type != null) sb.append("Content-Type: ").append(type).append(type.startsWith("text/") ? "; charset=utf-8" : "; charset=utf-8").append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Cache-Control: no-store\r\n");
        if (extraHeader != null) sb.append(extraHeader).append("\r\n");
        sb.append("Connection: ").append(keep ? "keep-alive" : "close").append("\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
    }

    private static String reason(int s) {
        switch (s) {
            case 200: return "OK";
            case 202: return "Accepted";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 408: return "Request Timeout";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 431: return "Request Header Fields Too Large";
            case 503: return "Service Unavailable";
            default: return "Error";
        }
    }
}
