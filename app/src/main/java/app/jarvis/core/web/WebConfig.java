package app.jarvis.core.web;

import app.jarvis.core.net.Http;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Web tool settings read at call time: search backend choice, keys (from Secrets) and limits. */
public final class WebConfig {
    public static final String KEY_BACKEND = "web.search.backend", KEY_MAX_RESULTS = "web.search.maxResults",
        KEY_FETCH_CHARS = "web.fetch.maxChars", KEY_OPENROUTER_MODEL = "web.search.openrouterModel", KEY_SEARXNG_URL = "web.searxng.url";
    public static final String SECRET_BRAVE = "key.brave", SECRET_TAVILY = "key.tavily", SECRET_EXA = "key.exa",
        SECRET_OPENROUTER = "key.openrouter";

    public static final String AUTO = "auto";

    public final String mode, braveKey, tavilyKey, exaKey, openRouterKey, openRouterModel, searxngUrl;
    public final int maxResults, fetchMaxChars;
    public final Proxy proxy;

    private WebConfig(String mode, String braveKey, String tavilyKey, String exaKey, String openRouterKey, String openRouterModel,
                      String searxngUrl, int maxResults, int fetchMaxChars, Proxy proxy) {
        this.mode = mode;
        this.braveKey = braveKey;
        this.tavilyKey = tavilyKey;
        this.exaKey = exaKey;
        this.openRouterKey = openRouterKey;
        this.openRouterModel = openRouterModel;
        this.searxngUrl = searxngUrl;
        this.maxResults = maxResults;
        this.fetchMaxChars = fetchMaxChars;
        this.proxy = proxy;
    }

    /** Reads the configuration; {@code settings} and {@code secrets} return "" for missing values. */
    public static WebConfig from(Function<String, String> settings, Function<String, String> secrets, String proxySpec) {
        String mode = settings.apply(KEY_BACKEND);
        mode = mode == null || mode.trim().isEmpty() ? AUTO : mode.trim().toLowerCase(Locale.ROOT);
        return new WebConfig(mode,
            nz(secrets.apply(SECRET_BRAVE)), nz(secrets.apply(SECRET_TAVILY)), nz(secrets.apply(SECRET_EXA)),
            nz(secrets.apply(SECRET_OPENROUTER)), nz(settings.apply(KEY_OPENROUTER_MODEL)), nz(settings.apply(KEY_SEARXNG_URL)).trim(),
            clamp(intOf(settings.apply(KEY_MAX_RESULTS), 5), 1, 10),
            clamp(intOf(settings.apply(KEY_FETCH_CHARS), 12000), 1000, 200000),
            Http.parseProxy(proxySpec));
    }

    /**
     * Backends in use order. {@code auto} = configured paid keys (brave, tavily, exa), then OpenRouter, SearXNG, DuckDuckGo.
     * A named backend is used alone; asking for one that is not configured is an error.
     */
    public List<SearchBackend> chain(LongSupplier clock) throws WebException {
        List<SearchBackend> out = new ArrayList<>();
        if (AUTO.equals(mode) || !known(mode)) {
            if (!braveKey.isEmpty()) out.add(brave());
            if (!tavilyKey.isEmpty()) out.add(tavily());
            if (!exaKey.isEmpty()) out.add(exa(clock));
            if (!openRouterKey.isEmpty()) out.add(openRouter());
            if (!searxngUrl.isEmpty()) out.add(searxng());
            out.add(new SearchBackends.Ddg(SearchBackends.DDG_BASE, proxy));
            return out;
        }
        switch (mode) {
            case "brave": out.add(need(braveKey, "Brave Search", "ключ", brave())); break;
            case "tavily": out.add(need(tavilyKey, "Tavily", "ключ", tavily())); break;
            case "exa": out.add(need(exaKey, "Exa", "ключ", exa(clock))); break;
            case "openrouter": out.add(need(openRouterKey, "OpenRouter (web)", "ключ OpenRouter", openRouter())); break;
            case "searxng": out.add(need(searxngUrl, "SearXNG", "адрес сервера", searxng())); break;
            default: out.add(new SearchBackends.Ddg(SearchBackends.DDG_BASE, proxy));
        }
        return out;
    }

    public SearchBackend brave() { return new SearchBackends.Brave(braveKey, SearchBackends.BRAVE_BASE, proxy); }
    public SearchBackend tavily() { return new SearchBackends.Tavily(tavilyKey, SearchBackends.TAVILY_BASE, proxy); }
    public SearchBackend exa(LongSupplier clock) { return new SearchBackends.Exa(exaKey, SearchBackends.EXA_BASE, proxy, clock); }
    public SearchBackends.OpenRouter openRouter() {
        return new SearchBackends.OpenRouter(openRouterKey, SearchBackends.OPENROUTER_BASE, openRouterModel, proxy);
    }
    public SearchBackend searxng() { return new SearchBackends.Searxng(searxngUrl, proxy); }

    private static SearchBackend need(String value, String label, String missing, SearchBackend b) throws WebException {
        if (value.isEmpty()) throw new WebException("«" + label + "» не настроен: добавьте " + missing + " в настройках",
            "not configured: " + label, 0, false);
        return b;
    }

    private static boolean known(String m) {
        return m.equals("brave") || m.equals("tavily") || m.equals("exa") || m.equals("openrouter") || m.equals("searxng") || m.equals("ddg");
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static int intOf(String s, int def) {
        try {
            return s == null || s.trim().isEmpty() ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
