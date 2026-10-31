package app.jarvis.core.mcp;

import app.jarvis.core.trace.TraceStore;
import app.jarvis.core.trace.TurnTrace;
import app.jarvis.core.util.J;
import java.util.List;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Turn traces (P-01): trace_recent, perf_turns, perf_summary, trace_current. Pure Java; the store is supplied by the caller. */
public final class TraceTools {
    private TraceTools() { }

    public static void register(McpRegistry r, final Supplier<TraceStore> store) {
        r.register(McpTools.tool("trace_recent", "Последние трассы ходов: метки времени, интервалы, группы метрик.",
            McpTools.Schema.obj().integer("n", "Сколько трасс (1..100, по умолчанию 5)", false), (a, c) -> McpResult.json(traces(store.get(), ToolArgs.clamp(ToolArgs.intArg(a, "n", 5), 1, 100)))).readOnly());

        r.register(McpTools.tool("perf_turns", "Тайминги последних ходов агента (то же, что trace_recent).",
            McpTools.Schema.obj().integer("n", "Сколько ходов (1..100, по умолчанию 5)", false), (a, c) -> McpResult.json(traces(store.get(), ToolArgs.clamp(ToolArgs.intArg(a, "n", 5), 1, 100)))).readOnly());

        r.register(McpTools.tool("perf_summary", "Медианы и p90 интервалов последних завершённых ходов, отдельно голос и текст: to_sound, stt, llm_ttft, llm_total, tts_ttfa.",
            McpTools.Schema.obj().integer("n", "По скольким последним ходам (1..100, по умолчанию 20)", false), (a, c) -> {
                List<JSONObject> l = store.get().recent(ToolArgs.clamp(ToolArgs.intArg(a, "n", 20), 1, 100));
                return McpResult.json(PerfSummary.summarize(l));
            }).readOnly());

        r.register(McpTools.tool("trace_current", "Незавершённый ход (если идёт сейчас), иначе current=null.", McpTools.Schema.obj(), (a, c) -> {
            TurnTrace cur = store.get().current();
            JSONObject o = new JSONObject();
            J.put(o, "current", cur == null ? JSONObject.NULL : cur.toJson());
            return McpResult.json(o);
        }).readOnly());
    }

    public static JSONObject traces(TraceStore s, int n) {
        List<JSONObject> l = s.recent(n);
        JSONArray arr = new JSONArray();
        for (JSONObject t : l) arr.put(t);
        return J.put(J.put(new JSONObject(), "count", arr.length()), "traces", arr);
    }
}
