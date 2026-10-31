package app.jarvis.core.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.jarvis.core.MockServer;
import app.jarvis.core.util.CancelToken;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.List;
import org.junit.Test;

public class SearchBackendsTest {

    @Test
    public void braveParsesWebResultsStripsMarkupAndKeepsSource() throws Exception {
        List<SearchHit> hits = SearchBackends.Brave.parse(WebFixtures.read("brave.json"), 10);
        assertEquals(4, hits.size());
        SearchHit first = hits.get(0);
        assertEquals("Погода в Гомеле — прогноз", first.title);
        assertEquals("https://gismeteo.by/weather-gomel-4944", first.url);
        assertEquals("gismeteo.by", first.source);
        assertEquals("Прогноз погоды в Гомеле на 10 дней.", first.snippet);
        assertEquals("2026-10-28T08:00:00", first.published);
        assertEquals("Гомель — город в Белоруссии & центр области.", hits.get(1).snippet);
        assertEquals("https://ru.wikipedia.org/wiki/Гомель", hits.get(2).url);
        assertEquals(2, SearchBackends.Brave.parse(WebFixtures.read("brave.json"), 2).size());
    }

    @Test
    public void tavilyParsesContentAndOptionalPublishedDate() throws Exception {
        List<SearchHit> hits = SearchBackends.Tavily.parse(WebFixtures.read("tavily.json"), 5);
        assertEquals(2, hits.size());
        assertEquals("Gomel forecast: sunny, +12.", hits.get(0).snippet);
        assertEquals("", hits.get(0).published);
        assertEquals("Tue, 28 Oct 2026 07:10:00 GMT", hits.get(1).published);
    }

    @Test
    public void exaParsesTextAndPublishedDate() throws Exception {
        List<SearchHit> hits = SearchBackends.Exa.parse(WebFixtures.read("exa.json"), 5);
        assertEquals(2, hits.size());
        assertEquals("A short guide to the API.", hits.get(0).snippet);
        assertEquals("2026-10-20T00:00:00.000Z", hits.get(0).published);
        assertEquals("docs.example.dev", hits.get(0).source);
    }

    @Test
    public void searxngParsesContentAndEngineFreeShape() throws Exception {
        List<SearchHit> hits = SearchBackends.Searxng.parse(WebFixtures.read("searxng.json"), 5);
        assertEquals(2, hits.size());
        assertEquals("Погода Гомель — meteoblue", hits.get(0).title);
        assertEquals("https://meteoblue.com/ru/погода/gomel", hits.get(0).url);
        assertEquals("Город в Белоруссии", hits.get(1).snippet);
    }

    @Test
    public void openRouterReadsUrlCitationsAndUsage() throws Exception {
        SearchBackends.OpenRouter.Parsed p = SearchBackends.OpenRouter.parse(WebFixtures.read("openrouter.json"), 5);
        assertEquals(2, p.hits.size());
        assertEquals("https://gismeteo.by/weather-gomel-4944", p.hits.get(0).url);
        assertEquals("Прогноз на 10 дней", p.hits.get(0).snippet);
        assertEquals(512, p.promptTokens);
        assertEquals(31, p.completionTokens);
        assertTrue(p.cost > 0);
    }

    @Test
    public void openRouterFallsBackToCitationsArray() throws Exception {
        SearchBackends.OpenRouter.Parsed p = SearchBackends.OpenRouter.parse(WebFixtures.read("openrouter_citations.json"), 5);
        assertEquals(2, p.hits.size());
        assertEquals("https://example.org/a", p.hits.get(0).url);
        assertEquals(0, p.cost, 0);
    }

    @Test
    public void openRouterErrorBodyIsReportedWithRussianMessage() throws Exception {
        try {
            SearchBackends.OpenRouter.parse("{\"error\":{\"message\":\"model not found\",\"code\":404}}", 5);
            fail();
        } catch (WebException e) {
            assertTrue(e.userMessage, e.userMessage.contains("model not found"));
            assertFalse(e.cooldown);
        }
    }

