package app.jarvis.core.web;

import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.J;
import java.net.Proxy;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Search backends: each maps its provider's documented JSON or HTML to {@link SearchHit}s. Base URLs are injectable for tests. */
public final class SearchBackends {
    private SearchBackends() { }

    public static final String BRAVE_BASE = "https://api.search.brave.com/res/v1/web/search";
    public static final String TAVILY_BASE = "https://api.tavily.com/search";
    public static final String EXA_BASE = "https://api.exa.ai/search";
    public static final String DDG_BASE = "https://html.duckduckgo.com/html/";
    public static final String OPENROUTER_BASE = "https://openrouter.ai/api/v1";
    public static final String OPENROUTER_DEFAULT_MODEL = "deepseek/deepseek-v4.1-flash";

    static final int CONNECT_MS = 6_000, READ_MS = 15_000, MAX_BODY = 1 << 20;
    static final String BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    static Reply get(String service, String url, Map<String, String> h, Proxy proxy, CancelToken c) throws WebException {
        return new Reply(WebHttp.send(service, "GET", url, h, null, proxy, CONNECT_MS, READ_MS, true, MAX_BODY, c));
    }

    static Reply post(String service, String url, Map<String, String> h, String body, Proxy proxy, CancelToken c) throws WebException {
        return new Reply(WebHttp.send(service, "POST", url, h, body.getBytes(java.nio.charset.StandardCharsets.UTF_8), proxy,
            CONNECT_MS, READ_MS, true, MAX_BODY, c));
    }

    static final class Reply {
        final int status;
        final String text;

        Reply(WebHttp.Reply r) {
            this.status = r.status;
            this.text = WebHttp.text(r.body);
        }

        void requireOk(String service) throws WebException {
            if (status >= 400) throw WebException.http(service, status, text);
        }
    }

