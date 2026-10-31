package app.jarvis.core.chat;

import static org.junit.Assert.assertEquals;

import app.jarvis.core.chat.SessionGrouping.Section;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import org.junit.Test;

public class SessionGroupingTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    static final class S {
        final String name;
        final long updated;
        final boolean pinned, archived;

        S(String name, long updated, boolean pinned, boolean archived) {
            this.name = name;
            this.updated = updated;
            this.pinned = pinned;
            this.archived = archived;
        }
    }

    private static final SessionGrouping.Fields<S> FIELDS = new SessionGrouping.Fields<S>() {
        @Override public long updated(S s) { return s.updated; }
        @Override public boolean pinned(S s) { return s.pinned; }
        @Override public boolean archived(S s) { return s.archived; }
    };

    /** Thursday 2026-10-29 00:00 UTC. */
    private static final long NOW = at(2026, Calendar.OCTOBER, 29, 0, 0);

    private static long at(int y, int month, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(UTC);
        c.clear();
        c.set(y, month, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    private static List<String> titles(List<Section<S>> sections) {
        List<String> out = new ArrayList<>();
        for (Section<S> s : sections) {
            StringBuilder b = new StringBuilder(s.title).append(':');
            for (S x : s.items) b.append(' ').append(x.name);
            out.add(b.toString());
        }
        return out;
    }

    @Test
    public void dayBoundariesAtMidnight() {
        List<S> items = Arrays.asList(
            new S("now", NOW, false, false),
            new S("justBeforeMidnight", NOW - 1, false, false),
            new S("yesterdayStart", at(2026, Calendar.OCTOBER, 28, 0, 0), false, false),
            new S("dayBeforeLate", at(2026, Calendar.OCTOBER, 27, 23, 59), false, false));
        assertEquals(Arrays.asList(
            "Сегодня: now",
            "Вчера: justBeforeMidnight yesterdayStart",
            "На этой неделе: dayBeforeLate"), titles(SessionGrouping.group(items, FIELDS, NOW, UTC)));
    }

    @Test
    public void weekStartsOnMondayNotSunday() {
        // Monday 2026-10-26 00:00 begins this week; Sunday 2026-10-25 belongs to the previous one.
        List<S> items = Arrays.asList(
            new S("mondayMorning", at(2026, Calendar.OCTOBER, 26, 0, 0), false, false),
            new S("sundayNight", at(2026, Calendar.OCTOBER, 25, 23, 59), false, false),
            new S("lastMonth", at(2026, Calendar.SEPTEMBER, 30, 12, 0), false, false));
        assertEquals(Arrays.asList(
            "На этой неделе: mondayMorning",
            "Раньше: sundayNight lastMonth"), titles(SessionGrouping.group(items, FIELDS, NOW, UTC)));
    }

    @Test
    public void sundayEveningIsYesterdayOnMonday() {
        long monday = at(2026, Calendar.OCTOBER, 26, 9, 0);
        List<S> items = Arrays.asList(new S("sun", at(2026, Calendar.OCTOBER, 25, 20, 0), false, false));
        assertEquals(Arrays.asList("Вчера: sun"), titles(SessionGrouping.group(items, FIELDS, monday, UTC)));
    }

    @Test
    public void pinnedAndArchivedOverrideDates() {
        List<S> items = Arrays.asList(
            new S("pinnedOld", at(2025, Calendar.JANUARY, 1, 0, 0), true, false),
            new S("today", NOW, false, false),
            new S("archivedToday", NOW, false, true),
            new S("pinnedArchived", NOW, true, true));
        assertEquals(Arrays.asList(
            "Закреплённые: pinnedOld",
            "Сегодня: today",
            "Архив: archivedToday pinnedArchived"), titles(SessionGrouping.group(items, FIELDS, NOW, UTC)));
    }

    @Test
    public void keepsInputOrderAndOmitsEmptySections() {
        List<S> items = Arrays.asList(
            new S("b", NOW - 1000, false, false),
            new S("a", NOW - 2000, false, false));
        List<Section<S>> out = SessionGrouping.group(items, FIELDS, NOW, UTC);
        assertEquals(1, out.size());
        assertEquals("b", out.get(0).items.get(0).name);
        assertEquals("a", out.get(0).items.get(1).name);
        assertEquals(0, SessionGrouping.group(new ArrayList<S>(), FIELDS, NOW, UTC).size());
    }

    @Test
    public void olderThanAWeekGoesToOlder() {
        List<S> items = Arrays.asList(new S("old", at(2026, Calendar.JANUARY, 3, 10, 0), false, false));
        assertEquals(Arrays.asList("Раньше: old"), titles(SessionGrouping.group(items, FIELDS, NOW, UTC)));
    }
}
