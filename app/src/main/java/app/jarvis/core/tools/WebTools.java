package app.jarvis.core.tools;

import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ToolSpec;
import app.jarvis.core.llm.ToolSpec.Param;
import app.jarvis.core.util.J;
import app.jarvis.core.web.DocsIndex;
import app.jarvis.core.web.SearchBackend;
import app.jarvis.core.web.SearchHit;
import app.jarvis.core.web.SearchRouter;
import app.jarvis.core.web.WebConfig;
import app.jarvis.core.web.WebException;
import app.jarvis.core.web.WebFetcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONObject;

/** Agent tools for the web: parallel search, page reading and search over downloaded documentation. */
public final class WebTools {
    private WebTools() { }

    private static final int MAX_QUERIES = 6;
    private static final String[] RECENCY = {"", "day", "week", "month", "year"};

    /**
     * @param settings lookup of settings values by key ("" when missing)
     * @param secrets  lookup of secret values by name ("" when missing)
     * @param proxyRef single-slot holder of the tool proxy spec
     */
    public static void register(ToolRegistry r, Sandbox sb, Function<String, String> settings, Function<String, String> secrets, String[] proxyRef) {
        final SearchRouter router = new SearchRouter(System::currentTimeMillis);
        final DocsIndex docs = new DocsIndex(sb);
        r.add(searchSpec(), "web", SearchRouter.OVERALL_TIMEOUT_MS + 5_000, false, (a, ctx) -> {
            List<String> queries = queries(a);
            if (queries.isEmpty()) return ToolResult.fail("Укажите от 1 до " + MAX_QUERIES + " поисковых запросов в поле queries");
            if (queries.size() > MAX_QUERIES) return ToolResult.fail("За один вызов не больше " + MAX_QUERIES + " запросов");
            String recency = J.str(a, "recency").trim().toLowerCase(Locale.ROOT);
            if (!contains(RECENCY, recency)) return ToolResult.fail("recency: допустимо day, week, month, year или не задавать");
            WebConfig cfg = WebConfig.from(settings, secrets, proxyRef[0]);
            int limit = Math.max(1, Math.min(10, J.num(a, "limit", cfg.maxResults)));
            List<SearchBackend> chain;
            try {
                chain = cfg.chain(System::currentTimeMillis);
            } catch (WebException e) {
                return ToolResult.fail(e.userMessage);
            }
            SearchRouter.Report rep = router.search(chain, queries, limit, recency, siteOf(J.str(a, "site")), ctx.cancel);
            JSONObject o = new JSONObject();
            J.put(o, "ok", rep.anyOk());
            J.put(o, "backend", String.join(",", rep.backends));
            J.put(o, "ms", rep.ms);
            JSONArray results = new JSONArray();
            int total = 0;
            for (SearchRouter.QueryOutcome q : rep.results) {
                JSONObject qo = new JSONObject();
                J.put(qo, "query", q.query);
                if (!q.ok()) J.put(qo, "error", q.error);
                JSONArray hits = new JSONArray();
                for (SearchHit h : q.hits) { hits.put(h.toJson()); total++; }
                J.put(qo, "hits", hits);
                results.put(qo);
            }
            J.put(o, "results", results);
            if (!rep.anyOk()) {
                String why = rep.results.isEmpty() ? "нет результатов" : rep.results.get(0).error;
                return ToolResult.fail("Поиск не дал результатов: " + why, o);
            }
            return ToolResult.ok(o, total + " результатов по " + queries.size() + " запросам");
        });
        r.add(fetchSpec(), "web", 60_000, false, (a, ctx) -> {
            String url = J.str(a, "url").trim();
            WebConfig cfg = WebConfig.from(settings, secrets, proxyRef[0]);
            String format = J.str(a, "format", "markdown").trim().toLowerCase(Locale.ROOT);
            if (!contains(new String[]{"markdown", "text", "raw"}, format)) return ToolResult.fail("format: допустимо markdown, text или raw");
            int maxChars = Math.max(1000, Math.min(200000, J.num(a, "max_chars", cfg.fetchMaxChars)));
            int offset = Math.max(0, J.num(a, "offset", 0));
            WebFetcher.Page p;
            try {
                p = new WebFetcher(cfg.proxy).fetch(url, format, maxChars, offset, ctx.cancel);
            } catch (WebException e) {
                return ToolResult.fail(e.userMessage);
            }
            JSONObject o = new JSONObject();
            J.put(o, "ok", p.ok);
            J.put(o, "url", p.url);
            J.put(o, "finalUrl", p.finalUrl);
            J.put(o, "status", p.status);
            if (!p.title.isEmpty()) J.put(o, "title", p.title);
            if (!p.contentType.isEmpty()) J.put(o, "contentType", p.contentType);
            J.put(o, "format", p.format);
            if (p.binary) J.put(o, "binary", true);
            if (!p.hint.isEmpty()) J.put(o, "hint", p.hint);
            if (!p.ok) {
                J.put(o, "error", p.error);
                return ToolResult.fail(p.error, o);
            }
            J.put(o, "text", p.text);
            J.put(o, "truncated", p.truncated);
            J.put(o, "totalChars", p.totalChars);
            J.put(o, "nextOffset", p.nextOffset < 0 ? JSONObject.NULL : (Object) p.nextOffset);
            JSONArray links = new JSONArray();
            for (String[] l : p.links) {
                JSONObject lo = new JSONObject();
                J.put(lo, "text", l[0]);
                J.put(lo, "url", l[1]);
                links.put(lo);
            }
            J.put(o, "links", links);
            return ToolResult.ok(o, (p.title.isEmpty() ? p.finalUrl : p.title) + " (" + p.totalChars + " симв.)");
        });
        r.add(ToolSpec.of("docs_search",
                "Поиск по скачанной документации: файлы в downloads/ и docs/ (.md, .txt, .html, .py, .java, .json и др. до 512 КБ). Возвращает пути и фрагменты.",
                Param.str("query", "Что искать (слова; регистр не важен).", true),
                Param.integer("limit", "Сколько файлов вернуть (1–10, по умолчанию 5).", false)),
            "web", 20_000, false, (a, ctx) -> {
                String query = J.str(a, "query").trim();
                if (query.isEmpty()) return ToolResult.fail("Пустой запрос");
                int limit = Math.max(1, Math.min(10, J.num(a, "limit", 5)));
                DocsIndex.Result res;
                try {
                    res = docs.search(query, limit);
                } catch (java.io.IOException e) {
                    return ToolResult.fail("Не удалось прочитать документы: " + e.getMessage());
                }
                JSONArray hits = new JSONArray();
                for (DocsIndex.Hit h : res.hits) {
                    JSONObject ho = new JSONObject();
                    J.put(ho, "path", h.path);
                    J.put(ho, "score", Math.round(h.score * 100) / 100.0);
                    J.put(ho, "snippet", h.snippet);
                    hits.put(ho);
                }
                JSONObject o = new JSONObject();
                J.put(o, "hits", hits);
                J.put(o, "indexed", res.indexed);
                return ToolResult.ok(o, res.hits.isEmpty() ? "ничего не найдено" : res.hits.size() + " файлов");
            });
    }

