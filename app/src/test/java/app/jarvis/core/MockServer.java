package app.jarvis.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** Tiny local HTTP server so protocol tests exercise real sockets, real chunking and real disconnects. */
public final class MockServer implements AutoCloseable {
    public final HttpServer server;
    public final int port;
    public final List<String> bodies = new CopyOnWriteArrayList<>();
    public final List<String> authHeaders = new CopyOnWriteArrayList<>();
    public volatile boolean clientDisconnected;

    /** Handler that also receives the already-read request body. */
    public interface H { void handle(HttpExchange x, String body) throws IOException; }

    public MockServer(final HttpHandler h) throws IOException {
        this(new H() { @Override public void handle(HttpExchange x, String body) throws IOException { h.handle(x); } });
    }

    public MockServer(final H h) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override public void handle(HttpExchange x) throws IOException {
                String body = readAll(x.getRequestBody());
                bodies.add(body);
                String a = x.getRequestHeaders().getFirst("Authorization");
                authHeaders.add(a == null ? "" : a);
                h.handle(x, body);
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        port = server.getAddress().getPort();
    }

    public String base() { return "http://127.0.0.1:" + port + "/v1"; }
    public String url(String p) { return "http://127.0.0.1:" + port + p; }
    @Override public void close() { server.stop(0); }

    public static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    public static void json(HttpExchange x, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }

    /** Streams SSE events; sleepMs between events. Returns false if the client went away. */
    public static boolean sse(HttpExchange x, long sleepMs, String... events) throws IOException {
        x.getResponseHeaders().add("Content-Type", "text/event-stream");
        x.sendResponseHeaders(200, 0);
        OutputStream os = x.getResponseBody();
        try {
            for (String e : events) {
                os.write(("data: " + e + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
                if (sleepMs > 0) Thread.sleep(sleepMs);
            }
            os.close();
            return true;
        } catch (IOException | InterruptedException ex) {
            return false;
        }
    }

    public static String chunkText(String t) {
        return "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":" + org.json.JSONObject.quote(t) + "}}]}";
    }

    public static String chunkFinish(String reason) {
        return "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"" + reason + "\"}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"completion_tokens_details\":{\"reasoning_tokens\":3}}}";
    }
}
