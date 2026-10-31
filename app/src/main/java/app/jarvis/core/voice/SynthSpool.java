package app.jarvis.core.voice;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory buffer between a synthesis thread (which fills it as an {@link AudioSink}) and the player thread (which drains it with
 * {@link #pipeTo}). The player starts consuming as soon as the first bytes exist, so buffering adds no latency, but synthesis of a
 * sentence can run while an earlier one is still audible.
 */
public final class SynthSpool implements AudioSink {
    private final Object lock = new Object();
    private AudioSpec spec;
    private final List<byte[]> chunks = new ArrayList<>();
    private boolean ended;
    private IOException error;
    private long bytes;

    // ---- producer side -------------------------------------------------------------------------------------------------
    @Override public void begin(AudioSpec s) {
        synchronized (lock) { spec = s; lock.notifyAll(); }
    }

    @Override public void write(byte[] d, int off, int len) throws IOException {
        if (len <= 0) return;
        byte[] c = new byte[len];
        System.arraycopy(d, off, c, 0, len);
        synchronized (lock) {
            if (error != null) throw new IOException("stopped");
            chunks.add(c);
            bytes += len;
            lock.notifyAll();
        }
    }

    @Override public void end() {
        synchronized (lock) { ended = true; lock.notifyAll(); }
    }

    /** A failed attempt that produced no audio must not poison the spool (the next provider may still fill it); the final failure is {@link #fail}. */
    @Override public void abort() { }

    /** Terminal failure or cancellation: wakes the consumer, which rethrows it; later writes by the producer fail fast. */
    public void fail(IOException e) {
        synchronized (lock) { if (error == null && !ended) error = e; lock.notifyAll(); }
    }

    // ---- consumer side -------------------------------------------------------------------------------------------------
    /** Streams everything to the sink as it arrives; returns after {@code sink.end()}. Throws the producer's failure. */
    public void pipeTo(AudioSink sink) throws IOException {
        int next = 0;
        boolean begun = false;
        while (true) {
            byte[] c = null;
            boolean finish = false;
            synchronized (lock) {
                while (true) {
                    if (error != null) throw error;
                    if (next < chunks.size()) { c = chunks.get(next); chunks.set(next, null); next++; break; }
                    if (ended) { finish = true; break; }
                    try { lock.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("stopped"); }
                }
                if (!begun && spec == null && (c != null || finish)) throw new IOException("Синтез не передал формат аудио");
            }
            if (!begun) {
                AudioSpec s;
                synchronized (lock) { s = spec; }
                if (s == null) throw new IOException("Синтез не передал формат аудио");
                sink.begin(s);
                begun = true;
            }
            if (finish) { sink.end(); return; }
            sink.write(c, 0, c.length);
        }
    }

    public long bytes() { synchronized (lock) { return bytes; } }

    public boolean isDone() { synchronized (lock) { return ended || error != null; } }
}
