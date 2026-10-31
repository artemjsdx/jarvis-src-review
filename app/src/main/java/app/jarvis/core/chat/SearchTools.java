package app.jarvis.core.chat;

import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ToolSpec;
import app.jarvis.core.llm.ToolSpec.Param;
import app.jarvis.core.util.J;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** session_search: read-only search over the user's past chats. */
public final class SearchTools {
    public interface Searcher { List<SearchHit> search(String query, int limit, int days); }

    private static final int DEFAULT_LIMIT = 8, MAX_LIMIT = 20;

    private SearchTools() { }

    public static void register(ToolRegistry r, final Searcher searcher) {
        r.add(ToolSpec.of("session_search",
                "Поиск по всем прошлым чатам: текст пользователя и ассистента (не вывод инструментов). Возвращает чат, время и фрагмент. "
                    + "Если пользователь ссылается на прошлый разговор («мы говорили», «помнишь», «в прошлый раз») — сначала session_search.",
                Param.str("query", "Слова, которые должны встретиться в сообщении. Последнее слово можно неполным: «сбер» найдёт «Сбербанк».", true),
                Param.integer("limit", "Сколько результатов, 1–20. По умолчанию 8.", false),
                Param.integer("days", "Искать только за последние N дней. 0 или не задано — без ограничения.", false)),
            "chat", 10_000, false, (a, ctx) -> {
                String query = J.str(a, "query").trim();
                if (SearchQuery.terms(query).isEmpty()) return ToolResult.fail("Укажите слова для поиска в query.");
                int limit = Math.min(MAX_LIMIT, Math.max(1, (int) J.lng(a, "limit", DEFAULT_LIMIT)));
                int days = (int) Math.max(0, J.lng(a, "days", 0));
                List<SearchHit> hits = searcher.search(query, limit, days);
                JSONArray arr = new JSONArray();
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);
                for (SearchHit h : hits) {
                    arr.put(J.put(J.put(J.put(J.put(J.put(J.put(new JSONObject(),
                        "chat_id", h.convId), "chat", h.title), "seq", h.seq), "when", fmt.format(new java.util.Date(h.ts))),
                        "role", h.role), "snippet", h.snippet));
                }
                JSONObject data = J.put(J.put(new JSONObject(), "query", query), "hits", arr);
                return ToolResult.ok(data, hits.isEmpty() ? "ничего не найдено" : "найдено: " + hits.size());
            });
    }
}
