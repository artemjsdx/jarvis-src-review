package app.jarvis.core.voice;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Local HTTP server for provider protocol tests: gives RAW request bodies (MockServer decodes as UTF-8,
 * which corrupts binary multipart uploads), header access, and a scripted queue of responses
 * (drop-connection for retry tests). Real sockets, no external deps.
 */
public final class VoiceMockServer implements AutoCloseable {
    /** One recorded request with its raw body. */
    public static final class Req {
        public final String method, path, query, contentType;
        public final byte[] body;
        public final com.sun.net.httpserver.Headers headers;
        final HttpExchange x;
        Req(HttpExchange x, byte[] body) {
            this.x = x;
            this.method = x.getRequestMethod();
            this.path = x.getRequestURI().getPath();
            this.query = x.getRequestURI().getRawQuery() == null ? "" : x.getRequestURI().getRawQuery();
            this.contentType = x.getRequestHeaders().getFirst("Content-Type") == null ? "" : x.getRequestHeaders().getFirst("Content-Type");
            this.body = body;
            this.headers = x.getRequestHeaders();
        }
        public String header(String name) { String v = headers.getFirst(name); return v == null ? "" : v; }
        public String bodyText() { return new String(body, StandardCharsets.UTF_8); }
    }

    public interface H { void handle(Req r) throws IOException; }

    public final int port;
    private final HttpServer server;
    public final List<Req> requests = new CopyOnWriteArrayList<>();
    /** First error thrown by a handler (assert failures inside handlers land here instead of hanging the client). */
    public volatile Throwable handlerError;
    private final Queue<H> script = new ConcurrentLinkedQueue<>();
    /** Used when the script queue is empty. */
    public volatile H fallback = r -> json(r, 404, "{\"error\":\"no fallback\"}");

    public VoiceMockServer() throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = s.getAddress().getPort();
        s.createContext("/", x -> {
            byte[] body = readAll(x.getRequestBody());
            Req r = new Req(x, body);
            requests.add(r);
            H h = script.poll();
            if (h == null) h = fallback;
            try {
                h.handle(r);
            } catch (IOException dropped) {
                // client or server aborted the connection; fine in retry tests
            } catch (Throwable t) {
                if (handlerError == null) handlerError = t;
                String at = t.getStackTrace().length > 0 ? " at " + t.getStackTrace()[0] : "";
                try { json(r, 500, "{\"handlerError\":\"" + (t + at).replace("\"", "'") + "\"}"); } catch (Exception ignored) { }
            }
        });
        s.setExecutor(Executors.newCachedThreadPool());
        s.start();
        server = s;
    }

    /** Enqueues a one-shot response; later requests hit fallback. */
    public void enqueue(H h) { script.add(h); }

    public String url(String pathAndQuery) { return "http://127.0.0.1:" + port + pathAndQuery; }

    @Override public void close() { server.stop(0); }

    // ---------------------------------------------------------------- response helpers

    public static void json(Req r, int code, String body) {
        bytes(r, code, body.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    public static void bytes(Req r, int code, byte[] body, String contentType) {
        try {
            r.x.getResponseHeaders().add("Content-Type", contentType);
            r.x.sendResponseHeaders(code, body.length);
            try (OutputStream os = r.x.getResponseBody()) { os.write(body); }
        } catch (IOException ignored) { }
    }

    /** Two chunks with a pause — proves the client streams instead of buffering. */
    public static void chunkedBytes(Req r, byte[] a, byte[] b, long pauseMs) {
        try {
            r.x.getResponseHeaders().add("Content-Type", "application/octet-stream");
            r.x.sendResponseHeaders(200, a.length + b.length);
            OutputStream os = r.x.getResponseBody();
            os.write(a);
            os.flush();
            Thread.sleep(pauseMs);
            os.write(b);
            os.flush();
            os.close();
        } catch (IOException | InterruptedException ignored) { }
    }

    /** Closes the connection without any response — the client sees a reset/EOF. */
    public static void drop(Req r) {
        try {
            r.x.close();
        } catch (Exception ignored) { }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    // ---------------------------------------------------------------- multipart parsing (tests)

    /** Text fields of a multipart body: name → value. */
    public static java.util.Map<String, String> multipartTextFields(byte[] body, String contentType) {
        String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
        java.util.Map<String, String> out = new java.util.HashMap<>();
        String[] parts = new String(body, StandardCharsets.ISO_8859_1).split("--" + boundary);
        for (String p : parts) {
            int hd = p.indexOf("\r\n\r\n");
            if (hd < 0 || !p.contains("Content-Disposition")) continue;
            String head = p.substring(0, hd);
            String name = between(head, "name=\"", "\"");
            if (!head.contains("filename=")) out.put(name, p.substring(hd + 4).replaceAll("\r\n$", ""));
        }
        return out;
    }

    /** File part of a multipart body: name → exact bytes. */
    public static byte[] multipartFileBytes(byte[] body, String contentType, String name) {
        String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
        byte[] sep = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int i = indexOf(body, sep, 0);
        while (i >= 0) {
            int hd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), i);
            if (hd < 0) break;
            String head = new String(body, i, hd - i, StandardCharsets.ISO_8859_1);
            if (head.contains("name=\"" + name + "\"") && head.contains("filename=")) {
                int start = hd + 4;
                int end = indexOf(body, sep, start);
                if (end < 0) end = body.length;
                int len = end - start - 2; // trailing \r\n before boundary
                byte[] out = new byte[len];
                System.arraycopy(body, start, out, 0, len);
                return out;
            }
            i = indexOf(body, sep, i + sep.length);
        }
        return null;
    }

    private static String between(String s, String a, String b) {
        int i = s.indexOf(a);
        if (i < 0) return "";
        int j = s.indexOf(b, i + a.length());
        return j < 0 ? "" : s.substring(i + a.length(), j);
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
