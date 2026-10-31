package app.jarvis.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class SuggestionPolicyTest {
    private static final long T0 = 1_800_000_000_000L;

    @Test public void noCacheMeansRefresh() {
        assertTrue(SuggestionPolicy.needRefresh(T0, 0, "", "h"));
        assertTrue(SuggestionPolicy.needRefresh(T0, T0, "", "h"));
    }

    @Test public void freshAndSameContextDoesNotRefresh() {
        assertFalse(SuggestionPolicy.needRefresh(T0 + 9 * 60_000L, T0, "h", "h"));
        assertFalse(SuggestionPolicy.needRefresh(T0 + SuggestionPolicy.TTL_MS, T0, "h", "h"));
    }

    @Test public void olderThanTenMinutesRefreshesEvenWithSameContext() {
        assertTrue(SuggestionPolicy.needRefresh(T0 + SuggestionPolicy.TTL_MS + 1, T0, "h", "h"));
    }

    @Test public void changedContextRefreshesEvenWhenFresh() {
        assertTrue(SuggestionPolicy.needRefresh(T0 + 1_000L, T0, "old", "new"));
    }

    @Test public void clockMovedBackwardsCountsAsStale() {
        assertTrue(SuggestionPolicy.needRefresh(T0 - 5_000L, T0, "h", "h"));
    }

    @Test public void offlineChipsAreThreeShortRussianRequestsPerPartOfDay() {
        for (int hour : new int[]{0, 7, 12, 19}) {
            List<String> chips = SuggestionPolicy.offline(hour);
            assertEquals(3, chips.size());
            assertTrue(chips.contains("Включи музыку"));
            for (String c : chips) assertTrue(c, c.codePointCount(0, c.length()) <= SuggestionPrompt.CHIP_CHARS);
        }
        assertEquals("Доброе утро! Что нового?", SuggestionPolicy.offline(8).get(0));
        assertEquals("Что нового?", SuggestionPolicy.offline(14).get(0));
    }

    @Test public void cacheRoundTripKeepsItemsTimeAndHash() {
        String json = SuggestionCache.encode(Arrays.asList("Что нового?", "Включи музыку"), T0, "abc");
        SuggestionCache.Entry e = SuggestionCache.decode(json);
        assertNotNull(e);
        assertEquals(Arrays.asList("Что нового?", "Включи музыку"), e.items);
        assertEquals(T0, e.at);
        assertEquals("abc", e.hash);
    }

    @Test public void brokenOrEmptyCacheIsIgnored() {
        assertNull(SuggestionCache.decode("не json"));
        assertNull(SuggestionCache.decode("{\"items\":[],\"at\":1,\"hash\":\"x\"}"));
        assertNull(SuggestionCache.decode("{\"at\":1}"));
    }

    @Test public void budgetAllowsSixCallsAnHourAndRecovers() {
        CallBudget b = new CallBudget(SuggestionPolicy.MAX_CALLS_PER_HOUR, SuggestionPolicy.HOUR_MS);
        for (int i = 0; i < 6; i++) assertTrue(b.tryAcquire(T0 + i * 1000L));
        assertFalse(b.tryAcquire(T0 + 10_000L));
        assertEquals(6, b.used(T0 + 10_000L));
        assertTrue(b.tryAcquire(T0 + SuggestionPolicy.HOUR_MS + 1_000L));
    }

    @Test public void budgetSurvivesPersistence() {
        CallBudget b = new CallBudget(2, SuggestionPolicy.HOUR_MS);
        assertTrue(b.tryAcquire(T0));
        assertTrue(b.tryAcquire(T0 + 1));
        String saved = b.encode();
        CallBudget again = new CallBudget(2, SuggestionPolicy.HOUR_MS).decode(saved);
        assertFalse(again.tryAcquire(T0 + 2));
    }

    @Test public void unreadableBudgetEntriesAreIgnored() {
        assertEquals(0, new CallBudget(2, SuggestionPolicy.HOUR_MS).decode("x,,").used(T0));
    }

    @Test public void tapSendsOnlyTheChipTextAsTextMessage() {
        final List<String> calls = new ArrayList<>();
        SuggestionTap.tap("Включи музыку", new SuggestionTap.Sink() {
            @Override public void submit(String text, String source) { calls.add(text + "|" + source); }
        });
        assertEquals(Arrays.asList("Включи музыку|text"), calls);
    }

    @Test public void emptyChipIsNotSent() {
        final List<String> calls = new ArrayList<>();
        SuggestionTap.tap("  ", new SuggestionTap.Sink() {
            @Override public void submit(String text, String source) { calls.add(text); }
        });
        assertTrue(calls.isEmpty());
    }
}
