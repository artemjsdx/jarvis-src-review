package app.jarvis.core.util;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * org.json differs between Android and desktop: Android's optString() returns the text "null" for a JSON
 * null while desktop returns the default. These helpers behave identically on both.
 */
public final class J {
    private J() { }

    public static String str(JSONObject o, String key) { return str(o, key, ""); }

    public static String str(JSONObject o, String key, String def) {
        if (o == null) return def;
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return def;
        return v instanceof String ? (String) v : String.valueOf(v);
    }

    public static int num(JSONObject o, String key, int def) {
        if (o == null) return def;
        Object v = o.opt(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) { try { return Integer.parseInt(((String) v).trim()); } catch (NumberFormatException e) { return def; } }
        return def;
    }

    public static long lng(JSONObject o, String key, long def) {
        if (o == null) return def;
        Object v = o.opt(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) { try { return Long.parseLong(((String) v).trim()); } catch (NumberFormatException e) { return def; } }
        return def;
    }

    public static boolean bool(JSONObject o, String key, boolean def) {
        if (o == null) return def;
        Object v = o.opt(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return "true".equalsIgnoreCase((String) v);
        return def;
    }

    public static JSONObject obj(JSONObject o, String key) {
        Object v = o == null ? null : o.opt(key);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    public static JSONArray arr(JSONObject o, String key) {
        Object v = o == null ? null : o.opt(key);
        return v instanceof JSONArray ? (JSONArray) v : null;
    }

    public static JSONObject parseOrNull(String s) {
        try { return new JSONObject(s); } catch (Exception e) { return null; }
    }

    public static JSONObject put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (Exception ignored) { }
        return o;
    }
}
