package app.jarvis.mcp;

import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.LlmException;
import app.jarvis.core.llm.LlmListener;
import app.jarvis.core.llm.LlmRequest;
import app.jarvis.core.llm.LlmResult;
import app.jarvis.core.llm.ModelInfo;
import app.jarvis.core.llm.Presets;
import app.jarvis.core.llm.ProviderPreset;
import app.jarvis.core.mcp.McpRegistry;
import app.jarvis.core.mcp.McpResult;
import app.jarvis.core.mcp.McpTools;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.J;
import app.jarvis.data.Settings;
import app.jarvis.rt.Jarvis;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** llm_config, llm_models, llm_ping, llm_set. Keys are never returned, only whether one is stored. */
final class LlmTools {
    private LlmTools() { }

    static void register(McpRegistry r, final McpBridge b) {
        final Jarvis j = b.j;

        r.register(McpTools.tool("llm_config", "Выбранный провайдер и модель, запасные провайдеры, у каких провайдеров есть ключ.", (a, c) -> {
            JSONObject o = new JSONObject();
            J.put(o, "provider", j.providerId());
            J.put(o, "model", j.modelFor(j.providerId()));
            J.put(o, "fallbacks", j.settings.get(Settings.LLM_FALLBACKS, "openrouter"));
            J.put(o, "reasoning", j.settings.get(Settings.LLM_REASONING, "low"));
            J.put(o, "maxSteps", j.settings.getInt(Settings.LLM_MAX_STEPS, 10));
            J.put(o, "contextChars", j.settings.getInt(Settings.LLM_CONTEXT_CHARS, 60000));
            J.put(o, "proxySet", !j.settings.get(Settings.LLM_PROXY).isEmpty());
            JSONArray ps = new JSONArray();
            for (ProviderPreset p : Presets.all()) {
                JSONObject po = new JSONObject();
                J.put(po, "id", p.id);
                J.put(po, "name", p.name);
                J.put(po, "baseUrl", p.baseUrl);
                J.put(po, "hasKey", j.secrets.has("key." + p.id) || !p.keyRequired);
                J.put(po, "model", j.modelFor(p.id));
                ps.put(po);
            }
            J.put(o, "providers", ps);
            JSONArray chain = new JSONArray();
            for (Jarvis.LlmChoice ch : j.llmChoices()) chain.put(ch.label());
            J.put(o, "chain", chain);
            return McpResult.json(o);
        }).readOnly());

        r.register(McpTools.tool("llm_models", "Список моделей провайдера (запрос к его API). filter — подстрока в id.",
            McpTools.Schema.obj().str("provider", "Id провайдера (по умолчанию выбранный)", false).str("filter", "Подстрока в id модели", false).integer("limit", "Максимум (по умолчанию 100)", false), (a, c) -> {
                Jarvis.LlmChoice ch = j.choiceFor(J.str(a, "provider", j.providerId()), null);
                if (ch == null) return Tl.err("NOT_FOUND", "Неизвестный провайдер.", J.str(a, "provider"));
                if (!ch.hasKey) return Tl.err("NO_KEY", "У провайдера «" + ch.preset.name + "» нет ключа (secrets_set key." + ch.preset.id + ").", "no key");
                try {
                    List<ModelInfo> ms = j.llm.listModels(ch.config, new CancelToken());
                    String f = J.str(a, "filter").toLowerCase(Locale.ROOT);
                    int limit = Tl.clamp(Tl.intArg(a, "limit", 100), 1, 1000);
                    JSONArray arr = new JSONArray();
                    for (ModelInfo m : ms) {
                        if (!f.isEmpty() && !m.id.toLowerCase(Locale.ROOT).contains(f)) continue;
                        if (arr.length() >= limit) break;
                        arr.put(J.put(J.put(J.put(J.put(new JSONObject(), "id", m.id), "name", m.name), "context", m.contextLength), "chat", m.chatLikely));
                    }
                    return McpResult.json(J.put(J.put(J.put(new JSONObject(), "provider", ch.preset.id), "total", ms.size()), "models", arr));
                } catch (LlmException e) {
                    return Tl.err("LLM_" + e.kind.name(), e.userMessage, e.technical);
                }
            }).timeout(60_000));

        r.register(McpTools.tool("llm_ping", "Живой запрос к модели («ответь одним словом: ок»): задержка до заголовков, первого токена и итог.",
            McpTools.Schema.obj().str("provider", "Id провайдера (по умолчанию выбранный)", false).str("model", "Модель (по умолчанию выбранная для провайдера)", false), (a, c) -> {
                Jarvis.LlmChoice ch = j.choiceFor(J.str(a, "provider", j.providerId()), J.str(a, "model"));
                if (ch == null) return Tl.err("NOT_FOUND", "Неизвестный провайдер.", J.str(a, "provider"));
                if (!ch.hasKey) return Tl.err("NO_KEY", "У провайдера «" + ch.preset.name + "» нет ключа (secrets_set key." + ch.preset.id + ").", "no key");
                if (ch.model.isEmpty()) return Tl.err("NO_MODEL", "Не выбрана модель.", "model empty");
                LlmRequest req = new LlmRequest();
                req.model = ch.model;
                req.messages.add(ChatMessage.user("Ответь одним словом: ок"));
                req.maxTokens = 32;
                req.reasoning = "off";
                req.stream = true;
                long t0 = System.currentTimeMillis();
                try {
                    LlmResult res = j.llm.chat(ch.config, req, LlmListener.NOP, new CancelToken());
                    JSONObject o = new JSONObject();
                    J.put(o, "ok", true);
                    J.put(o, "provider", ch.preset.id);
                    J.put(o, "model", res.model.isEmpty() ? ch.model : res.model);
                    J.put(o, "reply", Tl.clip(res.content, 200));
                    J.put(o, "headersMs", res.headersMs);
                    J.put(o, "firstTokenMs", res.firstDeltaMs);
                    J.put(o, "totalMs", res.totalMs >= 0 ? res.totalMs : System.currentTimeMillis() - t0);
                    J.put(o, "promptTokens", res.promptTokens);
                    J.put(o, "completionTokens", res.completionTokens);
                    J.put(o, "attempts", res.attempts);
                    return McpResult.json(o);
                } catch (LlmException e) {
                    return Tl.err("LLM_" + e.kind.name(), e.userMessage, e.technical);
                }
            }).timeout(90_000));

        r.register(McpTools.tool("llm_set", "Выбрать провайдера и/или модель.", McpTools.Schema.obj().str("provider", "Id провайдера", false).str("model", "Модель", false), (a, c) -> {
            String p = J.str(a, "provider");
            if (!p.isEmpty() && Presets.get(p) == null) return Tl.err("NOT_FOUND", "Неизвестный провайдер «" + p + "».", p);
            String target = p.isEmpty() ? j.providerId() : p;
            String m = J.str(a, "model");
            String warnings = "";
            if (!m.isEmpty()) {
                app.jarvis.core.agent.ContextFit.Result fit = j.checkSwitch(target, m);
                if (fit.refused()) return Tl.err("NOT_TEXT_MODEL", "Модель «" + m + "» работает по голосовому или realtime-протоколу: для текстового чата не подходит.", m);
                JSONArray w = new JSONArray();
                for (String line : app.jarvis.core.agent.ContextFit.describe(fit, m)) w.put(line);
                j.selectModel(target, m);
                JSONObject o = new JSONObject();
                J.put(o, "provider", j.providerId());
                J.put(o, "model", j.modelFor(j.providerId()));
                J.put(o, "warnings", w);
                return McpResult.json(o);
            }
            if (!p.isEmpty()) j.settings.set(Settings.LLM_PROVIDER, p);
            JSONObject o = new JSONObject();
            J.put(o, "provider", j.providerId());
            J.put(o, "model", j.modelFor(j.providerId()));
            return McpResult.json(o);
        }));
    }
}