    private static ToolSpec searchSpec() {
        JSONObject s = ToolSpec.schema();
        JSONObject props = J.obj(s, "properties");
        JSONObject queries = new JSONObject();
        J.put(queries, "type", "array");
        J.put(queries, "description", "От 1 до 6 поисковых запросов. Они выполняются одновременно.");
        J.put(queries, "items", J.put(new JSONObject(), "type", "string"));
        J.put(queries, "minItems", 1);
        J.put(queries, "maxItems", MAX_QUERIES);
        J.put(props, "queries", queries);
        J.put(props, "limit", prop("integer", "Результатов на запрос (1–10, по умолчанию из настроек)."));
        JSONObject recency = prop("string", "Только свежие результаты: day, week, month, year.");
        J.put(recency, "enum", new JSONArray().put("day").put("week").put("month").put("year"));
        J.put(props, "recency", recency);
        J.put(props, "site", prop("string", "Ограничить сайтом, например wikipedia.org."));
        J.arr(s, "required").put("queries");
        return new ToolSpec("web_search",
            "Поиск в интернете. Принимает несколько запросов сразу (queries), выполняет их параллельно и возвращает результаты со ссылками по каждому запросу. Дубликаты ссылок убираются.",
            s);
    }

    private static JSONObject prop(String type, String description) {
        return J.put(J.put(new JSONObject(), "type", type), "description", description);
    }

    private static ToolSpec fetchSpec() {
        return ToolSpec.of("web_fetch",
            "Открыть веб-страницу и получить чистый текст (markdown со ссылками). Длинные страницы читаются частями: передайте next_offset как offset. PDF и офисные файлы не разбираются: используйте download.",
            Param.str("url", "http:// или https:// адрес страницы.", true),
            Param.str("format", "markdown (по умолчанию), text (без разметки) или raw (исходный HTML).", false).oneOf("markdown", "text", "raw"),
            Param.integer("max_chars", "Сколько символов вернуть за раз (1000–200000, по умолчанию из настроек).", false),
            Param.integer("offset", "С какого символа продолжить (берите nextOffset из прошлого ответа).", false));
    }

    static List<String> queries(JSONObject a) {
        List<String> out = new ArrayList<>();
        JSONArray arr = J.arr(a, "queries");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** Accepts "wikipedia.org", "www.wikipedia.org" or a full URL; returns the bare host. */
    static String siteOf(String site) {
        String s = site.trim().toLowerCase(Locale.ROOT);
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        return s.startsWith("www.") ? s.substring(4) : s;
    }

    private static boolean contains(String[] values, String v) {
        for (String s : values) if (s.equals(v)) return true;
        return false;
    }
}
