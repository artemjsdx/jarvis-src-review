package app.jarvis.core.mcp.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A real MCP server on 127.0.0.1 for client tests, speaking either Streamable HTTP ({@code /mcp}) or the legacy
 * HTTP+SSE pair ({@code /sse} + {@code /messages}). Failure switches are public fields set by the tests.
 */
public final class FakeMcpServer implements AutoCloseable {
    public enum Mode { STREAMABLE, LEGACY }

    public final Mode mode;
    private final HttpServer http;
    public final int port;

    // ---- behaviour switches (set before the calls they affect) ----
    /** Streamable: answer tool calls with an SSE body instead of JSON. */
    public volatile boolean sseResponses;
    /** Streamable: serve the optional GET stream (otherwise 405). */
    public volatile boolean getStream;
    /** Tools/list page size; 0 = one page. */
    public volatile int pageSize;
    /** Streamable: answer 404 to this many requests that carry a session id (the server forgot the session). */
    public volatile int forgetSessionTimes;
    /** Streamable: answer this many POSTs with 503 before behaving. */
    public volatile int fail503;
    /** Emit a garbage event, a comment and an unrelated event before each answer. */
    public volatile boolean malformed;
    /** Streamable SSE: write half of the answer and close the connection. */
    public volatile boolean truncateNextAnswer;
    /** Legacy: close the event stream once right after the endpoint event. */
    public volatile boolean dropLegacyStreamOnce;
    /** Version echoed back on initialize (default: the client's latest supported one). */
    public volatile String protocolOverride = "";
    /** When false, initialize answers with a version the client does not support. */
    public volatile boolean supportVersions = true;

    public final List<String> methods = new CopyOnWriteArrayList<>();
    public final List<String> cancelled = new CopyOnWriteArrayList<>();
    public final List<String> authHeaders = new CopyOnWriteArrayList<>();
    public final List<String> protocolHeaders = new CopyOnWriteArrayList<>();
    public final List<String> callArgs = new CopyOnWriteArrayList<>();
    public final AtomicCounter initializes = new AtomicCounter();
    public final AtomicCounter getOpened = new AtomicCounter();

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final BlockingQueue<String> pushes = new LinkedBlockingQueue<>();
    private volatile String session = "";
    private volatile boolean running = true;
    private volatile int sessionSeq;

    /** Tiny counter (avoids AtomicInteger noise in tests). */
    public static final class AtomicCounter {
        private volatile int v;
        public synchronized void inc() { v++; }
        public synchronized int get() { return v; }
    }

    private interface Impl { JSONObject call(JSONObject args, Object id) throws Exception; }

    private static final class Tool {
        final JSONObject def;
        final Impl impl;
        Tool(JSONObject def, Impl impl) { this.def = def; this.impl = impl; }
    }

    public FakeMcpServer(Mode mode) throws IOException {
        this.mode = mode;
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = http.getAddress().getPort();
        http.createContext("/", new com.sun.net.httpserver.HttpHandler() {
            @Override public void handle(HttpExchange x) throws IOException { dispatch(x); }
        });
        http.setExecutor(Executors.newCachedThreadPool());
        registerTools();
        http.start();
    }

    /** Full URL of the endpoint a client should be configured with. */
    public String url() { return mode == Mode.STREAMABLE ? "http://127.0.0.1:" + port + "/mcp" : "http://127.0.0.1:" + port + "/sse"; }

    /** Adds a tool at runtime and tells the client with notifications/tools/list_changed. */
    public void addToolAndNotify(String name) {
        synchronized (tools) {
            tools.put(name, simpleTool(name, false, "добавленный инструмент " + name, new Impl() {
                @Override public JSONObject call(JSONObject args, Object id) { return text("ok"); }
            }));
        }
        pushes.add(sse("notifications/tools/list_changed", null));
    }

    public void pushListChanged() { pushes.add(sse("notifications/tools/list_changed", null)); }

    @Override public void close() {
        if (!running) return;
        running = false;
        http.stop(0);
    }

    // ------------------------------------------------------------------------------------------ tools

