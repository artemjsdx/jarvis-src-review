package app.jarvis.mcp;

import app.jarvis.core.mcp.McpResult;
import app.jarvis.core.mcp.McpTool;
import app.jarvis.core.mcp.McpTools;
import app.jarvis.core.util.J;
import org.json.JSONArray;
import org.json.JSONObject;

/** Small helpers shared by the tool groups. */
final class Tl {
    private Tl() { }

    static McpResult ok(JSONObject o) { return McpResult.json(o); }

    static McpResult err(String code, String ru, String technical) { return McpResult.error(code, ru, technical); }

    static McpResult notImplemented(String tool, String what) {
        return McpResult.error("NOT_IMPLEMENTED", "Инструмент «" + tool + "» ещё не реализован: " + what, what);
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    static int intArg(JSONObject a, String k, int def) { return a.has(k) && !a.isNull(k) ? a.optInt(k, def) : def; }

    static long longArg(JSONObject a, String k, long def) { return a.has(k) && !a.isNull(k) ? a.optLong(k, def) : def; }

    static boolean boolArg(JSONObject a, String k, boolean def) { return a.has(k) && !a.isNull(k) ? a.optBoolean(k, def) : def; }

    /** Registers a tool that only answers NOT_IMPLEMENTED; the owning feature replaces it with {@code registry.put}. */
    static McpTools.Def stub(String name, String description, McpTools.Schema schema, final String waitsFor) {
        final String n = name;
        return McpTools.tool(name, description + " (пока не реализован)", schema, (a, c) -> notImplemented(n, waitsFor));
    }

    static JSONArray toArray(Iterable<String> it) { JSONArray a = new JSONArray(); for (String s : it) a.put(s); return a; }

    static String clip(String s, int max) { return s == null ? "" : s.length() <= max ? s : s.substring(0, max) + "…[+" + (s.length() - max) + " симв.]"; }

    static JSONObject o() { return new JSONObject(); }

    static JSONObject o(String k, Object v) { return J.put(new JSONObject(), k, v); }
}
