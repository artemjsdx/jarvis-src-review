package app.jarvis.mcp;

import app.jarvis.core.log.Journal;
import app.jarvis.core.mcp.McpRegistry;
import app.jarvis.core.mcp.McpResource;
import app.jarvis.core.mcp.McpResult;
import app.jarvis.core.mcp.McpTools;
import app.jarvis.core.util.J;
import app.jarvis.data.SqlJournal;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** journal_tail, journal_mark, journal_clear and the jarvis://journal/tail resource. */
final class JournalTools {
    private JournalTools() { }

    static void register(McpRegistry r, final McpBridge b) {
        final SqlJournal jr = b.j.journal;

        r.register(McpTools.tool("journal_tail", "Последние записи журнала (старые сверху). Фильтры: level (DEBUG|INFO|WARN|ERROR, минимум), cat, since (мс epoch), afterId, text.",
            McpTools.Schema.obj().integer("n", "Сколько записей (1..500, по умолчанию 50)", false).oneOf("level", "Минимальный уровень", false, "DEBUG", "INFO", "WARN", "ERROR")
                .str("cat", "Категория: system, agent, llm, tools, mcp, tts, stt, ...", false).integer("since", "Только записи не раньше этого времени (мс epoch)", false)
                .integer("afterId", "Только записи с id больше этого", false).str("text", "Подстрока в сообщении или деталях", false),
            (a, c) -> McpResult.json(tail(jr, a))).readOnly());

        r.register(McpTools.tool("journal_mark", "Поставить метку в журнал (начало теста) и вернуть её id и время; потом journal_tail(afterId=id).",
            McpTools.Schema.obj().str("label", "Название метки", false), (a, c) -> {
                String label = J.str(a, "label", "mark");
                long t0 = System.currentTimeMillis();
                String msg = "MARK " + label + " #" + Long.toString(t0, 36);
                jr.log("mcp", Journal.Level.INFO, msg, null, null, -1);
                long id = 0;
                for (int i = 0; i < 20 && id == 0; i++) {
                    Thread.sleep(80);
                    List<SqlJournal.Row> rows = jr.query("mcp", null, msg, 0, 1);
                    if (!rows.isEmpty()) id = rows.get(0).id;
                }
                return Tl.ok(J.put(J.put(J.put(new JSONObject(), "id", id), "ts", t0), "label", label));
            }));

        r.register(McpTools.tool("journal_clear", "Очистить журнал целиком (нужен mcp.allowDestructive).", (a, c) -> {
            long n = jr.count();
            jr.clear();
            return Tl.ok(J.put(new JSONObject(), "deleted", n));
        }).destructive());

        r.registerResource(new McpResource() {
            @Override public String uri() { return "jarvis://journal/tail"; }
            @Override public String name() { return "journal tail"; }
            @Override public String description() { return "Последние 100 записей журнала"; }
            @Override public String mimeType() { return "application/json"; }
            @Override public String read() throws Exception { return tail(jr, J.put(new JSONObject(), "n", 100)).toString(); }
        });
    }

    static JSONObject tail(SqlJournal jr, JSONObject a) throws InterruptedException {
        Thread.sleep(100);    // the journal writer batches for ~60 ms: let fresh rows land
        int n = Tl.clamp(Tl.intArg(a, "n", 50), 1, 500);
        long since = Tl.longArg(a, "since", 0), afterId = Tl.longArg(a, "afterId", 0);
        Journal.Level min = null;
        String lv = J.str(a, "level");
        if (!lv.isEmpty()) {
            try { min = Journal.Level.valueOf(lv.toUpperCase(java.util.Locale.ROOT)); } catch (IllegalArgumentException ignored) { }
        }
        int fetch = since > 0 || afterId > 0 ? 500 : n;
        List<SqlJournal.Row> rows = jr.query(J.str(a, "cat"), min, J.str(a, "text"), 0, fetch);
        JSONArray out = new JSONArray();
        for (int i = rows.size() - 1; i >= 0; i--) {
            SqlJournal.Row x = rows.get(i);
            if (x.ts < since || x.id <= afterId) continue;
            JSONObject o = new JSONObject();
            J.put(o, "id", x.id);
            J.put(o, "ts", x.ts);
            J.put(o, "time", x.time());
            J.put(o, "level", x.level.name());
            J.put(o, "cat", x.category);
            J.put(o, "msg", x.message);
            if (x.runId != null && !x.runId.isEmpty()) J.put(o, "runId", x.runId);
            if (x.durationMs >= 0) J.put(o, "ms", x.durationMs);
            if (!x.details.isEmpty()) { JSONObject d = J.parseOrNull(x.details); J.put(o, "details", d != null ? d : Tl.clip(x.details, 1500)); }
            out.put(o);
        }
        if (out.length() > n) {
            JSONArray cut = new JSONArray();
            for (int i = out.length() - n; i < out.length(); i++) cut.put(out.opt(i));
            out = cut;
        }
        return J.put(J.put(new JSONObject(), "count", out.length()), "rows", out);
    }
}