    private void registerTools() {
        tools.put("echo", simpleTool("echo", false, "Возвращает переданный текст", new Impl() {
            @Override public JSONObject call(JSONObject args, Object id) {
                return text(args.optString("text", ""));
            }
        }));
        tools.put("add", simpleTool("add", true, "Складывает два числа", new Impl() {
            @Override public JSONObject call(JSONObject args, Object id) {
                return text(String.valueOf(args.optInt("a") + args.optInt("b")));
            }
        }));
        tools.put("fail", simpleTool("fail", false, "Всегда отвечает isError", new Impl() {
            @Override public JSONObject call(JSONObject args, Object id) {
                JSONObject r = text("сломалось на сервере");
                r.put("isError", true);
                return r;
            }
        }));
        tools.put("boom", simpleTool("boom", false, "Отвечает JSON-RPC ошибкой", new Impl() {
            @Override public JSONObject call(JSONObject args, Object id) throws Exception {
                throw new RpcError(-32000, "внутренняя ошибка сервера");
            }
        }));
        tools.put("slow", simpleTool("slow", true, "Долго работает, отменяется по notifications/cancelled", new Impl() {
            @Override public JSONObject call(JSONObject args, Object id) throws Exception {
                long until = System.currentTimeMillis() + args.optLong("ms", 5000);
                while (System.currentTimeMillis() < until) {
                    if (cancelled.contains(McpWire.idKey(id))) throw new RpcError(-32800, "cancelled");
                    Thread.sleep(10);
                }
                return text("done");
            }
        }));
        tools.put("big", simpleTool("big", true, "Возвращает очень большой текст", new Impl() {
            @Override public JSONObject call(JSONObject args, Object id) {
                StringBuilder sb = new StringBuilder();
                while (sb.length() < 400_000) sb.append("строка ").append(sb.length()).append(" — данные\n");
                return text(sb.toString());
            }
        }));
        tools.put("read_thing", simpleTool("read_thing", true, "Читает данные, ничего не меняет", new Impl() {
            @Override public JSONObject call(JSONObject args, Object id) { return text("прочитано"); }
        }));
        tools.put("send_msg", simpleTool("send_msg", false, "Отправляет сообщение (запись)", new Impl() {
            @Override public JSONObject call(JSONObject args, Object id) {
                callArgs.add(args.toString());
                return text("отправлено");
            }
        }));
        tools.put("notify_change", simpleTool("notify_change", true, "Добавляет инструмент и присылает list_changed", new Impl() {
            @Override public JSONObject call(JSONObject args, Object id) {
                addToolAndNotify("late_tool");
                return text("ok");
            }
        }));
    }

    private static Tool simpleTool(String name, boolean readOnly, String desc, Impl impl) {
        JSONObject def = new JSONObject();
        def.put("name", name);
        def.put("description", desc);
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        JSONObject props = new JSONObject();
        JSONObject p = new JSONObject();
        p.put("type", "string");
        p.put("description", "параметр");
        props.put("text", p);
        schema.put("properties", props);
        def.put("inputSchema", schema);
        if (readOnly) def.put("annotations", new JSONObject().put("readOnlyHint", true));
        return new Tool(def, impl);
    }

    private static JSONObject text(String s) {
        JSONObject r = new JSONObject();
        JSONArray c = new JSONArray();
        c.put(new JSONObject().put("type", "text").put("text", s));
        r.put("content", c);
        return r;
    }

    /** Thrown by tool implementations to answer with a JSON-RPC error object. */
    static final class RpcError extends Exception {
        final int code;
        RpcError(int code, String msg) { super(msg); this.code = code; }
    }

    // ------------------------------------------------------------------------------------ JSON-RPC core

