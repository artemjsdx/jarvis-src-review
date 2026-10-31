package app.jarvis.core.mcp;

import org.json.JSONObject;

/** Argument readers for tools: a missing or null field gives the default. */
public final class ToolArgs {
    private ToolArgs() { }

    public static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    public static int intArg(JSONObject a, String k, int def) { return a.has(k) && !a.isNull(k) ? a.optInt(k, def) : def; }

    public static long longArg(JSONObject a, String k, long def) { return a.has(k) && !a.isNull(k) ? a.optLong(k, def) : def; }

    public static boolean boolArg(JSONObject a, String k, boolean def) { return a.has(k) && !a.isNull(k) ? a.optBoolean(k, def) : def; }

    public static String clip(String s, int max) { return s == null ? "" : s.length() <= max ? s : s.substring(0, max) + "…[+" + (s.length() - max) + " симв.]"; }
}
