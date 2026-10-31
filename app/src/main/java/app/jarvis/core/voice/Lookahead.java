package app.jarvis.core.voice;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Synthesis look-ahead for a queue of sentences that a player consumes strictly one after another.
 * Sentence N+1 starts synthesising the moment the player takes sentence N (never earlier, so at most ONE sentence is ahead), and a
 * sentence that is added while the previous one is already being played starts at once. Without this the gap between two sentences
 * equals the time to the first audio byte of the second (0.6-0.9 s on Edge, more on slow links).
 */
public final class Lookahead {
    /** Fills the spool completely (blocking) or throws; the exception reaches the player through the spool. */
    public interface Synth { void run(SynthSpool spool) throws Exception; }

    public final class Item {
        private final Synth synth;
        private final SynthSpool spool = new SynthSpool();
        private final AtomicBoolean started = new AtomicBoolean();
        private volatile boolean consumerStarted;
        private volatile Item next;
        private volatile boolean cancelled;

        Item(Synth s) { synth = s; }

        void start() {
            if (cancelled || !started.compareAndSet(false, true)) return;
            workers.execute(new Runnable() {
                @Override public void run() {
                    try { synth.run(spool); spool.end(); }
                    catch (IOException e) { spool.fail(e); }
                    catch (Throwable t) { spool.fail(new IOException(t.getMessage() == null ? t.toString() : t.getMessage(), t)); }
                }
            });
        }

        /** Called on the player thread when this sentence's turn comes. */
        public void produce(AudioSink sink) throws IOException {
            consumerStarted = true;
            start();
            Item n = next;
            if (n != null) n.start();
            spool.pipeTo(sink);
        }

        public boolean started() { return started.get(); }
        public SynthSpool spool() { return spool; }

        void cancel() { cancelled = true; spool.fail(new IOException("stopped")); }
    }

    private final Executor workers;
    private Item tail;
    private final java.util.List<Item> live = new java.util.ArrayList<>();

    public Lookahead(Executor workers) { this.workers = workers; }

    public synchronized Item add(Synth synth) {
        Item it = new Item(synth);
        Item prev = tail;
        if (prev != null) prev.next = it;
        tail = it;
        live.removeIf(new java.util.function.Predicate<Item>() { @Override public boolean test(Item x) { return x.spool.isDone() && x.consumerStarted; } });
        live.add(it);
        if (prev == null || prev.consumerStarted) it.start();
        return it;
    }

    /** Stop: every pending or running sentence fails with "stopped" and the chain starts over. */
    public synchronized void cancelAll() {
        for (Item i : live) i.cancel();
        live.clear();
        tail = null;
    }
}
