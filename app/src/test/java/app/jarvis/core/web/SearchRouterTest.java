package app.jarvis.core.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.util.CancelToken;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class SearchRouterTest {

    /** Fake backend: optional delay, scripted failure, call counter. Delays are real sleeps honouring cancellation. */
    static final class Fake implements SearchBackend {
        final String id;
        long delayMs;
        WebException failure;
        final List<String> urls = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();

        Fake(String id) { this.id = id; }

        @Override public String id() { return id; }
        @Override public String label() { return id; }

        @Override public List<SearchHit> search(SearchRequest r, CancelToken c) throws WebException {
            calls.incrementAndGet();
            if (delayMs > 0) {
                if (!c.sleep(delayMs)) throw new app.jarvis.core.util.CancelToken.CancelledException("cancelled");
            }
            if (failure != null) throw failure;
            List<SearchHit> out = new ArrayList<>();
            for (String u : urls) out.add(new SearchHit(r.query + " title", u, "snippet of " + r.query, ""));
            return out;
        }
    }

    private static List<String> q(String... s) { return Arrays.asList(s); }

    @Test
    public void sixSlowQueriesRunInParallel() {
        Fake f = new Fake("slow");
        f.delayMs = 400;
        f.urls.add("https://example.org/page");
        SearchRouter r = new SearchRouter(System::currentTimeMillis, 5_000, 10_000);
        long t0 = System.nanoTime();
        SearchRouter.Report rep = r.search(Collections.<SearchBackend>singletonList(f), q("a", "b", "c", "d", "e", "f"), 5, "", "", new CancelToken());
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertEquals(6, rep.results.size());
        assertTrue("took " + ms, ms < 400 * 3);
        assertEquals(6, f.calls.get());
        r.shutdown();
    }

    @Test
    public void duplicateUrlsAreListedOnlyOnceAcrossQueries() {
        Fake f = new Fake("dup");
        f.urls.add("https://www.example.org/a/?utm_source=x#frag");
        f.urls.add("https://example.org/a");
        f.urls.add("https://example.org/b");
        SearchRouter r = new SearchRouter(System::currentTimeMillis, 5_000, 10_000);
        SearchRouter.Report rep = r.search(Collections.<SearchBackend>singletonList(f), q("one", "two"), 5, "", "", new CancelToken());
        assertEquals(2, rep.results.get(0).hits.size());
        assertEquals("https://example.org/a", rep.results.get(0).hits.get(0).url);
        assertEquals(0, rep.results.get(1).hits.size());
        assertEquals(Collections.singletonList("dup"), rep.backends);
        r.shutdown();
    }

    @Test
    public void oneQueryFailingDoesNotHideTheOthers() {
        final Fake ok = new Fake("ok");
        ok.urls.add("https://example.org/ok");
        SearchBackend picky = new SearchBackend() {
            @Override public String id() { return "picky"; }
            @Override public String label() { return "Picky"; }
            @Override public List<SearchHit> search(SearchRequest req, CancelToken c) throws WebException {
                if (req.query.equals("bad")) throw new WebException("«Picky» сломался", "boom", 500, false);
                return ok.search(req, c);
            }
        };
        SearchRouter r = new SearchRouter(System::currentTimeMillis, 5_000, 10_000);
        SearchRouter.Report rep = r.search(Collections.singletonList(picky), q("good", "bad"), 5, "", "", new CancelToken());
        assertTrue(rep.results.get(0).ok());
        assertFalse(rep.results.get(1).ok());
        assertEquals("«Picky» сломался", rep.results.get(1).error);
        assertTrue(rep.anyOk());
        r.shutdown();
    }

    @Test
    public void fallsBackAndSkipsCooledBackendUntilCooldownEnds() {
        AtomicLong now = new AtomicLong(1_000_000L);
        Fake paid = new Fake("brave");
        paid.failure = WebException.http("Brave Search", 401, "bad key");
        Fake free = new Fake("ddg");
        free.urls.add("https://example.org/free");
        SearchRouter r = new SearchRouter(now::get, 5_000, 10_000);
        List<SearchBackend> chain = Arrays.<SearchBackend>asList(paid, free);

        SearchRouter.Report first = r.search(chain, q("x"), 5, "", "", new CancelToken());
        assertEquals("ddg", first.results.get(0).backend);
        assertEquals(1, paid.calls.get());

        now.addAndGet(60_000);
        SearchRouter.Report second = r.search(chain, q("y"), 5, "", "", new CancelToken());
        assertEquals("ddg", second.results.get(0).backend);
        assertEquals("brave is skipped while cooling down", 1, paid.calls.get());

        now.addAndGet(SearchRouter.COOLDOWN_MS);
        r.search(chain, q("z"), 5, "", "", new CancelToken());
        assertEquals("brave is retried after the cooldown", 2, paid.calls.get());
        r.shutdown();
    }

    @Test
    public void nonCooldownErrorTriesNextBackendWithoutPenalty() {
        AtomicLong now = new AtomicLong(0);
        Fake odd = new Fake("odd");
        odd.failure = new WebException("«odd» непонятный ответ", "parse", 0, false);
        Fake free = new Fake("ddg");
        free.urls.add("https://example.org/f");
        SearchRouter r = new SearchRouter(now::get, 5_000, 10_000);
        List<SearchBackend> chain = Arrays.<SearchBackend>asList(odd, free);
        r.search(chain, q("a"), 5, "", "", new CancelToken());
        r.search(chain, q("b"), 5, "", "", new CancelToken());
        assertEquals("not cooled, so tried again", 2, odd.calls.get());
        r.shutdown();
    }

    @Test
    public void backendTimeoutFallsThroughWithinQueryBudget() {
        Fake slow = new Fake("slow");
        slow.delayMs = 10_000;
        Fake fast = new Fake("fast");
        fast.urls.add("https://example.org/quick");
        SearchRouter r = new SearchRouter(System::currentTimeMillis, 400, 3_000);
        long t0 = System.nanoTime();
        SearchRouter.Report rep = r.search(Arrays.<SearchBackend>asList(slow, fast), q("t"), 5, "", "", new CancelToken());
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertEquals("fast", rep.results.get(0).backend);
        assertTrue("took " + ms, ms < 2_000);
        r.shutdown();
    }

    @Test
    public void overallTimeoutReportsPerQueryError() {
        Fake slow = new Fake("slow");
        slow.delayMs = 10_000;
        SearchRouter r = new SearchRouter(System::currentTimeMillis, 10_000, 500);
        long t0 = System.nanoTime();
        SearchRouter.Report rep = r.search(Collections.<SearchBackend>singletonList(slow), q("a", "b"), 5, "", "", new CancelToken());
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertFalse(rep.anyOk());
        assertTrue(rep.results.get(0).error, rep.results.get(0).error.contains("тайм-аут"));
        assertTrue("took " + ms, ms < 3_000);
        r.shutdown();
    }

    @Test
    public void cancellationStopsQueriesPromptly() throws Exception {
        Fake slow = new Fake("slow");
        slow.delayMs = 10_000;
        final CancelToken parent = new CancelToken();
        SearchRouter r = new SearchRouter(System::currentTimeMillis, 10_000, 15_000);
        new Thread(new Runnable() { @Override public void run() {
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
            parent.cancel("user");
        } }).start();
        long t0 = System.nanoTime();
        SearchRouter.Report rep = r.search(Collections.<SearchBackend>singletonList(slow), q("a", "b"), 5, "", "", parent);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertFalse(rep.anyOk());
        assertEquals("отменено", rep.results.get(0).error);
        assertTrue("took " + ms, ms < 3_000);
        r.shutdown();
    }

    @Test
    public void urlNormalizerKeysAreCanonical() {
        assertEquals("https://example.org/a", UrlNormalizer.normalize("https://WWW.Example.org/a/#top"));
        assertEquals("http://example.org/a?q=1&x=2", UrlNormalizer.normalize("http://example.org/a?utm_source=t&q=1&fbclid=z&x=2"));
        assertEquals("https://example.org/", UrlNormalizer.normalize("https://example.org/"));
        assertEquals("https://example.org/?q=1", UrlNormalizer.normalize("https://example.org?q=1&gclid=9"));
        assertEquals("example.org", UrlNormalizer.host("https://www.Example.org:8443/x"));
    }

    @Test
    public void configPicksPaidBackendsThenFallbacksInOrder() throws Exception {
        WebConfig c = WebConfig.from(
            map("web.searxng.url", "http://127.0.0.1:8888/", "web.search.maxResults", "3"),
            map("key.exa", "e", "key.brave", "b", "key.openrouter", "o"), "");
        java.util.List<SearchBackend> ids = c.chain(System::currentTimeMillis);
        assertEquals(Arrays.asList("brave", "exa", "openrouter", "searxng", "ddg"), idsOf(ids));
        assertEquals(3, c.maxResults);
        assertEquals("", WebConfig.from(map(), map(), "").searxngUrl);
        assertEquals(Collections.singletonList("ddg"), idsOf(WebConfig.from(map(), map(), "").chain(System::currentTimeMillis)));
    }

    @Test
    public void explicitBackendWithoutKeyIsAClearError() {
        WebConfig c = WebConfig.from(map("web.search.backend", "tavily"), map(), "");
        try {
            c.chain(System::currentTimeMillis);
            throw new AssertionError("expected error");
        } catch (WebException e) {
            assertTrue(e.userMessage, e.userMessage.contains("не настроен") && e.userMessage.contains("ключ"));
        }
    }

    private static java.util.function.Function<String, String> map(String... kv) {
        final java.util.Map<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return new java.util.function.Function<String, String>() {
            @Override public String apply(String k) { return m.get(k); }
        };
    }

    private static List<String> idsOf(List<SearchBackend> bs) {
        List<String> out = new ArrayList<>();
        for (SearchBackend b : bs) out.add(b.id());
        return out;
    }
}
