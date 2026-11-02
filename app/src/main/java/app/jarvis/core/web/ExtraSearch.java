package app.jarvis.core.web;

import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.J;
import java.net.Proxy;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Optional search connections are independent from the user's default WebConfig backend.
 * An extra is advertised to the agent only after a real nonempty search, and only while
 * enabled and authenticated by the SAME key that passed the check.
 */
public final class ExtraSearch {
    private ExtraSearch() { }

    public static final class Provider {
        public final String id, title, secret, freeText, purpose, caveat, docs;
        Provider(String id, String title, String secret, String freeText, String purpose, String caveat, String docs) {
            this.id=id; this.title=title; this.secret=secret; this.freeText=freeText;
            this.purpose=purpose; this.caveat=caveat; this.docs=docs;
        }
    }

    public static final List<Provider> CATALOG = java.util.Collections.unmodifiableList(Arrays.asList(
        new Provider("exa", "Exa Search", "key.exa", "$10 кредитов/месяц; бонусы отдельно",
            "Семантический поиск по документации, техническим статьям, GitHub и нишевым материалам.",
            "Режим Lite дешевле Auto; $10 бесплатных кредитов не означает безлимит.", "https://docs.exa.ai/reference/search"),
        new Provider("tavily", "Tavily", "key.tavily", "1 000 API-кредитов/месяц",
            "Поиск для ИИ с удобными короткими выдержками, быстрый обзор нескольких источников.",
            "Advanced-запросы расходуют больше кредитов, чем базовые.", "https://docs.tavily.com/documentation/api-reference/endpoint/search"),
        new Provider("brave", "Brave Search", "key.brave", "$5 кредитов/месяц; тариф проверяйте",
            "Независимый индекс с обычной выдачей сайтов и фильтром свежести.",
            "Для подключения бесплатного тарифного уровня может требоваться платёжный метод.", "https://api-dashboard.search.brave.com/app/documentation/web-search"),
        new Provider("parallel", "Parallel Search", "key.parallel", "$5 кредитов/месяц; отдельно есть бесплатный MCP",
            "Быстрый поиск для агентов, подбор источников по смыслу и цитируемые фрагменты.",
            "У Search API и бесплатного Parallel Search MCP разные способы подключения.",
            "https://docs.parallel.ai/search/search-quickstart"),
        new Provider("serper", "Serper Google", "key.serper", "2 500 пробных запросов (разово)",
            "Структурированная поисковая выдача Google: удобна для фактов и свежих страниц.",
            "Стартовый запас запросов не обновляется каждый месяц.",
            "https://serper.dev/playground")
    ));

    public static Provider provider(String id) {
        for (Provider p : CATALOG) if (p.id.equals(id)) return p;
        return null;
    }

    public static String keyEnabled(String id) { return "web.extra." + id + ".enabled"; }
    public static String keyChecked(String id) { return "web.extra." + id + ".verified"; }
    public static String keyCheckedAt(String id) { return "web.extra." + id + ".verifiedAt"; }

    /** SHA-256 fingerprint, not a persisted API secret; changing a key invalidates verification automatically. */
    public static String fingerprint(String id, String secret) {
        if (id == null || secret == null || secret.trim().isEmpty()) return "";
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(("jarvis-search-v1:" + id + ":" + secret.trim()).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) { return ""; }
    }

    public static boolean validated(String id, Function<String,String> settings, Function<String,String> secrets) {
        Provider p = provider(id);
        if (p == null || !"true".equalsIgnoreCase(settings.apply(keyEnabled(id)))) return false;
        String key = secrets.apply(p.secret);
        String fp = fingerprint(id, key);
        if (fp.isEmpty() || !fp.equals(settings.apply(keyChecked(id)))) return false;
        try {
            long stamp = Long.parseLong(settings.apply(keyCheckedAt(id)));
            return stamp > 0 && System.currentTimeMillis() - stamp < 7L * 24 * 3600 * 1000;
        } catch (Exception ignored) { return false; }
    }

    public static String agentSummary(Function<String,String> settings, Function<String,String> secrets) {
        StringBuilder b = new StringBuilder();
        for (Provider p : CATALOG) {
            if (validated(p.id, settings, secrets)) {
                if (b.length() == 0) b.append("Дополнительный подтверждённый веб-поиск: ");
                else b.append("; ");
                b.append(p.id).append(" (").append(p.purpose).append(")");
            }
        }
        if (b.length() == 0) return "";
        b.append(". Для выбора вызывай web_search_extra(provider,query), для документации web_provider_guide(provider). Основной web_search остаётся доступен.");
        return b.toString();
    }

