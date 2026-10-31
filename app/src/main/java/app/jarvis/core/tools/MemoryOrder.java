package app.jarvis.core.tools;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Pure ordering and timestamp formatting for the memory list in settings. */
public final class MemoryOrder {
    private MemoryOrder() { }

    /** Newest first; entries with the same timestamp are ordered by key so the list does not jump between redraws. */
    public static List<MemoryStore.Entry> newestFirst(List<MemoryStore.Entry> entries) {
        List<MemoryStore.Entry> out = new ArrayList<>(entries);
        Collections.sort(out, new Comparator<MemoryStore.Entry>() {
            @Override public int compare(MemoryStore.Entry a, MemoryStore.Entry b) {
                int byTime = Long.compare(b.updated, a.updated);
                return byTime != 0 ? byTime : a.key.compareTo(b.key);
            }
        });
        return out;
    }

    /** "29.10 03:12" in the given zone; a zero timestamp (written by older versions) reads "дата неизвестна". */
    public static String stamp(long epochMs, TimeZone zone) {
        if (epochMs <= 0) return "дата неизвестна";
        SimpleDateFormat f = new SimpleDateFormat("dd.MM HH:mm", Locale.ROOT);
        f.setTimeZone(zone);
        return f.format(new Date(epochMs));
    }
}
