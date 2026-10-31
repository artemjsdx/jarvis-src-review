package app.jarvis.core.agent;

import java.util.ArrayList;
import java.util.List;

/** Sliding-window limit on model calls, persisted as a comma-separated list of call times. */
public final class CallBudget {
    private final int max;
    private final long windowMs;
    private final List<Long> stamps = new ArrayList<>();

    public CallBudget(int max, long windowMs) { this.max = max; this.windowMs = windowMs; }

    /** Records a call and returns true when it fits in the window; false (nothing recorded) when the limit is reached. */
    public synchronized boolean tryAcquire(long now) {
        prune(now);
        if (stamps.size() >= max) return false;
        stamps.add(now);
        return true;
    }

    public synchronized int used(long now) { prune(now); return stamps.size(); }

    private void prune(long now) {
        for (int i = stamps.size() - 1; i >= 0; i--) {
            long t = stamps.get(i);
            if (now - t >= windowMs || t > now) stamps.remove(i);
        }
    }

    public synchronized String encode() {
        StringBuilder b = new StringBuilder();
        for (long t : stamps) {
            if (b.length() > 0) b.append(',');
            b.append(t);
        }
        return b.toString();
    }

    /** Restores the saved call times; unreadable parts are ignored. */
    public synchronized CallBudget decode(String csv) {
        stamps.clear();
        if (csv == null) return this;
        for (String p : csv.split(",")) {
            try { stamps.add(Long.parseLong(p.trim())); } catch (NumberFormatException ignored) { }
        }
        return this;
    }
}
