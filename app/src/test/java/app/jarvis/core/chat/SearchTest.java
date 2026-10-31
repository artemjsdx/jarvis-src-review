package app.jarvis.core.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ToolSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class SearchTest {
    private static String rep(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    @Test public void termsAreWordsLowerCaseWithYoFolded() {
        assertEquals(Arrays.asList("елка", "новогодняя"), SearchQuery.terms("  Ёлка, НОВОГОДНЯЯ!! "));
        assertEquals(Arrays.asList("елка", "е"), SearchQuery.terms("ёлка ёлка е"));
        assertTrue(SearchQuery.terms("  ?!  ").isEmpty());
        assertTrue(SearchQuery.terms(null).isEmpty());
    }

    @Test public void termsAreCappedAtEight() {
        assertEquals(SearchQuery.MAX_TERMS, SearchQuery.terms("а б в г д е ж з и к л м").size());
    }

    @Test public void ftsExpressionUsesPrefixForEveryWord() {
        assertEquals("\"сбер\"* \"банк\"*", SearchQuery.ftsMatch(SearchQuery.terms("Сбер банк")));
        assertEquals("", SearchQuery.ftsMatch(SearchQuery.terms("...")));
        assertEquals("\"42\"*", SearchQuery.ftsMatch(SearchQuery.terms("42")));
    }

    @Test public void fallbackMatchNeedsEveryWordAsSubstring() {
        List<String> t = SearchQuery.terms("сбер кредит");
        assertTrue(SearchQuery.matches("Вчера я говорил про СБЕРБАНК и кредит на машину", t));
        assertFalse(SearchQuery.matches("Вчера я говорил про сбербанк", t));
        assertFalse(SearchQuery.matches("кредит", Collections.<String>emptyList()));
        assertTrue(SearchQuery.matches("Ёлку поставили", SearchQuery.terms("елку")));
    }

    @Test public void foldKeepsLengthSoOffsetsStayValid() {
        String s = "ПРИВЕТ Ёж İstanbul";
        assertEquals(s.length(), SearchQuery.fold(s).length());
        assertEquals("привет еж istanbul", SearchQuery.fold("ПРИВЕТ Ёж Istanbul"));
    }

    @Test public void snippetShowsSixtyCharactersAroundTheWholeMatchedWord() {
        String text = rep("а", 100) + " Сбербанк " + rep("б", 100);
        String snip = SearchSnippet.around(text, SearchQuery.terms("сбер"), SearchSnippet.RADIUS);
        assertEquals("…" + rep("а", 59) + " Сбербанк " + rep("б", 59) + "…", snip);
    }

    @Test public void snippetWithoutMatchShowsTheBeginningAndFlattensNewlines() {
        assertEquals("первая строка вторая", SearchSnippet.around("первая строка\n\nвторая", SearchQuery.terms("нет"), 60));
        assertEquals("", SearchSnippet.around("  ", SearchQuery.terms("нет"), 60));
    }

    @Test public void snippetDoesNotSplitSurrogatePairs() {
        // the window start lands exactly on the low half of an emoji: it must be moved past it
        String prefix = rep("😀", 60) + "a";
        String snip = SearchSnippet.around(prefix + "слово" + rep("y", 60), SearchQuery.terms("слово"), 60);
        assertTrue(snip, snip.startsWith("…"));
        assertFalse(snip, Character.isLowSurrogate(snip.charAt(1)));
    }

    @Test public void sessionSearchToolReturnsHitsAndValidatesQuery() throws Exception {
        final List<String> seen = new ArrayList<>();
        ToolRegistry r = new ToolRegistry();
        SearchTools.register(r, new SearchTools.Searcher() {
            @Override public List<SearchHit> search(String query, int limit, int days) {
                seen.add(query + "|" + limit + "|" + days);
                List<SearchHit> h = new ArrayList<>();
                h.add(new SearchHit(7, "Поездка", 42, 1_700_000_000_000L, "user", "…в Минск на поезде…"));
                return h;
            }
        });
        ToolSpec spec = r.specs().get(0);
        assertEquals("session_search", spec.name);
        assertTrue(spec.description.contains("session_search"));

        ToolResult ok = r.get("session_search").run(new JSONObject().put("query", "поезд").put("limit", 50).put("days", 3), null);
        assertTrue(ok.ok);
        assertEquals("поезд|20|3", seen.get(0));
        JSONArray hits = ok.data.getJSONArray("hits");
        assertEquals(1, hits.length());
        JSONObject h = hits.getJSONObject(0);
        assertEquals(7, h.getLong("chat_id"));
        assertEquals("Поездка", h.getString("chat"));
        assertEquals("user", h.getString("role"));
        assertEquals(42, h.getLong("seq"));
        assertTrue(h.getString("when"), h.getString("when").matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"));

        ToolResult bad = r.get("session_search").run(new JSONObject().put("query", "?!"), null);
        assertFalse(bad.ok);
        assertEquals(1, seen.size());
    }
}
