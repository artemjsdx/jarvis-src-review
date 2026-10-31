package app.jarvis.core.mcp.client;

import app.jarvis.core.util.CancelToken;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * JSON-RPC 2.0 client for one external MCP server over a {@link McpTransport}. Requests are matched to responses by id;
 * a call may be cancelled (CancelToken) or time out, and both send notifications/cancelled to the server.
 * A session that the server forgot is re-initialized once, transparently.
 */
public final class McpClient implements Closeable, McpTransport.Sink {
    public static final String LATEST_PROTOCOL = "2025-06-18";
    static final String[] SUPPORTED_PROTOCOLS = {"2025-06-18", "2025-03-26", "2024-11-05"};
    /** Pages of tools/list before we assume a broken cursor loop. */
    static final int MAX_PAGES = 50;

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "jv-mcp-timer");
            t.setDaemon(true);
            return t;
        }
    });

    private final McpTransport transport;
    private final long timeoutMs;
    private final TransportOptions opt;
    private final AtomicLong ids = new AtomicLong(1);
    private final Map<String, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();
    private volatile Runnable toolsChanged;
    private volatile boolean closed;
    private volatile boolean lost;
    private volatile String protocolVersion = "";
    private volatile String serverName = "";
    private volatile String serverVersion = "";
    private volatile boolean serverHasTools;

    public McpClient(McpTransport transport, long timeoutMs, TransportOptions opt) {
        this.transport = transport;
        this.timeoutMs = timeoutMs <= 0 ? 60_000 : timeoutMs;
        this.opt = opt == null ? new TransportOptions() : opt;
    }

    public void setToolsChangedListener(Runnable r) { toolsChanged = r; }

    public String protocolVersion() { return protocolVersion; }
    public String serverName() { return serverName; }
    public String serverVersion() { return serverVersion; }
    public boolean serverHasTools() { return serverHasTools; }

    /** False after the transport gave up for good; the owner should build a new client. */
    public boolean isAlive() { return !closed && !lost; }

    /** Opens the transport and runs initialize; transient failures are retried with backoff (initialize is idempotent). */
    public void connect(CancelToken cancel) throws McpException {
        CancelToken c = cancel == null ? new CancelToken() : cancel;
        McpException last = null;
        for (int attempt = 1; attempt <= opt.maxAttempts; attempt++) {
            try {
                transport.open(this);
                initialize(c);
                return;
            } catch (McpException e) {
                if (!e.isTransient() || c.isCancelled()) throw e;
                last = e;
                transport.close();
                if (attempt < opt.maxAttempts && !c.sleep(opt.backoff(attempt))) {
                    throw new McpException(McpException.Kind.CANCELLED, "Отменено", "cancelled during backoff");
                }
            }
        }
        throw last;
    }

    private void initialize(CancelToken c) throws McpException {
        transport.resetSession();
        JSONObject p = new JSONObject();
        try {
            p.put("protocolVersion", LATEST_PROTOCOL);
            p.put("capabilities", new JSONObject());
            p.put("clientInfo", new JSONObject().put("name", "jarvis").put("version", "1"));
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
        JSONObject r = send("initialize", p, timeoutMs, c);
        String v = McpWire.str(r, "protocolVersion", "");
        if (!supported(v)) {
            throw new McpException(McpException.Kind.UNSUPPORTED, "Сервер использует неподдерживаемую версию протокола MCP" + (v.isEmpty() ? "" : " (" + v + ")"),
                "protocolVersion=" + v);
        }
        protocolVersion = v;
        transport.setProtocolVersion(v);
        JSONObject si = r.optJSONObject("serverInfo");
        serverName = McpWire.str(si, "name", "");
        serverVersion = McpWire.str(si, "version", "");
        JSONObject caps = r.optJSONObject("capabilities");
        serverHasTools = caps != null && caps.has("tools");
        notify("notifications/initialized", null);
    }

    private static boolean supported(String v) {
        for (String s : SUPPORTED_PROTOCOLS) if (s.equals(v)) return true;
        return false;
    }

    /** All tools, following nextCursor pages. */
    public List<RemoteTool> listTools(CancelToken cancel) throws McpException {
        CancelToken c = cancel == null ? new CancelToken() : cancel;
        List<RemoteTool> out = new ArrayList<>();
        String cursor = "";
        for (int page = 0; ; page++) {
            if (page >= MAX_PAGES) throw new McpException(McpException.Kind.PROTOCOL, "Сервер присылает слишком много страниц инструментов", "more than " + MAX_PAGES + " pages");
            JSONObject p = new JSONObject();
            if (!cursor.isEmpty()) {
                try { p.put("cursor", cursor); } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
            }
            JSONObject r = request("tools/list", p, c);
            JSONArray arr = r.optJSONArray("tools");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject t = arr.optJSONObject(i);
                    RemoteTool rt = t == null ? null : RemoteTool.fromJson(t);
                    if (rt != null) out.add(rt);
                }
            }
            String next = McpWire.str(r, "nextCursor", "");
            if (next.isEmpty()) return out;
            if (next.equals(cursor)) throw new McpException(McpException.Kind.PROTOCOL, "Сервер повторяет тот же курсор страниц", "cursor loop");
            cursor = next;
        }
    }

    public ToolCallResult callTool(String name, JSONObject arguments, CancelToken cancel) throws McpException {
        JSONObject p = new JSONObject();
        try {
            p.put("name", name);
            p.put("arguments", arguments == null ? new JSONObject() : arguments);
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
        return ToolCallResult.fromJson(request("tools/call", p, cancel));
    }

    public void ping(CancelToken cancel) throws McpException {
        request("ping", null, cancel);
    }

    /** The resources array of resources/list (raw JSON; resources are optional in MCP). */
    public JSONArray listResources(CancelToken cancel) throws McpException {
        JSONArray a = request("resources/list", null, cancel).optJSONArray("resources");
        return a == null ? new JSONArray() : a;
    }

    /** Text of a resource's text contents, capped like tool results. */
    public String readResource(String uri, CancelToken cancel) throws McpException {
        JSONObject p = new JSONObject();
        try { p.put("uri", uri); } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
        JSONArray contents = request("resources/read", p, cancel).optJSONArray("contents");
        StringBuilder sb = new StringBuilder();
        if (contents != null) for (int i = 0; i < contents.length(); i++) {
            JSONObject o = contents.optJSONObject(i);
            if (o != null && o.has("text")) { if (sb.length() > 0) sb.append('\n'); sb.append(McpWire.str(o, "text", "")); }
        }
        return ToolCallResult.capText(sb.toString());
    }

    /** Request with one re-initialize if the server says the session is gone. */
    private JSONObject request(String method, JSONObject params, CancelToken cancel) throws McpException {
        CancelToken c = cancel == null ? new CancelToken() : cancel;
        try {
            return send(method, params, timeoutMs, c);
        } catch (McpException e) {
            if (e.kind != McpException.Kind.SESSION_GONE || closed) throw e;
            initialize(c);
            return send(method, params, timeoutMs, c);
        }
    }

    private JSONObject send(String method, JSONObject params, long waitMs, CancelToken c) throws McpException {
        if (closed) throw new McpException(McpException.Kind.TRANSPORT, "Соединение с сервером закрыто", "client closed");
        if (lost) throw new McpException(McpException.Kind.TRANSPORT, "Соединение с сервером потеряно", "channel lost");
        if (c.isCancelled()) throw new McpException(McpException.Kind.CANCELLED, "Отменено", "cancelled before send");
        final long id = ids.getAndIncrement();
        final String key = McpWire.idKey(id);
        final CompletableFuture<JSONObject> f = new CompletableFuture<>();
        final CancelToken call = new CancelToken();
        pending.put(key, f);
        Runnable unhookCancel = c.onCancel(new Runnable() {
            @Override public void run() {
                f.completeExceptionally(new McpException(McpException.Kind.CANCELLED, "Отменено", "cancelled: " + method));
                call.cancel("cancelled");
            }
        });
        // the timer also cancels the transport: a streamable POST blocks until the answer, so only its abort can end the wait
        final ScheduledFuture<?> timer = TIMER.schedule(new Runnable() {
            @Override public void run() {
                f.completeExceptionally(new McpException(McpException.Kind.TIMEOUT, "Сервер не ответил за " + (waitMs / 1000) + " с", method + " timeout " + waitMs + " ms"));
                call.cancel("timeout");
            }
        }, waitMs, TimeUnit.MILLISECONDS);
        try {
            try {
                transport.post(McpWire.request(id, method, params), call);
            } catch (McpException e) {
                if (!f.isDone()) throw e;
            }
            JSONObject msg = f.get();
            if (msg.has("error")) {
                JSONObject err = msg.optJSONObject("error");
                String m = McpWire.str(err, "message", "ошибка");
                throw new McpException(McpException.Kind.RPC, "Сервер вернул ошибку: " + McpWire.clip(m, 200),
                    "code=" + McpWire.str(err, "code", "?") + " method=" + method, 0, null);
            }
            JSONObject result = msg.optJSONObject("result");
            return result == null ? new JSONObject() : result;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof McpException) {
                McpException me = (McpException) cause;
                if (me.kind == McpException.Kind.CANCELLED || me.kind == McpException.Kind.TIMEOUT) notifyCancelled(id, me.kind == McpException.Kind.TIMEOUT ? "timeout" : "cancelled");
                throw me;
            }
            throw new McpException(McpException.Kind.PROTOCOL, "Ошибка протокола MCP", String.valueOf(cause), 0, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notifyCancelled(id, "interrupted");
            throw new McpException(McpException.Kind.CANCELLED, "Отменено", "interrupted", 0, e);
        } finally {
            timer.cancel(false);
            pending.remove(key);
            unhookCancel.run();
        }
    }

    private void notifyCancelled(long id, String reason) {
        JSONObject p = new JSONObject();
        try { p.put("requestId", id).put("reason", reason); } catch (org.json.JSONException ignored) { return; }
        try {
            transport.post(McpWire.notification("notifications/cancelled", p), new CancelToken());
        } catch (McpException ignored) {
            // best effort: the server drops the request when the connection goes away anyway
        }
    }

    private void notify(String method, JSONObject params) throws McpException {
        transport.post(McpWire.notification(method, params), new CancelToken());
    }

    /** Incoming messages from the transport: responses complete pending calls; server requests and notifications are handled. */
    @Override public void onMessage(JSONObject m) {
        if (closed) return;
        if (McpWire.isResponse(m)) {
            CompletableFuture<JSONObject> f = pending.get(McpWire.idKey(m.opt("id")));
            if (f != null) f.complete(m);
            return;
        }
        String method = McpWire.str(m, "method", "");
        if (method.isEmpty()) return;
        if (m.has("id")) {
            JSONObject reply = "ping".equals(method)
                ? McpWire.response(m.opt("id"), new JSONObject())
                : McpWire.errorResponse(m.opt("id"), -32601, "Метод не поддерживается клиентом");
            try {
                transport.post(reply, new CancelToken());
            } catch (McpException ignored) {
                // the server will time out its own request
            }
            return;
        }
        if ("notifications/tools/list_changed".equals(method)) {
            Runnable r = toolsChanged;
            if (r != null) r.run();
        }
    }

    @Override public void onChannelLost(String technical) {
        lost = true;
        failPending("Соединение с сервером потеряно", technical);
    }

    private void failPending(String user, String technical) {
        for (Map.Entry<String, CompletableFuture<JSONObject>> e : pending.entrySet()) {
            e.getValue().completeExceptionally(new McpException(McpException.Kind.TRANSPORT, user, technical));
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        failPending("Соединение с сервером закрыто", "client closed");
        transport.close();
    }
}
