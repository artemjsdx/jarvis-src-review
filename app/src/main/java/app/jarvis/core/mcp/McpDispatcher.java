package app.jarvis.core.mcp;

import app.jarvis.core.util.J;
import app.jarvis.core.util.Redactor;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Transport-independent JSON-RPC 2.0 / MCP method handling: initialize, ping, tools/*, resources/*. Handles batches,
 * notifications, argument validation, audio/destructive gating, per-tool timeouts and the audit trail.
 */
public final class McpDispatcher {
    public static final List<String> PROTOCOLS = Arrays.asList("2025-06-18", "2025-03-26", "2024-11-05");
    public static final long MAX_TOOL_TIMEOUT_MS = 130_000;
    public static final int AUDIT_STRING_MAX = 200;

    /** What the HTTP layer sends back. {@code body == null} means "202 Accepted, no content". */
    public static final class Out {
        public final int status;
        public final String body;
        public final String sessionId;
        Out(int status, String body, String sessionId) { this.status = status; this.body = body; this.sessionId = sessionId; }
    }

    private final McpRegistry registry;
    private final McpGate gate;
    private final McpAudit audit;
    private final String serverName, serverVersion, instructions;
    private final SecureRandom rnd = new SecureRandom();
    private final AtomicInteger poolN = new AtomicInteger();
    private final ExecutorService pool = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "jv-mcp-tool-" + poolN.incrementAndGet()); t.setDaemon(true); return t; }
    });

    public McpDispatcher(McpRegistry registry, McpGate gate, McpAudit audit, String serverName, String serverVersion, String instructions) {
        this.registry = registry;
        this.gate = gate == null ? McpGate.DENY_ALL : gate;
        this.audit = audit == null ? McpAudit.NOP : audit;
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.instructions = instructions == null ? "" : instructions;
    }

    public void shutdown() { pool.shutdownNow(); }

    // ---- entry ------------------------------------------------------------------------------------------------------
    public Out handleBody(String body, String sessionHeader) {
        Object parsed;
        try {
            org.json.JSONTokener t = new org.json.JSONTokener(body);
            parsed = t.nextValue();
            if (t.nextClean() != 0) throw new org.json.JSONException("trailing data");
        } catch (Exception e) {
            return new Out(400, error(JSONObject.NULL, -32700, "Parse error").toString(), null);
        }
        if (parsed instanceof JSONObject) {
            Holder h = new Holder();
            JSONObject resp = handleOne((JSONObject) parsed, sessionHeader, h);
            if (resp == null) return new Out(202, null, h.sessionId);
            return new Out(resp.has("error") && isInvalid(resp) ? 400 : 200, resp.toString(), h.sessionId);
        }
        if (parsed instanceof JSONArray) {
            JSONArray in = (JSONArray) parsed;
            if (in.length() == 0) return new Out(400, error(JSONObject.NULL, -32600, "Invalid Request: empty batch").toString(), null);
            JSONArray out = new JSONArray();
            Holder h = new Holder();
            for (int i = 0; i < in.length(); i++) {
                Object o = in.opt(i);
                JSONObject r = o instanceof JSONObject ? handleOne((JSONObject) o, sessionHeader, h) : error(JSONObject.NULL, -32600, "Invalid Request");
                if (r != null) out.put(r);
            }
            if (out.length() == 0) return new Out(202, null, h.sessionId);
            return new Out(200, out.toString(), h.sessionId);
        }
        return new Out(400, error(JSONObject.NULL, -32600, "Invalid Request").toString(), null);
    }

    private static boolean isInvalid(JSONObject resp) {
        JSONObject e = J.obj(resp, "error");
        int c = e == null ? 0 : J.num(e, "code", 0);
        return c == -32600 || c == -32700;
    }

    private static final class Holder { String sessionId; }

    private JSONObject handleOne(JSONObject req, String sessionHeader, Holder h) {
        Object id = req.has("id") ? req.opt("id") : null;
        boolean notification = !req.has("id");
        if (!"2.0".equals(J.str(req, "jsonrpc")) || !(req.opt("method") instanceof String)) {
            if (req.has("result") || req.has("error")) return null; // a response to us (we never send requests): ignore
            return error(id == null ? JSONObject.NULL : id, -32600, "Invalid Request");
        }
        String method = (String) req.opt("method");
        Object p = req.opt("params");
        JSONObject params = p instanceof JSONObject ? (JSONObject) p : new JSONObject();
        try {
            if (method.startsWith("notifications/")) return null;
            JSONObject result;
            switch (method) {
                case "initialize": {
                    String sid = newSessionId();
                    h.sessionId = sid;
                    result = initialize(params);
                    break;
                }
                case "ping": result = new JSONObject(); break;
                case "tools/list": result = J.put(new JSONObject(), "tools", registry.toolsJson()); break;
                case "tools/call": {
                    JSONObject r = callTool(params, sessionHeader);
                    if (r == null) return notification ? null : error(id, -32602, "Unknown tool: " + J.str(params, "name"));
                    result = r;
                    break;
                }
                case "resources/list": result = J.put(new JSONObject(), "resources", resourcesJson()); break;
                case "resources/templates/list": result = J.put(new JSONObject(), "resourceTemplates", new JSONArray()); break;
                case "resources/read": {
                    McpResource r = registry.resource(J.str(params, "uri"));
                    if (r == null) return notification ? null : error(id, -32002, "Resource not found: " + J.str(params, "uri"));
                    String text = r.read();
                    JSONObject c = J.put(J.put(J.put(new JSONObject(), "uri", r.uri()), "mimeType", r.mimeType()), "text", Redactor.redact(text));
                    result = J.put(new JSONObject(), "contents", new JSONArray().put(c));
                    break;
                }
                case "prompts/list": result = J.put(new JSONObject(), "prompts", new JSONArray()); break;
                default:
                    return notification ? null : error(id, -32601, "Method not found: " + method);
            }
            if (notification) return null;
            JSONObject resp = new JSONObject();
            J.put(resp, "jsonrpc", "2.0");
            J.put(resp, "id", id);
            J.put(resp, "result", result);
            return resp;
        } catch (Throwable t) {
            return notification ? null : error(id, -32603, "Internal error: " + t.getClass().getSimpleName() + ": " + Redactor.redact(String.valueOf(t.getMessage())));
        }
    }

    private String newSessionId() {
        byte[] b = new byte[16];
        rnd.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private JSONObject initialize(JSONObject params) {
        String asked = J.str(params, "protocolVersion");
        String ver = PROTOCOLS.contains(asked) ? asked : PROTOCOLS.get(0);
        JSONObject caps = new JSONObject();
        J.put(caps, "tools", J.put(new JSONObject(), "listChanged", false));
        J.put(caps, "resources", J.put(J.put(new JSONObject(), "listChanged", false), "subscribe", false));
        JSONObject info = J.put(J.put(new JSONObject(), "name", serverName), "version", serverVersion);
        JSONObject r = new JSONObject();
        J.put(r, "protocolVersion", ver);
        J.put(r, "capabilities", caps);
        J.put(r, "serverInfo", info);
        if (!instructions.isEmpty()) J.put(r, "instructions", instructions);
        return r;
    }

    private JSONArray resourcesJson() {
        JSONArray a = new JSONArray();
        for (McpResource r : registry.resources()) {
            a.put(J.put(J.put(J.put(J.put(new JSONObject(), "uri", r.uri()), "name", r.name()), "description", r.description()), "mimeType", r.mimeType()));
        }
        return a;
    }

    // ---- tools/call -------------------------------------------------------------------------------------------------
    /** Returns null for an unknown tool (a protocol error), otherwise the CallToolResult. */
    private JSONObject callTool(JSONObject params, String session) {
        final McpTool tool = registry.get(J.str(params, "name"));
        if (tool == null) return null;
        JSONObject a = params.opt("arguments") instanceof JSONObject ? (JSONObject) params.opt("arguments") : new JSONObject();
        final JSONObject args = a;
        long t0 = System.nanoTime();
        McpResult res = runTool(tool, args, session);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        McpAudit.Entry e = new McpAudit.Entry();
        e.tool = tool.name();
        e.session = session == null ? "" : session;
        e.args = maskArgs(args, tool.sensitiveArgs());
        e.durationMs = ms;
        e.flags = tool.flags();
        e.ok = !res.isError;
        if (res.isError) {
            JSONObject body = J.parseOrNull(res.firstText());
            JSONObject err = J.obj(body, "error");
            e.errorCode = J.str(err, "code");
            e.errorMessage = J.str(err, "message");
        }
        try { audit.record(e); } catch (Throwable ignored) { }
        return res.toJson();
    }

    private McpResult runTool(final McpTool tool, final JSONObject args, String session) {
        int f = tool.flags();
        if ((f & McpTool.AUDIO) != 0 && !gate.allowAudio())
            return McpResult.error("FORBIDDEN", "Звук и микрофон для моста выключены: включите настройку mcp.allowAudio.", "setting mcp.allowAudio=false");
        if ((f & McpTool.DESTRUCTIVE) != 0 && !gate.allowDestructive())
            return McpResult.error("FORBIDDEN", "Опасная операция отключена: включите настройку mcp.allowDestructive.", "setting mcp.allowDestructive=false");
        String bad = validate(tool.inputSchema(), args);
        if (bad != null) return McpResult.error("INVALID_ARGS", bad, "schema validation");
        final McpCall call = new McpCall(session);
        Future<McpResult> fut = pool.submit(new java.util.concurrent.Callable<McpResult>() {
            @Override public McpResult call() throws Exception { return tool.call(args, call); }
        });
        long timeout = Math.max(1000, Math.min(MAX_TOOL_TIMEOUT_MS, tool.timeoutMs()));
        try {
            McpResult r = fut.get(timeout, TimeUnit.MILLISECONDS);
            return r == null ? McpResult.error("INTERNAL", "Инструмент не вернул результат.", tool.name()) : r;
        } catch (TimeoutException e) {
            call.cancel();
            fut.cancel(true);
            return McpResult.error("TIMEOUT", "Инструмент «" + tool.name() + "» не уложился в " + (timeout / 1000) + " с.", "timeout " + timeout + " ms");
        } catch (InterruptedException e) {
            call.cancel();
            fut.cancel(true);
            Thread.currentThread().interrupt();
            return McpResult.error("CANCELLED", "Вызов прерван.", "interrupted");
        } catch (ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            return McpResult.error("INTERNAL", "Ошибка инструмента «" + tool.name() + "».", Redactor.redact(c.getClass().getName() + ": " + c.getMessage()));
        }
    }

    /** Checks required properties and primitive types; null when valid. */
    static String validate(JSONObject schema, JSONObject args) {
        JSONArray req = J.arr(schema, "required");
        if (req != null) for (int i = 0; i < req.length(); i++) {
            String k = req.optString(i);
            if (!args.has(k) || args.isNull(k)) return "Не задан обязательный аргумент «" + k + "».";
        }
        JSONObject props = J.obj(schema, "properties");
        if (props == null) return null;
        Iterator<String> it = args.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONObject p = J.obj(props, k);
            if (p == null || args.isNull(k)) continue;
            String type = J.str(p, "type");
            Object v = args.opt(k);
            boolean ok = type.isEmpty()
                || ("string".equals(type) && v instanceof String)
                || ("integer".equals(type) && (v instanceof Integer || v instanceof Long || (v instanceof Number && ((Number) v).doubleValue() == Math.rint(((Number) v).doubleValue()))))
                || ("number".equals(type) && v instanceof Number)
                || ("boolean".equals(type) && v instanceof Boolean)
                || ("array".equals(type) && v instanceof JSONArray)
                || ("object".equals(type) && v instanceof JSONObject);
            if (!ok) return "Аргумент «" + k + "» должен быть типа " + type + ".";
            JSONArray en = J.arr(p, "enum");
            if (en != null && v instanceof String) {
                boolean found = false;
                for (int i = 0; i < en.length(); i++) if (v.equals(en.optString(i))) found = true;
                if (!found) return "Аргумент «" + k + "» должен быть одним из " + en + ".";
            }
        }
        return null;
    }

    /** Masks sensitive values, redacts secrets in strings and truncates long strings to 200 chars. */
    public static JSONObject maskArgs(JSONObject args, java.util.Set<String> sensitive) {
        JSONObject out = new JSONObject();
        Iterator<String> it = args.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = args.opt(k);
            if (sensitive.contains(k)) { J.put(out, k, "***"); continue; }
            J.put(out, k, maskValue(v));
        }
        return out;
    }

    private static Object maskValue(Object v) {
        if (v instanceof String) {
            String s = Redactor.redact((String) v);
            return s.length() > AUDIT_STRING_MAX ? s.substring(0, AUDIT_STRING_MAX) + "…(" + ((String) v).length() + " симв.)" : s;
        }
        if (v instanceof JSONObject) return maskArgs((JSONObject) v, java.util.Collections.<String>emptySet());
        if (v instanceof JSONArray) {
            JSONArray a = new JSONArray();
            JSONArray in = (JSONArray) v;
            for (int i = 0; i < in.length(); i++) a.put(maskValue(in.opt(i)));
            return a;
        }
        return v;
    }

    static JSONObject error(Object id, int code, String message) {
        JSONObject e = J.put(J.put(new JSONObject(), "code", code), "message", message);
        JSONObject r = new JSONObject();
        J.put(r, "jsonrpc", "2.0");
        J.put(r, "id", id == null ? JSONObject.NULL : id);
        J.put(r, "error", e);
        return r;
    }
}
