package app.jarvis.mcp;

import app.jarvis.core.agent.Prompts;
import app.jarvis.core.agent.Tool;
import app.jarvis.core.agent.ToolContext;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.ToolCall;
import app.jarvis.core.llm.ToolSpec;
import app.jarvis.core.mcp.McpRegistry;
import app.jarvis.core.mcp.McpResult;
import app.jarvis.core.mcp.McpTools;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.J;
import app.jarvis.rt.Jarvis;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** agent_tools_list, agent_tool_call (direct tool run without the model), agent_prompt_dump, perf_prompt_size. */
final class AgentTools {
    private AgentTools() { }

    /** Agent tools that make sound or open the microphone: they need mcp.allowAudio when called through the bridge. */
    private static final Set<String> AUDIO_TOOLS = new HashSet<>(Arrays.asList(
        "music_play", "music_play_latest_received", "music_resume", "music_next", "music_prev", "app_listen", "app_wake", "tts"));

    static void register(McpRegistry r, final McpBridge b) {
        final Jarvis j = b.j;

        r.register(McpTools.tool("agent_tools_list", "Все инструменты агента: имя, описание, группа (core — показывается всегда, остальные только по запросу), категория, побочные эффекты, схема аргументов.",
            McpTools.Schema.obj().bool("schemas", "Включить схемы аргументов (по умолчанию да)", false), (a, c) -> {
                boolean schemas = Tl.boolArg(a, "schemas", true);
                JSONArray arr = new JSONArray();
                for (ToolSpec s : j.tools.specs()) {
                    Tool t = j.tools.get(s.name);
                    JSONObject o = new JSONObject();
                    J.put(o, "name", s.name);
                    J.put(o, "description", s.description);
                    if (t != null) { J.put(o, "category", t.category()); J.put(o, "sideEffects", t.sideEffects()); J.put(o, "timeoutMs", t.timeoutMs()); }
                    String grp = app.jarvis.core.agent.ToolGroups.groupOf(s.name);
                    J.put(o, "group", grp == null ? "core" : grp);
                    J.put(o, "audio", AUDIO_TOOLS.contains(s.name));
                    if (schemas) J.put(o, "parameters", s.parameters);
                    arr.put(o);
                }
                return McpResult.json(J.put(J.put(new JSONObject(), "count", arr.length()), "tools", arr));
            }).readOnly());

        r.register(McpTools.tool("agent_tool_call", "Вызвать инструмент агента напрямую, без модели. Результат тот же, что получила бы модель. Звуковые инструменты требуют mcp.allowAudio.",
            McpTools.Schema.obj().str("name", "Имя инструмента из agent_tools_list", true).object("args", "Аргументы инструмента", false), (a, c) -> {
                String name = J.str(a, "name");
                Tool t = j.tools.get(name);
                if (t == null) return Tl.err("NOT_FOUND", "Нет инструмента агента «" + name + "».", "known: " + j.tools.names());
                if (AUDIO_TOOLS.contains(name) && !b.gate().allowAudio())
                    return Tl.err("FORBIDDEN", "Инструмент «" + name + "» издаёт звук или слушает микрофон: включите mcp.allowAudio.", "setting mcp.allowAudio=false");
                JSONObject args = a.optJSONObject("args");
                String runId = "mcp" + Long.toString(System.currentTimeMillis(), 36);
                CancelToken cancel = new CancelToken();
                long t0 = System.currentTimeMillis();
                ToolResult res = j.tools.execute(new ToolCall("mcp-" + runId, name, args == null ? "{}" : args.toString()), runId, cancel, j.journal, null);
                JSONObject o = new JSONObject();
                J.put(o, "ok", res.ok);
                J.put(o, "summary", res.summary);
                if (!res.ok) J.put(o, "error", res.error);
                J.put(o, "data", res.data == null ? new JSONObject() : res.data);
                J.put(o, "timedOut", res.timedOut);
                J.put(o, "cancelled", res.cancelled);
                J.put(o, "ms", System.currentTimeMillis() - t0);
                return McpResult.json(o);
            }).timeout(120_000));

        r.register(McpTools.tool("agent_prompt_dump", "Что реально уйдёт модели в этой сессии: системный промпт, история, число инструментов, размеры.",
            McpTools.Schema.obj().str("session", "Id или название сессии (по умолчанию автосессия)", false).bool("full", "Вернуть тексты целиком (по умолчанию обрезка)", false), (a, c) -> {
                JSONObject o = promptInfo(b, J.str(a, "session"), c, Tl.boolArg(a, "full", false));
                return o == null ? Tl.err("NOT_FOUND", "Сессия не найдена.", J.str(a, "session")) : McpResult.json(o);
            }).readOnly());

        r.register(McpTools.tool("perf_prompt_size", "Размер запроса к модели в символах по частям (система, история, инструменты).",
            McpTools.Schema.obj().str("session", "Id или название сессии", false), (a, c) -> {
                JSONObject o = promptInfo(b, J.str(a, "session"), c, false);
                if (o == null) return Tl.err("NOT_FOUND", "Сессия не найдена.", J.str(a, "session"));
                JSONObject sizes = J.obj(o, "chars");
                return McpResult.json(sizes);
            }).readOnly());
    }

