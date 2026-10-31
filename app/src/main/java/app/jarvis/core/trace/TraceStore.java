package app.jarvis.core.trace;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/**
 * Keeps the last {@value #CAPACITY} finished turns in memory and appends one compact JSON line per turn to
 * {@code <dir>/turns.jsonl} (capped at {@value #MAX_FILE_BYTES} bytes: the older half is dropped when exceeded).
 * Also tracks which turn is "current" so hooks in unrelated classes (speech service, player) can find it, and
 * decides when a turn is finished: the agent run is over AND nothing is being spoken any more (or never was).
 * A turn that is still open when the next one begins is finished as incomplete.
 */
public final class TraceStore {
    public static final int CAPACITY = 100;
    public static final long MAX_FILE_BYTES = 2L << 20;

    public interface Listener { void onTurnFinished(TurnTrace t); }

    private static final Object GLOBAL_LOCK = new Object();
    private static TraceStore global;

    /** The process-wide store (in-memory until {@link #install} supplies a directory). */
    public static TraceStore global() {
        synchronized (GLOBAL_LOCK) {
            if (global == null) global = new TraceStore(null, null, TurnTrace.SYSTEM);
            return global;
        }
    }

    /** Replaces the global store (called once at startup with the app's files dir). */
    public static TraceStore install(File dir, Listener l) {
        synchronized (GLOBAL_LOCK) {
            global = new TraceStore(dir, null, TurnTrace.SYSTEM);
            global.setListener(l);
            return global;
        }
    }

    private final File file;
    private final Executor io;
    private final TurnTrace.Clock clock;
    private final AtomicLong ids = new AtomicLong();
    private final ArrayDeque<TurnTrace> recent = new ArrayDeque<>();
    private final Object lock = new Object();
    private TurnTrace current;
    private TurnTrace handoff;
    private long handoffAt;
    private boolean speaking;
    private volatile Listener listener;

    /** @param dir directory for turns.jsonl, null = memory only; @param io executor for file writes, null = a daemon thread. */
    public TraceStore(File dir, Executor io, TurnTrace.Clock clock) {
        this.file = dir == null ? null : new File(dir, "turns.jsonl");
        this.clock = clock == null ? TurnTrace.SYSTEM : clock;
        if (io != null) this.io = io;
        else if (dir == null) this.io = new Executor() { @Override public void execute(Runnable r) { } };
        else {
            final java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "jv-trace-io"); t.setDaemon(true); return t; }
            });
            this.io = ex;
        }
        if (dir != null) dir.mkdirs();
    }

    public void setListener(Listener l) { listener = l; }

    /** A new, not yet published trace. */
    public TurnTrace create(String source) { return new TurnTrace(ids.incrementAndGet(), source, clock); }

    /** Makes the trace the current one; an unfinished predecessor is finished as incomplete. */
    public TurnTrace publish(TurnTrace t) {
        TurnTrace old;
        synchronized (lock) { old = current; current = t; }
        if (old != null && old != t) finish(old, false);
        return t;
    }

    /** The newest published turn that is not finished yet, or null. */
    public TurnTrace current() {
        synchronized (lock) { return current; }
    }

    /** The voice side parks the turn it has started right before calling the agent; the agent takes it. */
    public void handoff(TurnTrace t) {
        synchronized (lock) { handoff = t; handoffAt = clock.nanos(); }
    }

    /** The parked turn if it is younger than 60 s, else null. Only one taker gets it. */
    public TurnTrace takeHandoff() {
        synchronized (lock) {
            TurnTrace t = handoff;
            handoff = null;
            if (t == null || clock.nanos() - handoffAt > 60_000_000_000L) return null;
            return t;
        }
    }

    /** The agent run of this turn is over (success, error or cancel). */
    public void agentDone(TurnTrace t) {
        if (t == null) return;
        t.setAgentDone();
        maybeFinish(t);
    }

    /** Playback state from the speech output; the turn ends when the agent is done and speech has gone quiet. */
    public void speakingChanged(boolean nowSpeaking) {
        TurnTrace t;
        synchronized (lock) { speaking = nowSpeaking; t = current; }
        if (t == null) return;
        if (!nowSpeaking && t.has(TurnTrace.PLAY_START)) t.markLast(TurnTrace.PLAY_END);
        if (!nowSpeaking) maybeFinish(t);
    }

    /** Speech output raises "speaking" synchronously when text is queued, so agent done + silence means nothing is pending. */
    private void maybeFinish(TurnTrace t) {
        boolean sp;
        synchronized (lock) { sp = speaking; }
        if (t.isAgentDone() && !sp) finish(t, true);
    }

    private void finish(TurnTrace t, boolean complete) {
        if (!t.markFinished(complete)) return;
        synchronized (lock) {
            if (current == t) current = null;
            recent.addLast(t);
            while (recent.size() > CAPACITY) recent.pollFirst();
        }
        Listener l = listener;
        if (l != null) try { l.onTurnFinished(t); } catch (Throwable ignored) { }
        if (file != null) {
            final String line = t.toJson().toString();
            io.execute(new Runnable() { @Override public void run() { append(line); } });
        }
    }

    /** The newest n finished turns, oldest first. */
    public List<JSONObject> recent(int n) {
        List<TurnTrace> copy;
        synchronized (lock) { copy = new ArrayList<>(recent); }
        int from = Math.max(0, copy.size() - Math.max(0, n));
        List<JSONObject> out = new ArrayList<>(copy.size() - from);
        for (int i = from; i < copy.size(); i++) out.add(copy.get(i).toJson());
        return out;
    }

    /** Finished turns still in memory. */
    public int size() {
        synchronized (lock) { return recent.size(); }
    }

    // ------------------------------------------------------------------ file

    private synchronized void append(String line) {
        try {
            if (file.length() + line.length() + 1 > MAX_FILE_BYTES) trimFile();
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) { /* tracing must never disturb the app */ }
    }

    /** Keeps the newest half of the file, cut at a line boundary. */
    private void trimFile() throws IOException {
        if (!file.isFile()) return;
        long len = file.length();
        byte[] keep;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long from = len / 2;
            raf.seek(from);
            byte[] all = new byte[(int) (len - from)];
            raf.readFully(all);
            int start = 0;
            while (start < all.length && all[start] != '\n') start++;
            start = Math.min(all.length, start + 1);
            keep = new byte[all.length - start];
            System.arraycopy(all, start, keep, 0, keep.length);
        }
        try (FileOutputStream out = new FileOutputStream(file, false)) { out.write(keep); }
    }
}
