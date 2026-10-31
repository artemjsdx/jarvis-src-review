package app.jarvis.core.telegram;

import java.util.ArrayDeque;

/** Sliding-window limit for write actions on the Telegram server (defaults: 20 per minute). Thread-safe. */
public final class SendLimiter {
    public static final int MAX_PER_MINUTE = 20;

    private final int max;
    private final long windowMs;
    private final ArrayDeque<Long> stamps = new ArrayDeque<>();

    public SendLimiter(int max, long windowMs) {
        this.max = max;
        this.windowMs = windowMs;
    }

    public static SendLimiter perMinute() { return new SendLimiter(MAX_PER_MINUTE, 60_000L); }

    /** Records one action at {@code nowMs} if the window has room; false means the caller must refuse it. */
    public synchronized boolean tryAcquire(long nowMs) {
        prune(nowMs);
        if (stamps.size() >= max) return false;
        stamps.addLast(nowMs);
        return true;
    }

    /** Milliseconds until the next action is allowed, or 0 when one is allowed now. */
    public synchronized long retryAfterMs(long nowMs) {
        prune(nowMs);
        if (stamps.size() < max) return 0;
        return Math.max(0, stamps.peekFirst() + windowMs - nowMs);
    }

    private void prune(long nowMs) {
        while (!stamps.isEmpty() && nowMs - stamps.peekFirst() >= windowMs) stamps.pollFirst();
    }
}