    public static SearchBackend backend(String id, String apiKey, Proxy proxy, LongSupplier clock) throws WebException {
        if (apiKey == null || apiKey.trim().isEmpty())
            throw new WebException("Добавьте API-ключ поискового сервиса.", "missing key for " + id, 0, false);
        switch (id) {
            case "exa": return new SearchBackends.Exa(apiKey, SearchBackends.EXA_BASE, proxy, clock);
            case "tavily": return new SearchBackends.Tavily(apiKey, SearchBackends.TAVILY_BASE, proxy);
            case "brave": return new SearchBackends.Brave(apiKey, SearchBackends.BRAVE_BASE, proxy);
            case "serper": return new Serper(apiKey, proxy);
            case "parallel": return new Parallel(apiKey, proxy);
            default: throw new WebException("Неизвестный поисковый провайдер.", "unknown provider", 0, false);
        }
    }

    public static final class Serper implements SearchBackend {
        private final String key; private final Proxy proxy;
        Serper(String key, Proxy proxy) { this.key = key; this.proxy=proxy; }
        @Override public String id() { return "serper"; }
        @Override public String label() { return "Serper Google"; }
        @Override public List<SearchHit> search(SearchRequest request, CancelToken cancel) throws WebException {
            JSONObject body = new JSONObject();
            J.put(body, "q", request.text());
            J.put(body, "num", Math.max(1, Math.min(10, request.limit)));
            String t = request.recency;
            if ("day".equals(t)) J.put(body, "tbs", "qdr:d");
            else if ("week".equals(t)) J.put(body, "tbs", "qdr:w");
            else if ("month".equals(t)) J.put(body, "tbs", "qdr:m");
            else if ("year".equals(t)) J.put(body, "tbs", "qdr:y");
            SearchBackends.Reply rep = SearchBackends.post(label(), "https://google.serper.dev/search",
                SearchBackends.headers("Content-Type", "application/json", "X-API-KEY", key),
                body.toString(), proxy, cancel);
            rep.requireOk(label());
            JSONObject json = WebHttp.json(label(), rep.text);
            JSONArray organic = json.optJSONArray("organic");
            List<SearchHit> out = new ArrayList<>();
            if (organic != null) for (int i=0; i<organic.length() && out.size()<request.limit; i++) {
                JSONObject x=organic.optJSONObject(i); if (x == null) continue;
                SearchHit hit = new SearchHit(x.optString("title"), x.optString("link"),
                    x.optString("snippet"), x.optString("date"));
                if (!hit.url.isEmpty()) out.add(hit);
            }
            return out;
        }
    }

    public static final class Parallel implements SearchBackend {
        private final String key; private final Proxy proxy;
        Parallel(String key, Proxy proxy) { this.key=key; this.proxy=proxy; }
        @Override public String id() { return "parallel"; }
        @Override public String label() { return "Parallel Search"; }
        @Override public List<SearchHit> search(SearchRequest request, CancelToken cancel) throws WebException {
            JSONObject body = new JSONObject();
            J.put(body, "objective", "Find reliable web sources for: " + request.text());
            J.put(body, "search_queries", new JSONArray().put(request.text()));
            J.put(body, "mode", "fast");
            J.put(body, "max_chars_total", 3200);
            SearchBackends.Reply rep = SearchBackends.post(label(), "https://api.parallel.ai/v1/search",
                SearchBackends.headers("Content-Type", "application/json", "x-api-key", key),
                body.toString(), proxy, cancel);
            rep.requireOk(label());
            JSONObject json = WebHttp.json(label(), rep.text);
            JSONArray results=json.optJSONArray("results");
            List<SearchHit> out=new ArrayList<>();
            if (results!=null) for (int i=0;i<results.length()&&out.size()<request.limit;i++) {
                JSONObject x=results.optJSONObject(i); if(x==null)continue;
                JSONArray snippets=x.optJSONArray("excerpts");
                StringBuilder excerpt=new StringBuilder();
                if(snippets!=null)for(int j=0;j<snippets.length()&&j<2;j++) {
                    if(excerpt.length()>0)excerpt.append(" ");
                    excerpt.append(snippets.optString(j,""));
                }
                SearchHit hit=new SearchHit(x.optString("title"),x.optString("url"),
                    excerpt.toString(),x.optString("publish_date"));
                if(!hit.url.isEmpty())out.add(hit);
            }
            return out;
        }
    }
}
