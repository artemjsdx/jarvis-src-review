package app.jarvis.mcp;

import app.jarvis.core.mcp.McpRegistry;
import app.jarvis.core.mcp.McpResource;
import app.jarvis.core.mcp.McpResult;
import app.jarvis.core.mcp.McpTools;
import app.jarvis.core.util.J;
import app.jarvis.core.util.Redactor;
import app.jarvis.data.Settings;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** settings_* and secrets_* tools plus the jarvis://settings resource. */
final class SettingsTools {
    private SettingsTools() { }

    /** Keys the bridge may not change: otherwise an agent could lift its own safety gates. They are switched in the settings page only. */
    static boolean protectedKey(String key) {
        return key.equals(Settings.MCP_ALLOW_AUDIO) || key.equals(Settings.MCP_ALLOW_DESTRUCTIVE) || key.equals(Settings.MCP_ENABLED);
    }

    static void register(McpRegistry r, final McpBridge b) {
        final Settings st = b.j.settings;
        final app.jarvis.data.Secrets sec = b.j.secrets;

        r.register(McpTools.tool("settings_list", "Все настройки (или по префиксу ключа).", McpTools.Schema.obj().str("prefix", "Префикс ключа, например llm.", false),
            (a, c) -> McpResult.json(list(st, J.str(a, "prefix")))).readOnly());

        r.register(McpTools.tool("settings_get", "Значение одной настройки.", McpTools.Schema.obj().str("key", "Ключ, например llm.provider", true), (a, c) -> {
            String k = J.str(a, "key");
            JSONObject o = new JSONObject();
            J.put(o, "key", k);
            J.put(o, "set", st.has(k));
            J.put(o, "value", Redactor.redact(st.get(k)));
            return McpResult.json(o);
        }).readOnly());

        r.register(McpTools.tool("settings_set", "Записать настройку (флаги безопасности моста меняются только в приложении).",
            McpTools.Schema.obj().str("key", "Ключ", true).str("value", "Значение строкой", true), (a, c) -> {
                String k = J.str(a, "key");
                if (k.isEmpty() || !k.matches("[A-Za-z0-9_.\\-]{1,80}")) return Tl.err("INVALID_ARGS", "Недопустимый ключ настройки.", k);
                if (protectedKey(k)) return Tl.err("FORBIDDEN", "Этот флаг безопасности меняется только вручную: Настройки → Мост для агента.", "protected key " + k);
                String old = st.get(k);
                st.set(k, J.str(a, "value"));
                JSONObject o = new JSONObject();
                J.put(o, "key", k);
                J.put(o, "old", Redactor.redact(old));
                J.put(o, "value", Redactor.redact(st.get(k)));
                return McpResult.json(o);
            }));

        r.register(McpTools.tool("settings_reset", "Удалить настройку (вернуть умолчание). Нужен mcp.allowDestructive.", McpTools.Schema.obj().str("key", "Ключ", true), (a, c) -> {
            String k = J.str(a, "key");
            if (protectedKey(k)) return Tl.err("FORBIDDEN", "Этот флаг безопасности меняется только вручную.", "protected key " + k);
            boolean had = st.has(k);
            st.remove(k);
            return McpResult.json(J.put(J.put(new JSONObject(), "key", k), "removed", had));
        }).destructive());

        r.register(McpTools.tool("secrets_list", "Имена ключей, последние 4 символа и длина; значения не показываются.", (a, c) -> {
            JSONArray arr = new JSONArray();
            for (String n : sec.names()) {
                String v = sec.get(n);
                JSONObject o = new JSONObject();
                J.put(o, "name", n);
                J.put(o, "last4", v.length() >= 12 ? v.substring(v.length() - 4) : "");
                J.put(o, "length", v.length());
                arr.put(o);
            }
            return McpResult.json(J.put(J.put(new JSONObject(), "count", arr.length()), "secrets", arr));
        }).readOnly());

        r.register(McpTools.tool("secrets_set", "Сохранить ключ (например key.openrouter). Значение не попадает в журнал.",
            McpTools.Schema.obj().str("name", "Имя, например key.openrouter", true).str("value", "Значение", true), (a, c) -> {
                String n = J.str(a, "name");
                if (!n.matches("[A-Za-z0-9_.\\-]{1,80}")) return Tl.err("INVALID_ARGS", "Недопустимое имя ключа.", n);
                sec.set(n, J.str(a, "value"));
                return McpResult.json(J.put(J.put(new JSONObject(), "name", n), "stored", sec.has(n)));
            }).sensitive("value"));

        r.register(McpTools.tool("secrets_get", "Прочитать значение ключа (нужен mcp.allowDestructive).", McpTools.Schema.obj().str("name", "Имя", true), (a, c) -> {
            String n = J.str(a, "name");
            JSONObject o = new JSONObject();
            J.put(o, "name", n);
            J.put(o, "found", sec.has(n));
            J.put(o, "value", sec.get(n));
            return McpResult.json(o);
        }).destructive());

        r.registerResource(new McpResource() {
            @Override public String uri() { return "jarvis://settings"; }
            @Override public String name() { return "settings"; }
            @Override public String description() { return "Все настройки"; }
            @Override public String mimeType() { return "application/json"; }
            @Override public String read() { return list(st, "").toString(); }
        });
    }

    static JSONObject list(Settings st, String prefix) {
        JSONObject vals = new JSONObject();
        for (Map.Entry<String, String> e : st.snapshot().entrySet()) {
            if (!prefix.isEmpty() && !e.getKey().startsWith(prefix)) continue;
            J.put(vals, e.getKey(), Redactor.redact(e.getValue()));
        }
        return J.put(J.put(new JSONObject(), "count", vals.length()), "settings", vals);
    }
}
