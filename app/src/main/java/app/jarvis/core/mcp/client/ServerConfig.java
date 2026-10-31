package app.jarvis.core.mcp.client;

import app.jarvis.core.util.J;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One external MCP server as the user configured it. Headers (bearer tokens) are NOT here: they live in Secrets.
 * The tool list is a cache of the last successful tools/list, so direct tools exist before any connection is made.
 */
public final class ServerConfig {
    public enum Transport { AUTO, HTTP, SSE }
    public enum Exposure { AUTO, DIRECT, LAZY }

    /** More tools than this and the auto exposure switches to the two lazy meta-tools. */
    public static final int LAZY_THRESHOLD = 12;
    public static final long MIN_TIMEOUT_MS = 1_000, MAX_TIMEOUT_MS = 600_000, DEFAULT_TIMEOUT_MS = 60_000;

    public String id = "";
    public String name = "";
    public String url = "";
    public Transport transport = Transport.AUTO;
    public boolean enabled = true;
    public Exposure exposure = Exposure.AUTO;
    public String toolPrefix = "";
    public final List<String> allow = new ArrayList<>();
    public final List<String> deny = new ArrayList<>();
    public long timeoutMs = DEFAULT_TIMEOUT_MS;
    /** Ask the user before tools that are not marked readOnlyHint run. */
    public boolean confirmWrites = true;
    public final List<RemoteTool> tools = new ArrayList<>();
    public long toolsAt;

    public ServerConfig copy() {
        ServerConfig c = new ServerConfig();
        c.id = id; c.name = name; c.url = url; c.transport = transport; c.enabled = enabled; c.exposure = exposure;
        c.toolPrefix = toolPrefix; c.allow.addAll(allow); c.deny.addAll(deny); c.timeoutMs = timeoutMs;
        c.confirmWrites = confirmWrites; c.tools.addAll(tools); c.toolsAt = toolsAt;
        return c;
    }

    public Exposure effectiveExposure() {
        if (exposure != Exposure.AUTO) return exposure;
        return tools.size() > LAZY_THRESHOLD ? Exposure.LAZY : Exposure.DIRECT;
    }

    public String prefix() { return toolPrefix.isEmpty() ? McpSchemas.defaultPrefix(id) : toolPrefix; }

    public boolean allowed(String tool) {
        if (deny.contains(tool)) return false;
        return allow.isEmpty() || allow.contains(tool);
    }

    public RemoteTool tool(String name) {
        for (RemoteTool t : tools) if (t.name.equals(name)) return t;
        return null;
    }

    /** Empty string when the URL is acceptable; otherwise a user-safe Russian reason. */
    public static String validateUrl(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (u.isEmpty()) return "Укажите адрес сервера";
        String lower = u.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return "Адрес должен начинаться с http:// или https://";
        if (u.length() < 12 || u.indexOf(' ') >= 0) return "Адрес выглядит неверно";
        return "";
    }

    /** Stable id from a display name: lowercase latin, digits, dashes; at most 32 characters. */
    public static String slug(String name) {
        String s = (name == null ? "" : name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.isEmpty()) s = "server";
        return s.length() > 32 ? s.substring(0, 32).replaceAll("-+$", "") : s;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        J.put(o, "id", id);
        J.put(o, "name", name);
        J.put(o, "url", url);
        J.put(o, "transport", transport.name().toLowerCase(Locale.ROOT));
        J.put(o, "enabled", enabled);
        J.put(o, "exposure", exposure.name().toLowerCase(Locale.ROOT));
        J.put(o, "toolPrefix", toolPrefix);
        J.put(o, "allow", new JSONArray(allow));
        J.put(o, "deny", new JSONArray(deny));
        J.put(o, "timeoutMs", timeoutMs);
        J.put(o, "confirmWrites", confirmWrites);
        JSONArray ts = new JSONArray();
        for (RemoteTool t : tools) ts.put(t.toJson());
        J.put(o, "tools", ts);
        J.put(o, "toolsAt", toolsAt);
        return o;
    }

    /** Null when the entry is unusable (no id or URL). Unknown enum values fall back to defaults. */
    public static ServerConfig fromJson(JSONObject o) {
        if (o == null) return null;
        ServerConfig c = new ServerConfig();
        c.id = McpWire.str(o, "id", "");
        c.name = McpWire.str(o, "name", c.id);
        c.url = McpWire.str(o, "url", "");
        if (c.id.isEmpty() || !validateUrl(c.url).isEmpty()) return null;
        c.transport = parseEnum(Transport.class, McpWire.str(o, "transport", ""), Transport.AUTO);
        c.enabled = o.optBoolean("enabled", true);
        c.exposure = parseEnum(Exposure.class, McpWire.str(o, "exposure", ""), Exposure.AUTO);
        c.toolPrefix = McpWire.str(o, "toolPrefix", "");
        readStrings(o.optJSONArray("allow"), c.allow);
        readStrings(o.optJSONArray("deny"), c.deny);
        c.timeoutMs = Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, o.optLong("timeoutMs", DEFAULT_TIMEOUT_MS)));
        c.confirmWrites = o.optBoolean("confirmWrites", true);
        JSONArray ts = o.optJSONArray("tools");
        if (ts != null) for (int i = 0; i < ts.length(); i++) {
            RemoteTool t = RemoteTool.fromJson(ts.optJSONObject(i) == null ? new JSONObject() : ts.optJSONObject(i));
            if (t != null) c.tools.add(t);
        }
        c.toolsAt = o.optLong("toolsAt", 0);
        return c;
    }

    private static void readStrings(JSONArray a, List<String> out) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "");
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String v, E def) {
        for (E e : type.getEnumConstants()) if (e.name().equalsIgnoreCase(v)) return e;
        return def;
    }
}
