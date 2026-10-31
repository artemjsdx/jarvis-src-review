package app.jarvis.core.mcp;

import app.jarvis.core.net.NetLab;
import app.jarvis.core.net.NetThrottle;
import app.jarvis.core.util.J;
import org.json.JSONArray;
import org.json.JSONObject;

/** net_probe, net_throttle, net_state: measurements and artificial limits for the app's own traffic (debug only). */
public final class NetTools {
    private NetTools() { }

    static final String[] DEFAULT_HOSTS = {"openrouter.ai", "api.groq.com", "api.elevenlabs.io", "speech.platform.bing.com"};

    public static void register(McpRegistry r) {
        r.register(McpTools.tool("net_probe", "DNS, TCP, TLS, первый байт и общее время до хостов; при uploadUrl — скорость отправки.",
            McpTools.Schema.obj()
                .array("hosts", "Хосты: host, host:port, https://host/path (по умолчанию 4 хоста голосового конвейера)", false)
                .integer("timeoutMs", "Таймаут на шаг, мс (1000..30000)", false)
                .str("uploadUrl", "POST-адрес для замера исходящей скорости (http или https)", false)
                .integer("uploadKb", "Размер отправки, КБ (1..4096, по умолчанию 64)", false), (a, c) -> {
                JSONArray hosts = a.optJSONArray("hosts");
                if (hosts == null || hosts.length() == 0) {
                    hosts = new JSONArray();
                    for (String h : DEFAULT_HOSTS) hosts.put(h);
                }
                int timeout = ToolArgs.clamp(ToolArgs.intArg(a, "timeoutMs", 8000), 1000, 30000);
                JSONObject out = NetLab.probe(hosts, timeout);
                String up = ToolArgs.clip(a.optString("uploadUrl", ""), 300);
                if (!up.isEmpty()) {
                    int kb = ToolArgs.clamp(ToolArgs.intArg(a, "uploadKb", 64), 1, 4096);
                    J.put(out, "upload", NetLab.upload(a.optString("uploadUrl"), kb * 1024, timeout));
                }
                return McpResult.json(out);
            }).timeout(120_000).readOnly());

        r.register(McpTools.tool("net_throttle", "Искусственные ограничения исходящего трафика приложения: задержка, скорость вверх/вниз, потери. "
                + "Действуют на HTTP-ответы, загрузки, SSE и WebSocket; сами истекают; off=true снимает раньше.",
            McpTools.Schema.obj()
                .integer("latencyMs", "Задержка перед каждым ответом, мс (0..60000)", false)
                .integer("kbpsUp", "Скорость отправки, кбит/с (0 = без ограничения)", false)
                .integer("kbpsDown", "Скорость приёма, кбит/с (0 = без ограничения)", false)
                .integer("lossPct", "Потери: соединение рвётся после 100000/lossPct байт (0..100)", false)
                .integer("durationMs", "Сколько действует, мс (1..600000, по умолчанию 60000)", false)
                .bool("off", "Снять ограничения", false), (a, c) -> {
                if (ToolArgs.boolArg(a, "off", false)) {
                    NetThrottle.clear();
                    return McpResult.json(NetThrottle.state());
                }
                try {
                    NetThrottle.set(ToolArgs.longArg(a, "latencyMs", 0), ToolArgs.longArg(a, "kbpsUp", 0), ToolArgs.longArg(a, "kbpsDown", 0),
                        ToolArgs.intArg(a, "lossPct", 0), ToolArgs.longArg(a, "durationMs", 60_000));
                } catch (IllegalArgumentException e) {
                    return McpResult.error("BAD_ARGS", e.getMessage(), String.valueOf(e));
                }
                return McpResult.json(NetThrottle.state());
            }));

        r.register(McpTools.tool("net_state", "Текущие искусственные ограничения сети (active=false, если выключены).", McpTools.Schema.obj(),
            (a, c) -> McpResult.json(NetThrottle.state())).readOnly());
    }
}
