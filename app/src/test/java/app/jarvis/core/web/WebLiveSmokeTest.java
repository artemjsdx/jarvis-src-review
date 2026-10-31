package app.jarvis.core.web;

import app.jarvis.core.util.CancelToken;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

/** Real network smoke test. Skipped unless JV_LIVE=1. The OpenRouter key is read at run time and never printed. */
public class WebLiveSmokeTest {

    @Before
    public void gate() {
        Assume.assumeTrue("set JV_LIVE=1 to run against the real network", "1".equals(System.getenv("JV_LIVE")));
    }

    private static String openRouterKey() throws Exception {
        String env = System.getenv("JV_OPENROUTER_KEY");
        if (env != null && !env.isEmpty()) return env;
        String home = System.getenv().getOrDefault("JV_HOME_DIR", "/data/data/com.termux/files/home");
        File f = new File(home, ".local/share/opencode/auth.json");
        JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        return o.getJSONObject("openrouter").getString("key");
    }

    private static void show(String label, List<SearchHit> hits) {
        System.out.println("LIVE " + label + ": " + hits.size() + " hits");
        for (int i = 0; i < Math.min(3, hits.size()); i++) {
            SearchHit h = hits.get(i);
            System.out.println("LIVE   - " + h.title + " | " + h.url);
        }
    }

    @Test
    public void openRouterSearchAndRouterChain() throws Exception {
        String key = openRouterKey();
        SearchBackends.OpenRouter or = new SearchBackends.OpenRouter(key, SearchBackends.OPENROUTER_BASE, "", null);
        long t0 = System.currentTimeMillis();
        SearchBackends.OpenRouter.Parsed p = or.complete(new SearchRequest("погода Гомель", 5, "", ""), new CancelToken());
        System.out.println("LIVE openrouter model=" + or.model() + " ms=" + (System.currentTimeMillis() - t0)
            + " prompt_tokens=" + p.promptTokens + " completion_tokens=" + p.completionTokens + " cost_usd=" + p.cost);
        show("openrouter direct", p.hits);
        assertTrue("openrouter returned no citations", !p.hits.isEmpty());

        SearchRouter router = new SearchRouter(System::currentTimeMillis);
        List<SearchBackend> chain = Arrays.<SearchBackend>asList(or, new SearchBackends.Ddg(SearchBackends.DDG_BASE, null));
        SearchRouter.Report rep = router.search(chain, Arrays.asList("погода Гомель", "asyncio event loop docs"), 5, "", "", new CancelToken());
        System.out.println("LIVE router ms=" + rep.ms + " backends=" + rep.backends);
        for (SearchRouter.QueryOutcome q : rep.results) {
            System.out.println("LIVE   query=" + q.query + " ok=" + q.ok() + " hits=" + q.hits.size() + (q.ok() ? "" : " error=" + q.error));
        }
        router.shutdown();
    }

    @Test
    public void duckDuckGoLiveOutcome() throws Exception {
        try {
            List<SearchHit> hits = new SearchBackends.Ddg(SearchBackends.DDG_BASE, null)
                .search(new SearchRequest("погода Гомель", 5, "", ""), new CancelToken());
            show("duckduckgo", hits);
        } catch (WebException e) {
            System.out.println("LIVE duckduckgo unavailable: " + e.userMessage + " (cooldown=" + e.cooldown + ")");
        }
    }

    @Test
    public void fetchRussianWikipediaAndPythonDocs() throws Exception {
        WebFetcher f = new WebFetcher(null);
        long t0 = System.currentTimeMillis();
        WebFetcher.Page wiki = f.fetch("https://ru.wikipedia.org/wiki/Гомель", "markdown", 12000, 0, new CancelToken());
        System.out.println("LIVE wikipedia status=" + wiki.status + " title=" + wiki.title + " chars=" + wiki.totalChars
            + " links=" + wiki.links.size() + " ms=" + (System.currentTimeMillis() - t0) + " nextOffset=" + wiki.nextOffset);
        System.out.println("LIVE wikipedia head: " + abridge(wiki.text, 300));
        assertTrue("wikipedia text too short: " + wiki.totalChars, wiki.totalChars > 2000);

        t0 = System.currentTimeMillis();
        WebFetcher.Page docs = f.fetch("https://docs.python.org/3/library/asyncio-eventloop.html", "markdown", 12000, 0, new CancelToken());
        System.out.println("LIVE python-docs status=" + docs.status + " title=" + docs.title + " chars=" + docs.totalChars
            + " links=" + docs.links.size() + " ms=" + (System.currentTimeMillis() - t0));
        System.out.println("LIVE python-docs head: " + abridge(docs.text, 300));
        assertTrue("docs text too short: " + docs.totalChars, docs.totalChars > 2000);
    }

    private static String abridge(String s, int n) {
        String t = s.replace('\n', ' ');
        return t.length() > n ? t.substring(0, n) + "…" : t;
    }
}