    /** Null for notifications. */
    private JSONObject handle(JSONObject msg) {
        String method = msg.optString("method", "");
        Object id = msg.opt("id");
        try {
            switch (method) {
                case "initialize": {
                    initializes.inc();
                    String want = msg.optJSONObject("params") == null ? "" : msg.getJSONObject("params").optString("protocolVersion", "");
                    String v = !protocolOverride.isEmpty() ? protocolOverride : supportVersions ? (want.isEmpty() ? "2025-06-18" : want) : "1999-01-01";
                    JSONObject r = new JSONObject();
                    r.put("protocolVersion", v);
                    r.put("capabilities", new JSONObject().put("tools", new JSONObject().put("listChanged", true)));
                    r.put("serverInfo", new JSONObject().put("name", "fake-mcp").put("version", "0.1"));
                    return ok(id, r);
                }
                case "ping": return ok(id, new JSONObject());
                case "tools/list": {
                    String cursor = msg.optJSONObject("params") == null ? "" : msg.getJSONObject("params").optString("cursor", "");
                    List<JSONObject> all = new ArrayList<>();
                    synchronized (tools) { for (Tool t : tools.values()) all.add(t.def); }
                    int from = cursor.isEmpty() ? 0 : Integer.parseInt(cursor);
                    int to = pageSize <= 0 ? all.size() : Math.min(all.size(), from + pageSize);
                    JSONArray arr = new JSONArray();
                    for (int i = from; i < to; i++) arr.put(all.get(i));
                    JSONObject r = new JSONObject().put("tools", arr);
                    if (pageSize > 0 && to < all.size()) r.put("nextCursor", String.valueOf(to));
                    return ok(id, r);
                }
                case "tools/call": {
                    JSONObject p = msg.getJSONObject("params");
                    String name = p.getString("name");
                    callArgs.add(name);
                    Tool t = tools.get(name);
                    if (t == null) return err(id, -32602, "Unknown tool: " + name);
                    return ok(id, t.impl.call(p.optJSONObject("arguments") == null ? new JSONObject() : p.getJSONObject("arguments"), id));
                }
                case "resources/list": return ok(id, new JSONObject().put("resources", new JSONArray().put(new JSONObject().put("uri", "mem://note").put("name", "note"))));
                case "resources/read": return ok(id, new JSONObject().put("contents", new JSONArray().put(new JSONObject().put("uri", "mem://note").put("text", "заметка"))));
                default: return err(id, -32601, "Method not found: " + method);
            }
        } catch (RpcError e) {
            return err(id, e.code, e.getMessage());
        } catch (Exception e) {
            return err(id, -32603, String.valueOf(e.getMessage()));
        }
    }

