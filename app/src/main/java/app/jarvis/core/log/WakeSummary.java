package app.jarvis.core.log;

import java.util.ArrayDeque;

/**
 * Counts wake-word fragments and recognized calls in a sliding window, for the single rolling summary row of the journal.
 * The caller supplies the clock; the class has no Android dependencies.
 */
public final class WakeSummary {
    public static final long WINDOW_MS = 10 * 60 * 1000L;

    private final long windowMs;
    private final ArrayDeque<Long> fragments = new ArrayDeque<>();
    private final ArrayDeque<Long> calls = new ArrayDeque<>();

    public WakeSummary() { this(WINDOW_MS); }

    WakeSummary(long windowMs) { this.windowMs = windowMs; }

    public void fragment(long now) { fragments.addLast(now); }

    public void call(long now) { calls.addLast(now); }

    /** E.g. "за 10 мин: 14 фрагментов, 1 обращение". */
    public String text(long now) {
        prune(fragments, now);
        prune(calls, now);
        int f = fragments.size(), c = calls.size();
        return "за " + (windowMs / 60000) + " мин: " + f + " " + plural(f, "фрагмент", "фрагмента", "фрагментов")
            + ", " + c + " " + plural(c, "обращение", "обращения", "обращений");
    }

    private void prune(ArrayDeque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > windowMs) q.pollFirst();
    }

    static String plural(long n, String one, String few, String many) {
        long m100 = n % 100, m10 = n % 10;
        if (m10 == 1 && m100 != 11) return one;
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
        return many;
    }
}
