package app.jarvis.core.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

public class LookaheadTest {
    private final ExecutorService pool = Executors.newCachedThreadPool();

    @After public void tearDown() { pool.shutdownNow(); }

    /** A player stand-in: takes the bytes and "plays" them for playMs. Records start/end times of every utterance. */
    static final class FakeSink implements AudioSink {
        final long playMs; final ByteArrayOutputStream got = new ByteArrayOutputStream();
        volatile long beginAt, endAt; volatile AudioSpec spec;
        FakeSink(long playMs) { this.playMs = playMs; }
        @Override public void begin(AudioSpec s) { spec = s; beginAt = System.nanoTime(); }
        @Override public void write(byte[] d, int o, int l) { got.write(d, o, l); }
        @Override public void end() throws IOException {
            try { Thread.sleep(playMs); } catch (InterruptedException e) { throw new IOException("stopped"); }
            endAt = System.nanoTime();
        }
        @Override public void abort() { }
    }

    private static Lookahead.Synth synth(final String name, final long firstByteMs, final List<String> log) {
        return new Lookahead.Synth() {
            @Override public void run(SynthSpool sp) throws Exception {
                log.add("start:" + name);
                Thread.sleep(firstByteMs);
                sp.begin(AudioSpec.mp3());
                sp.write(name.getBytes("UTF-8"), 0, name.length());
                sp.write("!".getBytes("UTF-8"), 0, 1);
                sp.end();
                log.add("done:" + name);
            }
        };
    }

    @Test public void spoolStreamsBytesInOrderAndRethrowsFailure() throws Exception {
        final SynthSpool sp = new SynthSpool();
        FakeSink sink = new FakeSink(0);
        pool.execute(new Runnable() { @Override public void run() {
            try { sp.begin(AudioSpec.mp3()); sp.write("ab".getBytes(), 0, 2); Thread.sleep(50); sp.write("cd".getBytes(), 0, 2); sp.end(); } catch (Exception e) { sp.fail(new IOException(e)); }
        } });
        sp.pipeTo(sink);
        assertEquals("abcd", new String(sink.got.toByteArray()));
        final SynthSpool bad = new SynthSpool();
        pool.execute(new Runnable() { @Override public void run() { try { Thread.sleep(40); } catch (InterruptedException ignored) { } bad.fail(new IOException("провайдер упал")); } });
        try { bad.pipeTo(new FakeSink(0)); fail("must throw"); } catch (IOException e) { assertEquals("провайдер упал", e.getMessage()); }
        final SynthSpool silent = new SynthSpool();
        silent.end();
        try { silent.pipeTo(new FakeSink(0)); fail("an ended stream without a format is an error"); } catch (IOException expected) { }
    }

    @Test public void nextSentenceSynthesisOverlapsWithPlayback() throws Exception {
        final List<String> log = Collections.synchronizedList(new ArrayList<String>());
        Lookahead la = new Lookahead(pool);
        Lookahead.Item a = la.add(synth("A", 300, log));
        Lookahead.Item b = la.add(synth("B", 300, log));
        Lookahead.Item c = la.add(synth("C", 300, log));
        assertTrue("first item starts at once", a.started());
        Thread.sleep(80);
        assertFalse("B must wait for A's turn: bounded to one ahead", b.started());
        FakeSink sa = new FakeSink(500), sb = new FakeSink(500), sc = new FakeSink(500);
        long t0 = System.nanoTime();
        a.produce(sa);
        assertTrue(b.started());
        assertFalse("C is two ahead: not yet", c.started());
        b.produce(sb);
        assertTrue(c.started());
        c.produce(sc);
        long total = (System.nanoTime() - t0) / 1_000_000L;
        long gapAB = (sb.beginAt - sa.endAt) / 1_000_000L;
        long gapBC = (sc.beginAt - sb.endAt) / 1_000_000L;
        assertEquals("A!", new String(sa.got.toByteArray()));
        assertEquals("C!", new String(sc.got.toByteArray()));
        assertTrue("gap A->B " + gapAB, gapAB < 120);      // without look-ahead it is the 300 ms synthesis time
        assertTrue("gap B->C " + gapBC, gapBC < 120);
        assertTrue("total " + total, total < 300 + 3 * 500 + 200);
    }

    @Test public void sentenceAddedWhilePreviousPlaysStartsImmediately() throws Exception {
        final List<String> log = Collections.synchronizedList(new ArrayList<String>());
        Lookahead la = new Lookahead(pool);
        final Lookahead.Item a = la.add(synth("A", 20, log));
        pool.execute(new Runnable() { @Override public void run() { try { a.produce(new FakeSink(600)); } catch (IOException ignored) { } } });
        Thread.sleep(150);
        Lookahead.Item b = la.add(synth("B", 20, log));
        assertTrue("A is being played: B starts at once", b.started());
        Lookahead.Item c = la.add(synth("C", 20, log));
        assertFalse("but never two ahead", c.started());
    }

    @Test public void cancelAllFailsPendingAndRunningItems() throws Exception {
        final List<String> log = Collections.synchronizedList(new ArrayList<String>());
        Lookahead la = new Lookahead(pool);
        Lookahead.Item a = la.add(synth("A", 400, log));
        Lookahead.Item b = la.add(synth("B", 400, log));
        la.cancelAll();
        try { a.produce(new FakeSink(0)); fail("cancelled"); } catch (IOException e) { assertEquals("stopped", e.getMessage()); }
        try { b.produce(new FakeSink(0)); fail("cancelled"); } catch (IOException e) { assertEquals("stopped", e.getMessage()); }
        Lookahead.Item c = la.add(synth("C", 10, log));      // a fresh chain works after a stop
        FakeSink sc = new FakeSink(0);
        c.produce(sc);
        assertEquals("C!", new String(sc.got.toByteArray()));
    }

    @Test public void synthFailureReachesThePlayerAndDoesNotBlockTheNextSentence() throws Exception {
        final List<String> log = Collections.synchronizedList(new ArrayList<String>());
        Lookahead la = new Lookahead(pool);
        Lookahead.Item a = la.add(new Lookahead.Synth() { @Override public void run(SynthSpool s) throws Exception { throw new IOException("Служба не вернула звук"); } });
        Lookahead.Item b = la.add(synth("B", 10, log));
        try { a.produce(new FakeSink(0)); fail("failure expected"); } catch (IOException e) { assertEquals("Служба не вернула звук", e.getMessage()); }
        FakeSink sb = new FakeSink(0);
        b.produce(sb);
        assertEquals("B!", new String(sb.got.toByteArray()));
    }
}
