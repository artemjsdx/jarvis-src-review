package app.jarvis.core.log;

import static org.junit.Assert.*;

import org.junit.Test;

public class WakeSummaryTest {

    @Test
    public void countsFragmentsAndCallsInTheWindow() {
        WakeSummary s = new WakeSummary();
        long t0 = 1_000_000L;
        for (int i = 0; i < 14; i++) s.fragment(t0 + i * 1000L);
        s.call(t0 + 5000L);
        assertEquals("за 10 мин: 14 фрагментов, 1 обращение", s.text(t0 + 20_000L));
    }

    @Test
    public void oldEntriesLeaveTheWindow() {
        WakeSummary s = new WakeSummary();
        s.fragment(0L);
        s.call(0L);
        s.fragment(WakeSummary.WINDOW_MS);
        assertEquals("за 10 мин: 1 фрагмент, 0 обращений", s.text(WakeSummary.WINDOW_MS + 1L));
        assertEquals("за 10 мин: 0 фрагментов, 0 обращений", s.text(3 * WakeSummary.WINDOW_MS));
    }

    @Test
    public void russianPluralForms() {
        assertEquals("фрагмент", WakeSummary.plural(1, "фрагмент", "фрагмента", "фрагментов"));
        assertEquals("фрагмента", WakeSummary.plural(2, "фрагмент", "фрагмента", "фрагментов"));
        assertEquals("фрагмента", WakeSummary.plural(4, "фрагмент", "фрагмента", "фрагментов"));
        assertEquals("фрагментов", WakeSummary.plural(5, "фрагмент", "фрагмента", "фрагментов"));
        assertEquals("фрагментов", WakeSummary.plural(11, "фрагмент", "фрагмента", "фрагментов"));
        assertEquals("фрагментов", WakeSummary.plural(14, "фрагмент", "фрагмента", "фрагментов"));
        assertEquals("фрагмент", WakeSummary.plural(21, "фрагмент", "фрагмента", "фрагментов"));
        assertEquals("фрагмента", WakeSummary.plural(22, "фрагмент", "фрагмента", "фрагментов"));
        assertEquals("фрагментов", WakeSummary.plural(112, "фрагмент", "фрагмента", "фрагментов"));
    }
}
