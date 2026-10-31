package app.jarvis.core.llm;

import app.jarvis.core.util.CancelToken;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Races two routes for ONE model step: the primary starts at once; if it has produced nothing (no text, reasoning or tool-call delta)
 * after {@code afterMs}, the backup is started in parallel. The first route that delivers anything wins, the other one is cancelled and
 * everything it still emits is dropped. This is safe because a step has no side effects until it returns: tools run only after the winner's
 * result is complete, so a tool can never run twice. Both routes may be billed when the backup was started.
 */
public final class HedgedChat {
    private HedgedChat() { }

    private static final ExecutorService POOL = Executors.newCachedThreadPool(new ThreadFactory() {
        int n;
        @Override public synchronized Thread newThread(Runnable r) { Thread t = new Thread(r, "jv-hedge-" + (++n)); t.setDaemon(true); return t; }
    });

    /** What happened; filled in by {@link #chat}. */
    public static final class Info {
        public boolean backupStarted, backupWon;
        public long primaryFirstMs = -1, backupFirstMs = -1;
    }

    private static final class Gate implements LlmListener {
        final int idx; final AtomicInteger winner; final LlmListener out; final Object wake;
        Gate(int idx, AtomicInteger winner, LlmListener out, Object wake) { this.idx = idx; this.winner = winner; this.out = out; this.wake = wake; }
        private boolean pass() {
            boolean ok = winner.get() == idx || winner.compareAndSet(0, idx);
            if (ok) synchronized (wake) { wake.notifyAll(); }
            return ok;
        }
        @Override public void onTextDelta(String d) { if (pass()) out.onTextDelta(d); }
        @Override public void onReasoningDelta(String d) { if (pass()) out.onReasoningDelta(d); }
        @Override public void onToolCallDelta(int i, String id, String name, String frag) { if (pass()) out.onToolCallDelta(i, id, name, frag); }
        @Override public void onRetry(int attempt, String reason, long delayMs) { if (winner.get() == idx || winner.get() == 0) out.onRetry(attempt, reason, delayMs); }
    }

    private static final class Slot {
        volatile LlmResult result; volatile LlmException error; volatile boolean done;
    }

    private static void launch(final OpenAiClient client, final LlmConfig cfg, final LlmRequest req, final LlmListener l, final CancelToken ct, final Slot s, final Object wake) {
        POOL.execute(new Runnable() {
            @Override public void run() {
                try { s.result = client.chat(cfg, req, l, ct); }
                catch (LlmException e) { s.error = e; }
                catch (RuntimeException e) { s.error = new LlmException(LlmException.Kind.PROTOCOL, 0, "Внутренняя ошибка запроса", e.toString(), 0, null, e); }
                finally { s.done = true; synchronized (wake) { wake.notifyAll(); } }
            }
        });
    }

    public static LlmResult chat(OpenAiClient client, LlmConfig cfgA, LlmRequest reqA, LlmConfig cfgB, LlmRequest reqB, long afterMs,
                                 LlmListener listener, CancelToken cancel, Info info) throws LlmException {
        final LlmListener out = listener == null ? LlmListener.NOP : listener;
        final Info inf = info == null ? new Info() : info;
        final Object wake = new Object();
        final AtomicInteger winner = new AtomicInteger();
        final CancelToken ca = cancel.child(), cb = cancel.child();
        final Slot a = new Slot(), b = new Slot();
        final long t0 = System.nanoTime();
        launch(client, cfgA, reqA, new Gate(1, winner, out, wake), ca, a, wake);
        boolean backup = false;
        long bStart = 0;
        try {
            while (true) {
                synchronized (wake) {
                    int w = winner.get();
                    boolean finished = w == 1 ? a.done : w == 2 ? b.done : false;
                    if (w == 0 && a.done && a.error == null && !backup) finished = true;     // primary completed without any delta
                    if (!finished) {
                        if (cancel.isCancelled()) throw LlmException.cancelled();
                        long waitMs = 50;
                        if (!backup && w == 0) {
                            long left = afterMs - (System.nanoTime() - t0) / 1_000_000L;
                            if (a.done && a.error != null) throw a.error;    // failed before the hedge time: plain fallback is the caller's business
                            if (left <= 0) {
                                backup = true;
                                inf.backupStarted = true;
                                bStart = System.nanoTime();
                                launch(client, cfgB, reqB, new Gate(2, winner, out, wake), cb, b, wake);
                                continue;
                            }
                            waitMs = Math.min(waitMs, left);
                        }
                        // everyone failed before delivering anything
                        if (w == 0 && a.done && a.error != null && backup && b.done && b.error != null) throw a.error;
                        try { wake.wait(waitMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw LlmException.cancelled(); }
                        continue;
                    }
                    if (w == 0) winner.compareAndSet(0, 1);
                    w = winner.get();
                    Slot win = w == 2 ? b : a;
                    (w == 2 ? ca : cb).cancel("hedge-lost");
                    inf.backupWon = w == 2;
                    if (win.result != null) {
                        LlmResult r = win.result;
                        if (w == 1) inf.primaryFirstMs = r.firstDeltaMs; else { inf.backupFirstMs = r.firstDeltaMs; inf.primaryFirstMs = Math.max(afterMs, (bStart - t0) / 1_000_000L); }
                        return r;
                    }
                    throw win.error != null ? win.error : LlmException.cancelled();
                }
            }
        } finally {
            if (!ca.isCancelled() && winner.get() != 1) ca.cancel("hedge-end");
            if (!cb.isCancelled() && winner.get() != 2) cb.cancel("hedge-end");
        }
    }
}
