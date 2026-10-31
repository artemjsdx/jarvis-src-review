package app.jarvis.core.mcp.client;

import app.jarvis.core.util.J;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** JSON-RPC 2.0 framing helpers shared by the client and its transports. */
final class McpWire {
    private McpWire() { }

    /** Largest HTTP body read in one go (a page of tools/list or a whole tool result). */
    static final int MAX_BODY = 4 * 1024 * 1024;
    /** Tool results above this many bytes are cut and marked. */
    static final int MAX_RESULT_BYTES = 256 * 1024;

    static JSONObject parseObject(String text) {
        try {
            Object o = new org.json.JSONTokener(text).nextValue();
            return o instanceof JSONObject ? (JSONObject) o : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** A single object or a batch array; malformed input yields an empty list (the event is skipped, never fatal). */
    static List<JSONObject> parseMessages(String text) {
        List<JSONObject> out = new ArrayList<>();
        Object o;
        try {
            o = new org.json.JSONTokener(text).nextValue();
        } catch (Exception e) {
            return out;
        }
        if (o instanceof JSONObject) out.add((JSONObject) o);
        else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) if (a.opt(i) instanceof JSONObject) out.add(a.optJSONObject(i));
        }
        return out;
    }

    static boolean isResponse(JSONObject m) {
        return m.has("id") && !m.has("method") && (m.has("result") || m.has("error"));
    }

    static String idKey(Object id) { return String.valueOf(id); }

    static JSONObject request(long id, String method, JSONObject params) {
        JSONObject o = new JSONObject();
        J.put(o, "jsonrpc", "2.0");
        J.put(o, "id", id);
        J.put(o, "method", method);
        if (params != null) J.put(o, "params", params);
        return o;
    }

    static JSONObject notification(String method, JSONObject params) {
        JSONObject o = new JSONObject();
        J.put(o, "jsonrpc", "2.0");
        J.put(o, "method", method);
        if (params != null) J.put(o, "params", params);
        return o;
    }

    static JSONObject response(Object id, JSONObject result) {
        JSONObject o = new JSONObject();
        J.put(o, "jsonrpc", "2.0");
        J.put(o, "id", id);
        J.put(o, "result", result == null ? new JSONObject() : result);
        return o;
    }

    static JSONObject errorResponse(Object id, int code, String message) {
        JSONObject e = new JSONObject();
        J.put(e, "code", code);
        J.put(e, "message", message);
        JSONObject o = new JSONObject();
        J.put(o, "jsonrpc", "2.0");
        J.put(o, "id", id);
        J.put(o, "error", e);
        return o;
    }

    /** Null-safe string read: absent and JSON null both give the default. */
    static String str(JSONObject o, String key, String def) {
        if (o == null || !o.has(key) || o.isNull(key)) return def;
        Object v = o.opt(key);
        return v == null ? def : String.valueOf(v);
    }

    static String readBody(InputStream in, int max) throws McpException {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (bo.size() + n > max) throw new McpException(McpException.Kind.TOO_LARGE, "Ответ сервера слишком большой", "body > " + max);
                bo.write(buf, 0, n);
            }
        } catch (IOException e) {
            throw new McpException(McpException.Kind.TRANSPORT, "Соединение с сервером прервалось", e.toString(), 0, e);
        }
        try {
            return new String(bo.toByteArray(), "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
