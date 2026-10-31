package app.jarvis.core.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SessionSearchTest {
    @Test
    public void normalizesWhitespace() {
        assertEquals("привет мир", SessionSearch.normalize("  привет \t  мир \n"));
        assertEquals("", SessionSearch.normalize("   "));
        assertEquals("", SessionSearch.normalize(null));
    }

    @Test
    public void onlyChangedQueriesIssueNewTokens() {
        SessionSearch s = new SessionSearch();
        assertFalse(s.update(""));
        assertFalse(s.filtering());
        assertTrue(s.update("план"));
        int first = s.token();
        assertFalse(s.update("план  "));
        assertEquals(first, s.token());
        assertTrue(s.isLatest(first));
    }

    @Test
    public void olderResultsAreStale() {
        SessionSearch s = new SessionSearch();
        s.update("a");
        int a = s.token();
        s.update("ab");
        int ab = s.token();
        assertFalse(s.isLatest(a));
        assertTrue(s.isLatest(ab));
        assertEquals("ab", s.query());
        assertTrue(s.filtering());
    }
}
