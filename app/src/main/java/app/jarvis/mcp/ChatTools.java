package app.jarvis.mcp;

import android.database.Cursor;
import app.jarvis.core.mcp.McpCall;
import app.jarvis.core.mcp.McpRegistry;
import app.jarvis.core.mcp.McpResult;
import app.jarvis.core.mcp.McpTools;
import app.jarvis.core.util.J;
import app.jarvis.data.ChatItem;
import app.jarvis.data.ChatStore;
import app.jarvis.rt.ChatEvent;
import app.jarvis.rt.Jarvis;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * chat_* tools over ChatStore and AgentRunner. Without a "session" argument a tool works in the automatic test session
 * "mcp-&lt;transport session&gt;", which the UI hides. Session ids are conversation ids as strings; titles are accepted too.
 * When the runtime gets a richer Sessions API (sess1) only {@link #clearContext} and {@link #createSession} need rewiring.
 */
final class ChatTools {
    private ChatTools() { }

    static final class Sess {
        final long id;
        final String title;
        Sess(long id, String title) { this.id = id; this.title = title; }
    }

    static void register(McpRegistry r, final McpBridge b) {
        final Jarvis j = b.j;

        r.register(McpTools.tool("chat_sessions", "Список сессий чата (id, название, число сообщений, признак тестовой).", McpTools.Schema.obj().bool("includeTest", "Показывать тестовые mcp-* (по умолчанию да)", false),
            (a, c) -> {
                boolean test = Tl.boolArg(a, "includeTest", true);
                JSONArray arr = new JSONArray();
                for (ChatStore.Conv cv : j.chat.conversations()) {
                    boolean isTest = cv.title != null && cv.title.startsWith(ChatStore.TEST_PREFIX);
                    if (isTest && !test) continue;
                    arr.put(describe(cv, isTest));
                }
                return McpResult.json(J.put(J.put(new JSONObject(), "count", arr.length()), "sessions", arr));
            }).readOnly());

        r.register(McpTools.tool("chat_new", "Создать пустую сессию (контекст без истории). По умолчанию тестовая: название получает префикс mcp- и скрыто в меню.",
            McpTools.Schema.obj().str("title", "Название", false).bool("visible", "Создать обычную видимую сессию (по умолчанию нет)", false), (a, c) -> {
                String title = J.str(a, "title", "тест");
                if (!Tl.boolArg(a, "visible", false) && !title.startsWith(ChatStore.TEST_PREFIX)) title = ChatStore.TEST_PREFIX + title;
                Sess s = createSession(j, title);
                return McpResult.json(J.put(J.put(new JSONObject(), "session", String.valueOf(s.id)), "title", s.title));
            }));

        r.register(McpTools.tool("chat_send", "Отправить сообщение агенту в сессии и (wait=true) дождаться ответа: текст, вызовы инструментов, тайминги. "
            + "Новая команда отменяет текущий ход. source=voice требует mcp.allowAudio (иначе ответ не озвучивается).",
            McpTools.Schema.obj().str("text", "Текст сообщения", true).str("session", "Id или название сессии (по умолчанию автосессия моста)", false)
                .bool("wait", "Ждать завершения хода (по умолчанию да)", false).integer("timeoutMs", "Сколько ждать, мс (до 120000)", false)
                .oneOf("source", "Источник сообщения", false, "text", "voice"),
            (a, c) -> send(b, a, c)).timeout(125_000));

        r.register(McpTools.tool("chat_history", "Последние сообщения сессии: пользователь, ассистент, вызовы инструментов, системные метки.",
            McpTools.Schema.obj().str("session", "Id или название сессии", false).integer("n", "Сколько последних элементов (1..200, по умолчанию 30)", false), (a, c) -> {
                Sess s = resolve(j, J.str(a, "session"), c, false);
                if (s == null) return noSession(J.str(a, "session"));
                List<ChatItem> items = j.chat.items(s.id, 0, Tl.clamp(Tl.intArg(a, "n", 30), 1, 200));
                JSONArray arr = new JSONArray();
                for (ChatItem it : items) arr.put(item(it, 4000));
                return McpResult.json(J.put(J.put(J.put(new JSONObject(), "session", String.valueOf(s.id)), "title", s.title), "items", arr));
            }).readOnly());

        r.register(McpTools.tool("chat_clear_context", "Очистить контекст сессии: история остаётся видна, но модели больше не отправляется.",
            McpTools.Schema.obj().str("session", "Id или название сессии", false), (a, c) -> {
                Sess s = resolve(j, J.str(a, "session"), c, true);
                if (s == null) return noSession(J.str(a, "session"));
                clearContext(j, s.id);
                return McpResult.json(J.put(J.put(new JSONObject(), "session", String.valueOf(s.id)), "cleared", true));
            }));

        r.register(McpTools.tool("chat_cancel", "Отменить текущий ход агента.", (a, c) -> {
            boolean was = j.agent.isRunning();
            j.cancelAll();
            return McpResult.json(J.put(new JSONObject(), "wasRunning", was));
        }));

        r.register(McpTools.tool("chat_wait_idle", "Дождаться, пока агент закончит текущий ход.", McpTools.Schema.obj().integer("timeoutMs", "Сколько ждать, мс (до 120000)", false), (a, c) -> {
            long t0 = System.currentTimeMillis();
            boolean idle = waitIdle(j, c, Tl.clamp(Tl.intArg(a, "timeoutMs", 30000), 100, 120000));
            return McpResult.json(J.put(J.put(new JSONObject(), "idle", idle), "waitedMs", System.currentTimeMillis() - t0));
        }).timeout(125_000).readOnly());

        r.register(McpTools.tool("chat_state", "Состояние ассистента: занят ли агент, что показывает орб, активные сессии.", (a, c) -> {
            JSONObject o = new JSONObject();
            J.put(o, "agentRunning", j.agent.isRunning());
            J.put(o, "state", j.state().name());
            J.put(o, "detail", j.stateDetail());
            J.put(o, "provider", j.providerId());
            J.put(o, "model", j.modelFor(j.providerId()));
            J.put(o, "uiSession", String.valueOf(j.chat.currentConversation()));
            return McpResult.json(o);
        }).readOnly());

        r.register(McpTools.tool("chat_delete", "Удалить сессию. Тестовые mcp-* удаляются свободно, остальные только при mcp.allowDestructive.",
            McpTools.Schema.obj().str("session", "Id или название сессии", true), (a, c) -> {
                Sess s = resolve(j, J.str(a, "session"), c, false);
                if (s == null) return noSession(J.str(a, "session"));
                if (!s.title.startsWith(ChatStore.TEST_PREFIX) && !b.gate().allowDestructive())
                    return Tl.err("FORBIDDEN", "Обычные сессии удаляются только при mcp.allowDestructive; тестовые mcp-* — свободно.", "session " + s.id + " is not a test session");
                j.chat.deleteConversation(s.id);
                j.agent.silentConvs.remove(s.id);
                return McpResult.json(J.put(J.put(new JSONObject(), "deleted", true), "session", String.valueOf(s.id)));
            }));

        r.register(McpTools.tool("chat_seed", "Добавить в сессию готовые элементы чата без модели (нужен mcp.allowDestructive): для скриншотов и проверки вёрстки. "
            + "kind: user|assistant|tool|error|system; для tool нужны tool, args, ok, ms, summary, result.",
            McpTools.Schema.obj().str("session", "Id или название сессии (по умолчанию автосессия моста)", false)
                .array("items", "Элементы по порядку, до 60", true),
            (a, c) -> seed(j, a, c)).destructive());
    }

    // ---- seeding ----------------------------------------------------------------------------------------------------
    private static McpResult seed(Jarvis j, JSONObject a, McpCall call) {
        JSONArray items = a.optJSONArray("items");
        if (items == null || items.length() == 0) return Tl.err("INVALID_ARGS", "Нужен непустой массив items.", "items");
        if (items.length() > 60) return Tl.err("INVALID_ARGS", "За один вызов не больше 60 элементов.", "items " + items.length());
        Sess s = resolve(j, J.str(a, "session"), call, true);
        if (s == null) return noSession(J.str(a, "session"));
        List<ChatItem> built = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            try {
                built.add(seedItem(items.optJSONObject(i), s.id));
            } catch (IllegalArgumentException e) {
                return Tl.err("INVALID_ARGS", "Элемент " + i + ": " + e.getMessage(), "items[" + i + "]");
            }
        }
        for (ChatItem it : built) {
            j.chat.add(it);
            j.fireChat(ChatEvent.Type.ADDED, it, null, "");
        }
        return McpResult.json(J.put(J.put(new JSONObject(), "session", String.valueOf(s.id)), "added", built.size()));
    }

    /** One chat row from a seed description; the kinds and fields are the ones the UI draws. */
    static ChatItem seedItem(JSONObject o, long convId) {
        if (o == null) throw new IllegalArgumentException("не объект");
        ChatItem it = new ChatItem();
        it.convId = convId;
        String kind = J.str(o, "kind").trim().toLowerCase(java.util.Locale.ROOT);
        switch (kind) {
            case "user":
                it.kind = ChatItem.Kind.USER;
                it.text = J.str(o, "text");
                it.source = J.str(o, "source", "text");
                break;
            case "assistant":
                it.kind = ChatItem.Kind.ASSISTANT;
                it.text = J.str(o, "text");
                it.model = J.str(o, "model", "seed");
                break;
            case "tool":
                it.kind = ChatItem.Kind.TOOL;
                it.toolName = J.str(o, "tool");
                if (it.toolName.isEmpty()) throw new IllegalArgumentException("для tool нужно поле tool");
                it.callId = "seed-" + java.util.UUID.randomUUID();
                it.toolArgs = jsonText(o, "args", "{}");
                it.ok = o.optBoolean("ok", true);
                it.toolStatus = it.ok ? ChatItem.ToolStatus.DONE : ChatItem.ToolStatus.FAILED;
                it.durationMs = o.optLong("ms", 120);
                it.summary = J.str(o, "summary", it.toolName);
                it.text = jsonText(o, "result", it.ok ? "{\"ok\":true}" : "{\"ok\":false}");
                break;
            case "error":
                it.kind = ChatItem.Kind.ERROR;
                it.text = J.str(o, "text");
                break;
            case "system":
                it.kind = ChatItem.Kind.SYSTEM;
                it.text = J.str(o, "text");
                break;
            default:
                throw new IllegalArgumentException("неизвестный kind «" + kind + "»");
        }
        if (it.kind != ChatItem.Kind.TOOL && it.text.isEmpty()) throw new IllegalArgumentException("пустой text");
        return it;
    }

    private static String jsonText(JSONObject o, String key, String def) {
        Object v = o.opt(key);
        if (v == null) return def;
        return v instanceof String ? (String) v : v.toString();
    }

    // ---- sessions -------------------------------------------------------------------------------------------------
    static Sess createSession(Jarvis j, String title) {
        long id = j.chat.newConversation(title);
        return new Sess(id, title);
    }

    static void clearContext(Jarvis j, long convId) {
        ChatItem m = new ChatItem();
        m.convId = convId;
        m.kind = ChatItem.Kind.SYSTEM;
        m.text = ChatStore.CONTEXT_CLEARED;
        j.chat.add(m);
        j.fireChat(ChatEvent.Type.ADDED, m, null, "");
    }

    /** arg: "" = automatic session of this transport session (created when {@code create}); digits = conversation id; otherwise exact title. */
    static Sess resolve(Jarvis j, String arg, McpCall call, boolean create) {
        arg = arg == null ? "" : arg.trim();
        List<ChatStore.Conv> all = j.chat.conversations();
        if (arg.isEmpty()) {
            String sid = call == null || call.session.isEmpty() ? "default" : call.session;
            String title = ChatStore.TEST_PREFIX + sid.substring(0, Math.min(8, sid.length()));
            for (ChatStore.Conv cv : all) if (title.equals(cv.title)) return new Sess(cv.id, cv.title);
            return create ? createSession(j, title) : null;
        }
        if (arg.matches("\\d{1,18}")) {
            long id = Long.parseLong(arg);
            for (ChatStore.Conv cv : all) if (cv.id == id) return new Sess(cv.id, cv.title);
            return null;
        }
        for (ChatStore.Conv cv : all) if (arg.equals(cv.title)) return new Sess(cv.id, cv.title);
        return null;
    }

    private static McpResult noSession(String arg) {
        return Tl.err("NOT_FOUND", arg.isEmpty() ? "Автосессия моста ещё не создана: сначала chat_send или chat_new." : "Сессия «" + arg + "» не найдена (chat_sessions покажет список).", "session " + arg);
    }

    private static JSONObject describe(ChatStore.Conv cv, boolean isTest) {
        JSONObject o = new JSONObject();
        J.put(o, "session", String.valueOf(cv.id));
        J.put(o, "title", cv.title);
        J.put(o, "items", cv.items);
        J.put(o, "created", cv.created);
        J.put(o, "updated", cv.updated);
        J.put(o, "test", isTest);
        return o;
    }

    // ---- send / wait ----------------------------------------------------------------------------------------------
    static boolean waitIdle(Jarvis j, McpCall call, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (j.agent.isRunning()) {
            if (System.currentTimeMillis() > end) return false;
            if (call != null && call.isCancelled()) return false;
            Thread.sleep(40);
        }
        return true;
    }

    private static long lastSeq(Jarvis j, long conv) {
        List<ChatItem> last = j.chat.items(conv, 0, 1);
        return last.isEmpty() ? 0 : last.get(0).seq;
    }

    private static McpResult send(McpBridge b, JSONObject a, McpCall call) throws Exception {
        Jarvis j = b.j;
        String text = J.str(a, "text").trim();
        if (text.isEmpty()) return Tl.err("INVALID_ARGS", "Пустое сообщение.", "text");
        String source = J.str(a, "source", "text");
        boolean audio = b.gate().allowAudio();
        if ("voice".equals(source) && !audio)
            return Tl.err("FORBIDDEN", "Источник voice озвучивает ответ: включите mcp.allowAudio или отправляйте как text.", "setting mcp.allowAudio=false");
        Sess s = resolve(j, J.str(a, "session"), call, true);
        if (s == null) return noSession(J.str(a, "session"));
        if (!audio) j.agent.silentConvs.add(s.id); else j.agent.silentConvs.remove(s.id);
        long before = lastSeq(j, s.id);
        long t0 = System.currentTimeMillis();
        j.agent.submit(text, null, source, s.id);
        JSONObject o = new JSONObject();
        J.put(o, "session", String.valueOf(s.id));
        if (!Tl.boolArg(a, "wait", true)) { J.put(o, "started", true); return McpResult.json(o); }
        long timeout = Tl.clamp(Tl.intArg(a, "timeoutMs", 90_000), 1000, 120_000);
        if (!waitIdle(j, call, timeout))
            return Tl.err("TIMEOUT", "Агент не закончил за " + timeout / 1000 + " с; ход продолжается: chat_wait_idle или chat_cancel.", "session " + s.id);
        JSONArray events = new JSONArray(), tools = new JSONArray();
        String reply = "";
        List<ChatItem> items = new ArrayList<>(j.chat.items(s.id, 0, 200));
        for (ChatItem it : items) {
            if (it.seq <= before) continue;
            events.put(item(it, 3000));
            if (it.kind == ChatItem.Kind.ASSISTANT && !it.text.isEmpty()) reply = it.text;
            if (it.kind == ChatItem.Kind.TOOL) tools.put(J.put(J.put(J.put(J.put(J.put(new JSONObject(), "name", it.toolName), "args", Tl.clip(it.toolArgs, 500)), "ok", it.ok), "summary", Tl.clip(it.summary, 300)), "ms", it.durationMs));
            if (it.kind == ChatItem.Kind.ERROR) J.put(o, "error", it.text);
        }
        J.put(o, "reply", reply);
        J.put(o, "toolCalls", tools);
        J.put(o, "events", events);
        JSONObject timings = new JSONObject();
        J.put(timings, "totalMs", System.currentTimeMillis() - t0);
        try (Cursor cur = j.db.sql.rawQuery("SELECT id,status,model,provider,steps,tool_calls,first_text_ms,total_ms,error FROM run WHERE conv_id=? AND started>=? ORDER BY started DESC LIMIT 1",
            new String[]{String.valueOf(s.id), String.valueOf(t0 - 1000)})) {
            if (cur.moveToFirst()) {
                JSONObject run = new JSONObject();
                J.put(run, "id", cur.getString(0));
                J.put(run, "status", cur.getString(1));
                J.put(run, "model", cur.getString(2));
                J.put(run, "provider", cur.getString(3));
                J.put(run, "steps", cur.getInt(4));
                J.put(run, "toolCalls", cur.getInt(5));
                J.put(run, "error", cur.getString(8) == null ? "" : cur.getString(8));
                J.put(o, "run", run);
                J.put(timings, "firstTextMs", cur.getLong(6));
                J.put(timings, "runMs", cur.getLong(7));
            }
        }
        J.put(o, "timings", timings);
        return McpResult.json(o);
    }

    static JSONObject item(ChatItem it, int clip) {
        JSONObject o = new JSONObject();
        J.put(o, "seq", it.seq);
        J.put(o, "kind", it.kind.name().toLowerCase(java.util.Locale.ROOT));
        J.put(o, "ts", it.ts);
        if (it.kind == ChatItem.Kind.TOOL) {
            J.put(o, "tool", it.toolName);
            J.put(o, "args", Tl.clip(it.toolArgs, 800));
            J.put(o, "ok", it.ok);
            J.put(o, "status", it.toolStatus.name());
            J.put(o, "summary", Tl.clip(it.summary, 400));
            J.put(o, "ms", it.durationMs);
            J.put(o, "result", Tl.clip(it.text, clip));
        } else {
            J.put(o, "text", Tl.clip(it.text, clip));
            if (it.kind == ChatItem.Kind.USER) J.put(o, "source", it.source);
            if (it.kind == ChatItem.Kind.ASSISTANT && !it.model.isEmpty()) J.put(o, "model", it.model);
            if (it.kind == ChatItem.Kind.ASSISTANT && !it.toolCallsJson.isEmpty()) J.put(o, "toolCalls", Tl.clip(it.toolCallsJson, 800));
        }
        if (!it.runId.isEmpty()) J.put(o, "runId", it.runId);
        return o;
    }
}