    private static JSONObject promptInfo(McpBridge b, String sessionArg, app.jarvis.core.mcp.McpCall call, boolean full) {
        Jarvis j = b.j;
        long conv;
        String title = "";
        if (sessionArg.isEmpty() && call.session.isEmpty()) conv = j.chat.currentConversation();
        else {
            ChatTools.Sess s = ChatTools.resolve(j, sessionArg, call, false);
            if (s == null) { if (!sessionArg.isEmpty()) return null; conv = j.chat.currentConversation(); } else { conv = s.id; title = s.title; }
        }
        int ctxChars = j.contextChars(j.providerId(), j.modelFor(j.providerId()));
        String system = Prompts.system(j.agent.currentPromptInfo());
        List<ChatMessage> hist = j.chat.history(conv, ctxChars - 4000);
        int histChars = 0;
        JSONArray msgs = new JSONArray();
        for (ChatMessage m : hist) {
            int len = m.content == null ? 0 : m.content.length();
            histChars += len;
            JSONObject mo = new JSONObject();
            J.put(mo, "role", m.role);
            if (m.name != null && !m.name.isEmpty()) J.put(mo, "name", m.name);
            J.put(mo, "text", full ? m.content : Tl.clip(m.content, 600));
            if (!m.toolCalls.isEmpty()) J.put(mo, "toolCalls", m.toolCalls.size());
            msgs.put(mo);
        }
        int toolChars = 0;
        List<ToolSpec> specs = j.agent.nextRequestTools(conv);
        for (ToolSpec s : specs) toolChars += s.toJson(false).toString().length();
        JSONObject o = new JSONObject();
        J.put(o, "session", String.valueOf(conv));
        J.put(o, "title", title);
        J.put(o, "provider", j.providerId());
        J.put(o, "model", j.modelFor(j.providerId()));
        J.put(o, "system", full ? system : Tl.clip(system, 1500));
        J.put(o, "history", msgs);
        J.put(o, "toolCount", specs.size());
        J.put(o, "toolCountRegistered", j.tools.names().size());
        JSONObject chars = new JSONObject();
        J.put(chars, "system", system.length());
        J.put(chars, "history", histChars);
        J.put(chars, "historyMessages", hist.size());
        J.put(chars, "tools", toolChars);
        J.put(chars, "total", system.length() + histChars + toolChars);
        J.put(chars, "contextBudget", ctxChars);
        J.put(o, "chars", chars);
        J.put(o, "report", j.agent.promptReport(conv).toJson());
        return o;
    }
}
