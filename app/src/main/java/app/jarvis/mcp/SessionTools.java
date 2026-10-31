package app.jarvis.mcp;

import app.jarvis.core.mcp.McpRegistry;
import app.jarvis.core.mcp.McpResult;
import app.jarvis.core.mcp.McpTools;
import app.jarvis.core.util.J;
import app.jarvis.data.ChatStore;
import app.jarvis.rt.Jarvis;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** sessions_*: the user's chat sessions through rt/Sessions (the same calls the side drawer makes). */
final class SessionTools {
    private SessionTools() { }

    static void register(McpRegistry r, final McpBridge b) {
        final Jarvis j = b.j;
        r.register(McpTools.tool("sessions_list", "Сессии чата: название, превью, число сообщений, закреплена, архив, активна.",
            McpTools.Schema.obj().str("query", "Подстрока для поиска по названию и тексту", false).bool("includeArchived", "Вместе с архивными", false)
                .bool("includeHidden", "Вместе со служебными тестовыми (mcp-…)", false), (a, c) -> {
                List<ChatStore.Conv> l = j.sessions.list(J.str(a, "query"), Tl.boolArg(a, "includeHidden", false), Tl.boolArg(a, "includeArchived", false));
                JSONArray arr = new JSONArray();
                for (ChatStore.Conv cv : l) arr.put(conv(j, cv));
                return McpResult.json(J.put(J.put(new JSONObject(), "active", j.sessions.active()), "sessions", arr));
            }).readOnly());

        r.register(McpTools.tool("sessions_search", "Поиск сессий по названию и тексту сообщений.",
            McpTools.Schema.obj().str("query", "Что искать", true).bool("includeArchived", "Вместе с архивными", false), (a, c) -> {
                List<ChatStore.Conv> l = j.sessions.list(J.str(a, "query"), false, Tl.boolArg(a, "includeArchived", false));
                JSONArray arr = new JSONArray();
                for (ChatStore.Conv cv : l) arr.put(conv(j, cv));
                return McpResult.json(J.put(new JSONObject(), "sessions", arr));
            }).readOnly());

        r.register(McpTools.tool("sessions_create", "Создать сессию и сделать её активной.",
            McpTools.Schema.obj().str("title", "Название (по умолчанию «Новый чат»)", false), (a, c) -> {
                long id = j.sessions.create(J.str(a, "title"));
                return McpResult.json(J.put(new JSONObject(), "id", id));
            }));

        r.register(McpTools.tool("sessions_select", "Сделать сессию активной.",
            McpTools.Schema.obj().integer("id", "Номер сессии", true), (a, c) -> {
                long id = a.optLong("id", -1);
                if (!j.sessions.select(id)) return Tl.err("NOT_FOUND", "Сессия не найдена.", "id " + id);
                return McpResult.json(J.put(new JSONObject(), "active", id));
            }));

        r.register(McpTools.tool("sessions_rename", "Переименовать сессию.",
            McpTools.Schema.obj().integer("id", "Номер сессии", true).str("title", "Новое название", true), (a, c) -> {
                long id = a.optLong("id", -1);
                if (!j.sessions.rename(id, J.str(a, "title"))) return Tl.err("BAD_ARGS", "Нет такой сессии или пустое название.", "id " + id);
                return McpResult.json(J.put(new JSONObject(), "id", id));
            }));

        r.register(McpTools.tool("sessions_pin", "Закрепить или открепить сессию.",
            McpTools.Schema.obj().integer("id", "Номер сессии", true).bool("on", "true — закрепить (по умолчанию)", false), (a, c) -> {
                long id = a.optLong("id", -1);
                if (!exists(j, id)) return Tl.err("NOT_FOUND", "Сессия не найдена.", "id " + id);
                j.sessions.pin(id, Tl.boolArg(a, "on", true));
                return McpResult.json(conv(j, j.sessions.info(id)));
            }));

        r.register(McpTools.tool("sessions_archive", "Архивировать или вернуть из архива.",
            McpTools.Schema.obj().integer("id", "Номер сессии", true).bool("on", "true — в архив (по умолчанию)", false), (a, c) -> {
                long id = a.optLong("id", -1);
                if (!exists(j, id)) return Tl.err("NOT_FOUND", "Сессия не найдена.", "id " + id);
                j.sessions.archive(id, Tl.boolArg(a, "on", true));
                return McpResult.json(conv(j, j.sessions.info(id)));
            }));

        r.register(McpTools.tool("sessions_delete", "Удалить сессию вместе с сообщениями (необратимо).",
            McpTools.Schema.obj().integer("id", "Номер сессии", true), (a, c) -> {
                long id = a.optLong("id", -1);
                if (!exists(j, id)) return Tl.err("NOT_FOUND", "Сессия не найдена.", "id " + id);
                j.sessions.delete(id);
                return McpResult.json(J.put(new JSONObject(), "deleted", id));
            }).destructive());

        r.register(McpTools.tool("sessions_clear_context", "Очистить контекст модели в сессии: сообщения остаются в чате, модель их не видит.",
            McpTools.Schema.obj().integer("id", "Номер сессии", true), (a, c) -> {
                long id = a.optLong("id", -1);
                if (!exists(j, id)) return Tl.err("NOT_FOUND", "Сессия не найдена.", "id " + id);
                return McpResult.json(J.put(J.put(new JSONObject(), "id", id), "dividerSeq", j.sessions.clearContext(id)));
            }));
    }

    private static boolean exists(Jarvis j, long id) { return id > 0 && j.sessions.info(id) != null; }

    static JSONObject conv(Jarvis j, ChatStore.Conv cv) {
        JSONObject o = new JSONObject();
        J.put(o, "id", cv.id);
        J.put(o, "title", cv.title);
        J.put(o, "preview", Tl.clip(cv.preview, 160));
        J.put(o, "items", cv.items);
        J.put(o, "pinned", cv.pinned);
        J.put(o, "archived", cv.archived);
        J.put(o, "hidden", cv.hidden);
        J.put(o, "updated", cv.updated);
        J.put(o, "active", cv.id == j.sessions.active());
        return o;
    }
}