    @Test
    public void duckDuckGoUnwrapsRedirectsAndPairsSnippets() throws Exception {
        List<SearchHit> hits = SearchBackends.Ddg.parse(WebFixtures.read("ddg.html"), 5);
        assertEquals(2, hits.size());
        assertEquals("Погода в Гомеле сегодня", hits.get(0).title);
        assertEquals("https://gismeteo.by/weather-gomel-4944", hits.get(0).url);
        assertEquals("Прогноз погоды & ветер на неделю.", hits.get(0).snippet);
        assertEquals("Город в Белоруссии, центр Гомельской области.", hits.get(1).snippet);
    }

    @Test
    public void duckDuckGoAntiBotPageIsUnavailableNotEmpty() throws Exception {
        try {
            SearchBackends.Ddg.parse(WebFixtures.read("ddg_antibot.html"), 5);
            fail();
        } catch (WebException e) {
            assertTrue(e.cooldown);
        }
    }

    @Test
    public void braveSendsKeyHeaderAndEncodedQueryOverLoopback() throws Exception {
        final String[] seen = new String[2];
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                seen[0] = x.getRequestURI().toString();
                seen[1] = x.getRequestHeaders().getFirst("X-Subscription-Token");
                MockServer.json(x, 200, WebFixtures.read("brave.json"));
            }
        })) {
            SearchBackend b = new SearchBackends.Brave("k-123", s.url("/res/v1/web/search"), null);
            List<SearchHit> hits = b.search(new SearchRequest("погода Гомель", 3, "week", "wikipedia.org"), new CancelToken());
            assertEquals(3, hits.size());
            assertEquals("k-123", seen[1]);
            assertTrue(seen[0], seen[0].contains("q=site%3Awikipedia.org+%D0%BF%D0%BE%D0%B3%D0%BE%D0%B4%D0%B0"));
            assertTrue(seen[0], seen[0].contains("count=3"));
            assertTrue(seen[0], seen[0].contains("freshness=pw"));
        }
    }

    @Test
    public void authErrorBecomesCooldownWorthyRussianMessage() throws Exception {
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                MockServer.json(x, 401, "{\"error\":\"invalid token\"}");
            }
        })) {
            try {
                new SearchBackends.Tavily("bad", s.url("/search"), null).search(new SearchRequest("q", 3, "", ""), new CancelToken());
                fail();
            } catch (WebException e) {
                assertEquals(401, e.status);
                assertTrue(e.cooldown);
                assertTrue(e.userMessage, e.userMessage.contains("Tavily"));
                assertTrue(e.getMessage(), e.getMessage().contains("HTTP 401"));
            }
        }
    }

    @Test
    public void openRouterRequestCarriesWebPluginAndModel() throws Exception {
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                MockServer.json(x, 200, WebFixtures.read("openrouter.json"));
            }
        })) {
            SearchBackends.OpenRouter b = new SearchBackends.OpenRouter("sk-test", s.url("/api/v1"), "", null);
            SearchBackends.OpenRouter.Parsed p = b.complete(new SearchRequest("погода", 4, "", ""), new CancelToken());
            assertEquals(2, p.hits.size());
            assertEquals(SearchBackends.OPENROUTER_DEFAULT_MODEL, b.model());
            org.json.JSONObject sent = new org.json.JSONObject(s.bodies.get(0));
            assertEquals("web", sent.getJSONArray("plugins").getJSONObject(0).getString("id"));
            assertEquals(4, sent.getJSONArray("plugins").getJSONObject(0).getInt("max_results"));
            assertEquals("погода", sent.getJSONArray("messages").getJSONObject(1).getString("content"));
            assertEquals("Bearer sk-test", s.authHeaders.get(0));
        }
    }

    @Test
    public void duckDuckGo202IsUnavailableOverLoopback() throws Exception {
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                x.sendResponseHeaders(202, -1);
                x.close();
            }
        })) {
            try {
                new SearchBackends.Ddg(s.url("/html/"), null).search(new SearchRequest("q", 3, "", ""), new CancelToken());
                fail();
            } catch (WebException e) {
                assertTrue(e.cooldown);
                assertTrue(e.userMessage, e.userMessage.contains("202"));
            }
        }
    }
}
