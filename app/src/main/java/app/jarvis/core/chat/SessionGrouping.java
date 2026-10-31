package app.jarvis.core.chat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Sections of the sessions drawer: pinned first, then unpinned sessions by last activity (today, yesterday, this week
 * from Monday, older), archived ones last. Weeks start on Monday regardless of locale.
 */
public final class SessionGrouping {
    public static final String PINNED = "Закреплённые", TODAY = "Сегодня", YESTERDAY = "Вчера",
        THIS_WEEK = "На этой неделе", OLDER = "Раньше", ARCHIVE = "Архив";

    public interface Fields<T> {
        long updated(T item);
        boolean pinned(T item);
        boolean archived(T item);
    }

    public static final class Section<T> {
        public final String title;
        public final List<T> items;

        Section(String title, List<T> items) {
            this.title = title;
            this.items = items;
        }
    }

    private SessionGrouping() { }

    /** Keeps the input order inside each section; empty sections are left out. */
    public static <T> List<Section<T>> group(List<T> items, Fields<T> f, long now, TimeZone zone) {
        long today = startOfDay(now, zone, 0);
        long yesterday = startOfDay(now, zone, -1);
        long week = startOfDay(now, zone, -mondayOffset(now, zone));
        List<T> pinned = new ArrayList<>(), todays = new ArrayList<>(), yesterdays = new ArrayList<>();
        List<T> thisWeek = new ArrayList<>(), older = new ArrayList<>(), archive = new ArrayList<>();
        for (T it : items) {
            if (f.archived(it)) archive.add(it);
            else if (f.pinned(it)) pinned.add(it);
            else {
                long u = f.updated(it);
                if (u >= today) todays.add(it);
                else if (u >= yesterday) yesterdays.add(it);
                else if (u >= week) thisWeek.add(it);
                else older.add(it);
            }
        }
        List<Section<T>> out = new ArrayList<>();
        add(out, PINNED, pinned);
        add(out, TODAY, todays);
        add(out, YESTERDAY, yesterdays);
        add(out, THIS_WEEK, thisWeek);
        add(out, OLDER, older);
        add(out, ARCHIVE, archive);
        return out;
    }

    private static <T> void add(List<Section<T>> out, String title, List<T> items) {
        if (!items.isEmpty()) out.add(new Section<>(title, items));
    }

    /** Days elapsed since the Monday of the week containing {@code now}: Monday 0 … Sunday 6. */
    static int mondayOffset(long now, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(now);
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    static long startOfDay(long now, TimeZone zone, int dayOffset) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, dayOffset);
        return c.getTimeInMillis();
    }
}
