package app.jarvis.core.net;

import app.jarvis.core.util.J;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import org.json.JSONObject;

/**
 * Artificial network limits for the app's own traffic (debug tool net_throttle). Off by default and in memory only, so an app
 * restart clears it; it also expires on its own after the requested duration.
 *
 * <p>When off, {@link #in} and {@link #out} return the stream unchanged and {@link #latency} does nothing: no overhead.
 * When on: {@code latencyMs} is added once before the first read of every stream (the wait for a response), upload and download
 * are paced to {@code kbpsUp} / {@code kbpsDown} (0 = unlimited), and with {@code lossPct} &gt; 0 each stream is reset after
 * {@code 100000 / lossPct} bytes, which surfaces as a SocketException like a dropped connection.
 */
public final class NetThrottle {
    private NetThrottle() { }

    /** Time source: tests replace it with a fake that advances on sleep. */
    public interface Time {
        long nanos();
        void sleep(long ms) throws InterruptedException;
    }

    public static final Time SYSTEM = new Time() {
        @Override public long nanos() { return System.nanoTime(); }
        @Override public void sleep(long ms) throws InterruptedException { Thread.sleep(ms); }
    };

    public static final class Rule {
        public final long latencyMs, kbpsUp, kbpsDown, untilMs;
        public final int lossPct;

        Rule(long latencyMs, long kbpsUp, long kbpsDown, int lossPct, long untilMs) {
            this.latencyMs = latencyMs; this.kbpsUp = kbpsUp; this.kbpsDown = kbpsDown; this.lossPct = lossPct; this.untilMs = untilMs;
        }

        /** Bytes a single stream may carry before it is reset, or Long.MAX_VALUE when loss is off. */
        public long resetAfterBytes() { return lossPct <= 0 ? Long.MAX_VALUE : Math.max(1, 100_000L / lossPct); }
    }

    public static final long MAX_DURATION_MS = 600_000L;

    private static volatile Rule rule;
    private static volatile Time time = SYSTEM;

    /** Installs the limits for durationMs. Zero means "no limit" for the rate and loss fields. */
    public static synchronized void set(long latencyMs, long kbpsUp, long kbpsDown, int lossPct, long durationMs) {
        if (latencyMs < 0 || latencyMs > 60_000) throw new IllegalArgumentException("latencyMs вне диапазона 0..60000");
        if (kbpsUp < 0 || kbpsDown < 0 || kbpsUp > 1_000_000 || kbpsDown > 1_000_000) throw new IllegalArgumentException("kbps вне диапазона 0..1000000");
        if (lossPct < 0 || lossPct > 100) throw new IllegalArgumentException("lossPct вне диапазона 0..100");
        if (durationMs <= 0 || durationMs > MAX_DURATION_MS) throw new IllegalArgumentException("durationMs вне диапазона 1..600000");
        rule = new Rule(latencyMs, kbpsUp, kbpsDown, lossPct, nowMs() + durationMs);
    }

    public static synchronized void clear() { rule = null; }

    /** The active rule, or null when off or expired (an expired rule is dropped). */
    public static synchronized Rule active() {
        Rule r = rule;
        if (r == null) return null;
        if (nowMs() >= r.untilMs) { rule = null; return null; }
        return r;
    }

    public static JSONObject state() {
        Rule r = active();
        JSONObject o = new JSONObject();
        J.put(o, "active", r != null);
        if (r != null) {
            J.put(o, "latencyMs", r.latencyMs);
            J.put(o, "kbpsUp", r.kbpsUp);
            J.put(o, "kbpsDown", r.kbpsDown);
            J.put(o, "lossPct", r.lossPct);
            J.put(o, "remainingMs", Math.max(0, r.untilMs - nowMs()));
        }
        return o;
    }

    /** Waits the configured latency; called once per stream, before its first read. */
    static void latency(Rule r) throws IOException {
        if (r == null || r.latencyMs <= 0) return;
        sleep(r.latencyMs);
    }

    /** Wraps a response stream. Returns {@code raw} itself when no limit is active. */
    public static InputStream in(InputStream raw) {
        Rule r = active();
        return r == null ? raw : new Throttled(raw, r);
    }

    /** Wraps an upload stream. Returns {@code raw} itself when no limit is active. */
    public static OutputStream out(OutputStream raw) {
        Rule r = active();
        return r == null ? raw : new ThrottledOut(raw, r);
    }

    static void setForTest(Time t) { time = t; }

    private static long nowMs() { return time.nanos() / 1_000_000L; }

    private static void sleep(long ms) throws IOException {
        try { time.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("прервано", e); }
    }

    /** Sleeps until {@code bytes} at {@code kbps} have passed since {@code startNs}. */
    static void pace(long startNs, long bytes, long kbps) throws IOException {
        if (kbps <= 0 || bytes <= 0) return;
        long dueNs = startNs + bytes * 8_000_000_000L / (kbps * 1000L);
        long waitNs = dueNs - time.nanos();
        if (waitNs > 0) sleep(waitNs / 1_000_000L);
    }

    private static SocketException lossError() { return new SocketException("Connection reset (NetThrottle loss)"); }

    /** Download side: latency before the first read, pacing by kbpsDown, reset after the loss budget. */
    private static final class Throttled extends FilterInputStream {
        private final Rule r;
        private final long startNs;
        private final long budget;
        private long bytes;
        private boolean started;

        Throttled(InputStream in, Rule r) {
            super(in);
            this.r = r;
            this.startNs = time.nanos();
            this.budget = r.resetAfterBytes();
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : one[0] & 0xff;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (!started) { started = true; latency(r); }
            if (bytes >= budget) throw lossError();
            int n = in.read(b, off, (int) Math.min(len, budget - bytes));
            if (n > 0) {
                bytes += n;
                pace(startNs, bytes, r.kbpsDown);
            }
            return n;
        }
    }

    /** Upload side: pacing by kbpsUp and the same loss budget. */
    private static final class ThrottledOut extends FilterOutputStream {
        private final Rule r;
        private final long startNs;
        private final long budget;
        private long bytes;

        ThrottledOut(OutputStream out, Rule r) {
            super(out);
            this.r = r;
            this.startNs = time.nanos();
            this.budget = r.resetAfterBytes();
        }

        @Override public void write(int b) throws IOException { write(new byte[] {(byte) b}, 0, 1); }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            int done = 0;
            while (done < len) {
                if (bytes >= budget) throw lossError();
                int n = (int) Math.min(len - done, budget - bytes);
                out.write(b, off + done, n);
                bytes += n;
                done += n;
                pace(startNs, bytes, r.kbpsUp);
            }
        }
    }
}