    static Map<String, String> headers(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static String str(JSONObject o, String k) {
        String s = o.optString(k, "");
        return s == null ? "" : s;
    }

    static List<SearchHit> none() { return Collections.emptyList(); }

    static int cap(int limit) { return Math.max(1, Math.min(10, limit)); }

    static String isoDaysAgo(long nowMs, int days) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'00:00:00.000'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(nowMs - days * 86_400_000L));
    }

    static int recencyDays(String recency) {
        switch (recency) {
            case "day": return 1;
            case "week": return 7;
            case "month": return 31;
            case "year": return 365;
            default: return 0;
        }
    }

    // ---- Brave Search: GET ?q=&count=&freshness= ; header X-Subscription-Token; web.results[] ---------------------------

    public static final class Brave implements SearchBackend {
        private final String key, base;
        private final Proxy proxy;

        public Brave(String key, String base, Proxy proxy) {
            this.key = key;
            this.base = base;
            this.proxy = proxy;
        }

        @Override public String id() { return "brave"; }
        @Override public String label() { return "Brave Search"; }

        @Override public List<SearchHit> search(SearchRequest r, CancelToken c) throws WebException {
            String freshness = r.recency.isEmpty() ? "" : "&freshness=" + (r.recency.equals("day") ? "pd" : r.recency.equals("week") ? "pw"
                : r.recency.equals("month") ? "pm" : "py");
            String url = base + "?q=" + WebHttp.enc(r.text()) + "&count=" + cap(r.limit) + freshness;
            Reply rep = get(label(), url, headers("Accept", "application/json", "X-Subscription-Token", key), proxy, c);
            rep.requireOk(label());
            return parse(rep.text, cap(r.limit));
        }

        public static List<SearchHit> parse(String body, int limit) throws WebException {
            JSONObject o = WebHttp.json("Brave Search", body);
            JSONObject web = o.optJSONObject("web");
            JSONArray arr = web == null ? null : web.optJSONArray("results");
            if (arr == null) return none();
            List<SearchHit> out = new ArrayList<>();
            for (int i = 0; i < arr.length() && out.size() < limit; i++) {
                JSONObject h = arr.optJSONObject(i);
                if (h == null) continue;
                out.add(new SearchHit(HtmlToText.stripTags(str(h, "title")), str(h, "url"), HtmlToText.stripTags(str(h, "description")),
                    str(h, "page_age")));
            }
            return out;
        }
    }

    // ---- Tavily: POST /search with Bearer key; results[] {title,url,content,published_date} --------------------------

    public static final class Tavily implements SearchBackend {
        private final String key, base;
        private final Proxy proxy;

        public Tavily(String key, String base, Proxy proxy) {
            this.key = key;
            this.base = base;
            this.proxy = proxy;
        }

        @Override public String id() { return "tavily"; }
        @Override public String label() { return "Tavily"; }

        @Override public List<SearchHit> search(SearchRequest r, CancelToken c) throws WebException {
            JSONObject body = new JSONObject();
            J.put(body, "query", r.text());
            J.put(body, "max_results", cap(r.limit));
            J.put(body, "search_depth", "basic");
            if (!r.recency.isEmpty()) J.put(body, "time_range", r.recency);
            Reply rep = post(label(), base, headers("Content-Type", "application/json", "Authorization", "Bearer " + key), body.toString(), proxy, c);
            rep.requireOk(label());
            return parse(rep.text, cap(r.limit));
        }

        public static List<SearchHit> parse(String body, int limit) throws WebException {
            JSONObject o = WebHttp.json("Tavily", body);
            JSONArray arr = o.optJSONArray("results");
            if (arr == null) return none();
            List<SearchHit> out = new ArrayList<>();
            for (int i = 0; i < arr.length() && out.size() < limit; i++) {
                JSONObject h = arr.optJSONObject(i);
                if (h == null) continue;
                out.add(new SearchHit(str(h, "title"), str(h, "url"), str(h, "content"), str(h, "published_date")));
            }
            return out;
        }
    }

    // ---- Exa: POST /search with x-api-key; results[] {title,url,text,publishedDate} --------------------------------------

    public static final class Exa implements SearchBackend {
        private final String key, base;
        private final Proxy proxy;
        private final LongSupplier clock;

        public Exa(String key, String base, Proxy proxy, LongSupplier clock) {
            this.key = key;
            this.base = base;
            this.proxy = proxy;
            this.clock = clock;
        }

        @Override public String id() { return "exa"; }
        @Override public String label() { return "Exa"; }

        @Override public List<SearchHit> search(SearchRequest r, CancelToken c) throws WebException {
            JSONObject body = new JSONObject();
            J.put(body, "query", r.text());
            J.put(body, "numResults", cap(r.limit));
            J.put(body, "contents", J.put(new JSONObject(), "text", J.put(new JSONObject(), "maxCharacters", 300)));
            int days = recencyDays(r.recency);
            if (days > 0) J.put(body, "startPublishedDate", isoDaysAgo(clock.getAsLong(), days));
            Reply rep = post(label(), base, headers("Content-Type", "application/json", "x-api-key", key), body.toString(), proxy, c);
            rep.requireOk(label());
            return parse(rep.text, cap(r.limit));
        }

        public static List<SearchHit> parse(String body, int limit) throws WebException {
            JSONObject o = WebHttp.json("Exa", body);
            JSONArray arr = o.optJSONArray("results");
            if (arr == null) return none();
            List<SearchHit> out = new ArrayList<>();
            for (int i = 0; i < arr.length() && out.size() < limit; i++) {
                JSONObject h = arr.optJSONObject(i);
                if (h == null) continue;
                out.add(new SearchHit(str(h, "title"), str(h, "url"), str(h, "text"), str(h, "publishedDate")));
            }
            return out;
        }
    }

    // ---- SearXNG (user's own instance): GET <base>/search?q=&format=json ------------------------------------------------

    public static final class Searxng implements SearchBackend {
        private final String base;
        private final Proxy proxy;

        public Searxng(String base, Proxy proxy) {
            this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
            this.proxy = proxy;
        }

        @Override public String id() { return "searxng"; }
        @Override public String label() { return "SearXNG"; }

        @Override public List<SearchHit> search(SearchRequest r, CancelToken c) throws WebException {
            String tr = r.recency.isEmpty() ? "" : "&time_range=" + r.recency;
            Reply rep = get(label(), base + "/search?q=" + WebHttp.enc(r.text()) + "&format=json" + tr, headers("Accept", "application/json"), proxy, c);
            rep.requireOk(label());
            return parse(rep.text, cap(r.limit));
        }

        public static List<SearchHit> parse(String body, int limit) throws WebException {
            JSONObject o = WebHttp.json("SearXNG", body);
            JSONArray arr = o.optJSONArray("results");
            if (arr == null) return none();
            List<SearchHit> out = new ArrayList<>();
            for (int i = 0; i < arr.length() && out.size() < limit; i++) {
                JSONObject h = arr.optJSONObject(i);
                if (h == null) continue;
                out.add(new SearchHit(str(h, "title"), str(h, "url"), str(h, "content"), str(h, "publishedDate")));
            }
            return out;
        }
    }

    // ---- DuckDuckGo HTML endpoint (last resort; often answers 202 with an anti-bot page) ----------------------------------

    public static final class Ddg implements SearchBackend {
        private final String base;
        private final Proxy proxy;

        public Ddg(String base, Proxy proxy) {
            this.base = base;
            this.proxy = proxy;
        }

        @Override public String id() { return "ddg"; }
        @Override public String label() { return "DuckDuckGo"; }

        @Override public List<SearchHit> search(SearchRequest r, CancelToken c) throws WebException {
            String df = r.recency.isEmpty() ? "" : "&df=" + r.recency.substring(0, 1);
            Reply rep = get(label(), base + "?q=" + WebHttp.enc(r.text()) + df, headers("Accept", "text/html", "User-Agent", BROWSER_UA), proxy, c);
            if (rep.status == 202) throw WebException.unavailable(label(), "антибот-страница (HTTP 202)");
            rep.requireOk(label());
            return parse(rep.text, cap(r.limit));
        }

        public static List<SearchHit> parse(String html, int limit) throws WebException {
            List<String[]> rows = new ArrayList<>();
            collect(HtmlParser.parse(html), rows);
            List<SearchHit> out = new ArrayList<>();
            for (String[] row : rows) {
                if (out.size() >= limit) break;
                String url = resolveRedirect(row[1]);
                if (url.isEmpty()) continue;
                out.add(new SearchHit(row[0], url, row[2], ""));
            }
            if (out.isEmpty() && (html.contains("anomaly-modal") || html.contains("unusual traffic"))) {
                throw WebException.unavailable("DuckDuckGo", "антибот-страница");
            }
            return out;
        }

        /** Walks the document in order; each result link opens a row, its snippet fills the row. Rows are {title, href, snippet}. */
        private static void collect(HtmlNode n, List<String[]> rows) {
            for (HtmlNode c : n.children) {
                if (c.isText()) continue;
                String cls = " " + c.attr("class") + " ";
                if (cls.contains(" result__a ")) rows.add(new String[]{HtmlToText.collapse(HtmlToText.textOf(c)).trim(), c.attr("href"), ""});
                else if (cls.contains(" result__snippet ") && !rows.isEmpty() && rows.get(rows.size() - 1)[2].isEmpty()) {
                    rows.get(rows.size() - 1)[2] = HtmlToText.collapse(HtmlToText.textOf(c)).trim();
                }
                collect(c, rows);
            }
        }

        static String resolveRedirect(String href) {
            int i = href.indexOf("uddg=");
            if (i >= 0) {
                String v = href.substring(i + 5);
                int amp = v.indexOf('&');
                if (amp >= 0) v = v.substring(0, amp);
                try {
                    return URLDecoder.decode(v, "UTF-8");
                } catch (Exception e) {
                    return "";
                }
            }
            if (href.startsWith("//")) return "https:" + href;
            return href.startsWith("http") ? href : "";
        }
    }

    // ---- OpenRouter web plugin: chat completion with plugins=[web]; citations come back as url_citation annotations -------

    public static final class OpenRouter implements SearchBackend {
        /** Parsed chat completion: hits plus the token usage and cost the provider reported. */
        public static final class Parsed {
            public final List<SearchHit> hits;
            public final int promptTokens, completionTokens;
            public final double cost;

            Parsed(List<SearchHit> hits, int promptTokens, int completionTokens, double cost) {
                this.hits = hits;
                this.promptTokens = promptTokens;
                this.completionTokens = completionTokens;
                this.cost = cost;
            }
        }

        private final String key, base, model;
        private final Proxy proxy;

        public OpenRouter(String key, String base, String model, Proxy proxy) {
            this.key = key;
            this.base = base;
            this.model = model == null || model.isEmpty() ? OPENROUTER_DEFAULT_MODEL : model;
            this.proxy = proxy;
        }

        @Override public String id() { return "openrouter"; }
        @Override public String label() { return "OpenRouter (web)"; }

        public String model() { return model; }

        @Override public List<SearchHit> search(SearchRequest r, CancelToken c) throws WebException {
            return complete(r, c).hits;
        }

        public Parsed complete(SearchRequest r, CancelToken c) throws WebException {
            int n = cap(r.limit);
            String freshness = r.recency.isEmpty() ? "" : " Prefer sources published in the last " + r.recency + ".";
            JSONObject body = new JSONObject();
            J.put(body, "model", model);
            JSONArray msgs = new JSONArray();
            msgs.put(J.put(J.put(new JSONObject(), "role", "system"), "content",
                "You are a search tool. Answer in one short sentence. Cite the sources you used." + freshness));
            msgs.put(J.put(J.put(new JSONObject(), "role", "user"), "content", r.text()));
            J.put(body, "messages", msgs);
            J.put(body, "plugins", new JSONArray().put(J.put(J.put(new JSONObject(), "id", "web"), "max_results", n)));
            J.put(body, "max_tokens", 150);
            J.put(body, "temperature", 0);
            Reply rep = post(label(), base + "/chat/completions", headers("Content-Type", "application/json", "Authorization", "Bearer " + key,
                "X-Title", "Jarvis"), body.toString(), proxy, c);
            rep.requireOk(label());
            return parse(rep.text, n);
        }

        public static Parsed parse(String body, int limit) throws WebException {
            JSONObject o = WebHttp.json("OpenRouter", body);
            JSONObject err = o.optJSONObject("error");
            if (err != null) throw new WebException("«OpenRouter» вернул ошибку: " + err.optString("message", "без текста"),
                "OpenRouter error: " + err, 0, false);
            JSONArray choices = o.optJSONArray("choices");
            if (choices == null || choices.length() == 0) throw WebException.parse("OpenRouter", "нет choices");
            JSONObject msg = choices.optJSONObject(0) == null ? null : choices.optJSONObject(0).optJSONObject("message");
            List<SearchHit> hits = new ArrayList<>();
            JSONArray ann = msg == null ? null : msg.optJSONArray("annotations");
            if (ann != null) {
                for (int i = 0; i < ann.length() && hits.size() < limit; i++) {
                    JSONObject a = ann.optJSONObject(i);
                    if (a == null || !"url_citation".equals(a.optString("type"))) continue;
                    JSONObject uc = a.optJSONObject("url_citation");
                    if (uc == null) continue;
                    hits.add(new SearchHit(str(uc, "title"), str(uc, "url"), str(uc, "content"), ""));
                }
            }
            JSONArray cites = o.optJSONArray("citations");
            if (cites == null && msg != null) cites = msg.optJSONArray("citations");
            if (hits.isEmpty() && cites != null) {
                for (int i = 0; i < cites.length() && hits.size() < limit; i++) {
                    String u = cites.optString(i, "");
                    if (u.startsWith("http")) hits.add(new SearchHit("", u, "", ""));
                }
            }
            JSONObject usage = o.optJSONObject("usage");
            int pt = usage == null ? 0 : usage.optInt("prompt_tokens", 0);
            int ct = usage == null ? 0 : usage.optInt("completion_tokens", 0);
            double cost = usage == null ? 0 : usage.optDouble("cost", 0);
            return new Parsed(hits, pt, ct, cost);
        }
    }

}
