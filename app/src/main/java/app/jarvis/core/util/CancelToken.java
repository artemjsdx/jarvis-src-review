package app.jarvis.core.util;

import java.util.ArrayList;
import java.util.List;

/** Cooperative cancellation with hooks (e.g. closing a socket to unblock a read). Thread-safe. */
public final class CancelToken {
    public static final class CancelledException extends RuntimeException {
        public CancelledException(String m) { super(m); }
    }

    private final Object lock = new Object();
    private final List<Runnable> hooks = new ArrayList<>();
    private volatile boolean cancelled;
    private volatile String reason = "";

    public boolean isCancelled() { return cancelled; }
    public String reason() { return reason; }

    public void cancel() { cancel("cancelled"); }

    public void cancel(String why) {
        List<Runnable> toRun;
        synchronized (lock) {
            if (cancelled) return;
            cancelled = true;
            reason = why == null ? "cancelled" : why;
            toRun = new ArrayList<>(hooks);
            hooks.clear();
            lock.notifyAll();
        }
        for (Runnable r : toRun) {
            try { r.run(); } catch (Throwable ignored) { }
        }
    }

    /** Registers a hook; runs immediately if already cancelled. The returned Runnable unregisters it. */
    public Runnable onCancel(final Runnable r) {
        synchronized (lock) {
            if (!cancelled) {
                hooks.add(r);
                return () -> { synchronized (lock) { hooks.remove(r); } };
            }
        }
        try { r.run(); } catch (Throwable ignored) { }
        return () -> { };
    }

    public void throwIfCancelled() {
        if (cancelled) throw new CancelledException(reason);
    }

    /** Sleeps up to ms, waking early on cancellation. Returns true if the full time elapsed. */
    public boolean sleep(long ms) {
        long end = System.currentTimeMillis() + ms;
        synchronized (lock) {
            while (!cancelled) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) return true;
                try { lock.wait(left); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }
        }
        return false;
    }

    /** A token that is cancelled when this one is (but can also be cancelled independently). */
    public CancelToken child() {
        final CancelToken c = new CancelToken();
        final Runnable unhook = onCancel(() -> c.cancel(reason));
        c.onCancel(unhook);
        return c;
    }
}