    private static JSONObject ok(Object id, JSONObject result) {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result);
    }

    private static JSONObject err(Object id, int code, String msg) {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", new JSONObject().put("code", code).put("message", msg));
    }

    private static String sse(String method, Object id) {
        JSONObject m = new JSONObject().put("jsonrpc", "2.0").put("method", method);
        if (id != null) m.put("id", id);
        return "event: message\ndata: " + m.toString() + "\n\n";
    }

    private static String sseMessage(JSONObject m) { return "event: message\ndata: " + m.toString() + "\n\n"; }

    // ------------------------------------------------------------------------------------ HTTP

    private void dispatch(HttpExchange x) throws IOException {
        String auth = x.getRequestHeaders().getFirst("Authorization");
        authHeaders.add(auth == null ? "" : auth);
        if (mode == Mode.STREAMABLE) streamable(x); else legacy(x);
    }

    private void streamable(HttpExchange x) throws IOException {
        if (!"/mcp".equals(x.getRequestURI().getPath())) { reply(x, 404, ""); return; }
        String method = x.getRequestMethod();
        if ("DELETE".equals(method)) { session = ""; reply(x, 200, ""); return; }
        if ("GET".equals(method)) {
            if (!getStream) { reply(x, 405, ""); return; }
            getOpened.inc();
            serveStream(x, false);
            return;
        }
        if (!"POST".equals(method)) { reply(x, 405, ""); return; }
        if (fail503 > 0) { fail503--; reply(x, 503, ""); return; }
        String body = read(x.getRequestBody());
        JSONObject msg = McpWire.parseObject(body);
        if (msg == null) { reply(x, 400, ""); return; }
        String sid = x.getRequestHeaders().getFirst("Mcp-Session-Id");
        String ver = x.getRequestHeaders().getFirst("MCP-Protocol-Version");
        if (ver != null) protocolHeaders.add(ver);
        String mname = msg.optString("method", "");
        if (!"initialize".equals(mname)) {
            if (forgetSessionTimes > 0 && sid != null) { forgetSessionTimes--; reply(x, 404, ""); return; }
            if (session.isEmpty() || sid == null || !sid.equals(session)) { reply(x, 400, "no session"); return; }
        }
        methods.add(mname);
        if (!msg.has("id")) {
            if ("notifications/cancelled".equals(mname)) cancelled.add(McpWire.idKey(msg.optJSONObject("params").opt("requestId")));
            reply(x, 202, "");
            return;
        }
        if ("initialize".equals(mname)) {
            session = "sess-" + (++sessionSeq);
            x.getResponseHeaders().add("Mcp-Session-Id", session);
        }
        JSONObject resp = handle(msg);
        if (sseResponses && !"initialize".equals(mname)) {
            StringBuilder sb = new StringBuilder();
            if (malformed) sb.append(garbage());
            if ("notify_change".equals(McpWire.str(msg.optJSONObject("params"), "name", ""))) sb.append(sse("notifications/tools/list_changed", null));
            sb.append(sseMessage(resp));
            if (truncateNextAnswer) {
                truncateNextAnswer = false;
                String half = sb.toString();
                half = half.substring(0, Math.max(1, half.length() / 2));
                x.getResponseHeaders().add("Content-Type", "text/event-stream");
                x.sendResponseHeaders(200, 0);
                x.getResponseBody().write(half.getBytes(StandardCharsets.UTF_8));
                x.getResponseBody().flush();
                x.close();
                return;
            }
            x.getResponseHeaders().add("Content-Type", "text/event-stream");
            byte[] b = sb.toString().getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, b.length);
            x.getResponseBody().write(b);
            x.close();
            return;
        }
        if (resp.optJSONObject("result") != null && "notify_change".equals(McpWire.str(msg.optJSONObject("params"), "name", ""))) {
            pushes.add(sse("notifications/tools/list_changed", null));
        }
        x.getResponseHeaders().add("Content-Type", "application/json");
        byte[] b = resp.toString().getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(200, b.length);
        x.getResponseBody().write(b);
        x.close();
    }

    private void legacy(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        if ("GET".equals(x.getRequestMethod()) && "/sse".equals(path)) {
            getOpened.inc();
            serveStream(x, true);
            return;
        }
        if ("POST".equals(x.getRequestMethod()) && "/messages".equals(path)) {
            JSONObject msg = McpWire.parseObject(read(x.getRequestBody()));
            if (msg == null) { reply(x, 400, ""); return; }
            methods.add(msg.optString("method", ""));
            if (!msg.has("id")) {
                if ("notifications/cancelled".equals(msg.optString("method", ""))) cancelled.add(McpWire.idKey(msg.optJSONObject("params").opt("requestId")));
                reply(x, 202, "");
                return;
            }
            JSONObject resp = handle(msg);
            if (malformed) pushes.add(garbage());
            pushes.add(sseMessage(resp));
            if (!"initialize".equals(msg.optString("method", "")) && resp.optJSONObject("result") != null
                && "notify_change".equals(McpWire.str(msg.optJSONObject("params"), "name", ""))) {
                pushes.add(sse("notifications/tools/list_changed", null));
            }
            reply(x, 202, "");
            return;
        }
        reply(x, 404, "");
    }

    /** Writes an endpoint event (legacy only) and then whatever is queued until the client leaves. */
    private void serveStream(HttpExchange x, boolean legacyEndpoint) throws IOException {
        x.getResponseHeaders().add("Content-Type", "text/event-stream");
        x.sendResponseHeaders(200, 0);
        OutputStream os = x.getResponseBody();
        try {
            if (legacyEndpoint) {
                os.write(("event: endpoint\ndata: /messages?session=" + (++sessionSeq) + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
                if (dropLegacyStreamOnce) { dropLegacyStreamOnce = false; x.close(); return; }
            } else {
                os.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            while (running) {
                String ev = pushes.poll(50, TimeUnit.MILLISECONDS);
                if (ev == null) continue;
                os.write(ev.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } catch (IOException e) {
            // client went away: normal end of this stream
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try { x.close(); } catch (RuntimeException ignored) { }
        }
    }

    private String garbage() {
        return "event: message\ndata: {это не JSON\n\n: комментарий\n\nevent: other\ndata: {\"x\":1}\n\n";
    }

    private static void reply(HttpExchange x, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) x.getResponseBody().write(b);
        x.close();
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Names of tools as the server currently lists them (for asserts). */
    public List<String> toolNames() {
        synchronized (tools) { return Collections.unmodifiableList(new ArrayList<>(tools.keySet())); }
    }
}
