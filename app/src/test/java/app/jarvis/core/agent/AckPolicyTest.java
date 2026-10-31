package app.jarvis.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.After;
import org.junit.Test;

public class AckPolicyTest {
    private final ScheduledExecutorService sch = Executors.newScheduledThreadPool(1);

    @After public void tearDown() { sch.shutdownNow(); }

    static final class FakePlayer implements AckPolicy.Player {
        final List<String> played = Collections.synchronizedList(new ArrayList<String>());
        volatile int stops;
        volatile long remaining = 900;
        volatile boolean canPlay = true;
        @Override public boolean play(String p) { if (!canPlay) return false; played.add(p); return true; }
        @Override public void stop() { stops++; }
        @Override public long remainingMs() { return remaining; }
    }

    /** Waits (up to 3 s) until the scheduler thread has really played n phrases: a fixed sleep made the test fail on a loaded phone. */
    static void waitFor(FakePlayer p, int n) throws InterruptedException {
        long end = System.currentTimeMillis() + 3000;
        while (p.played.size() < n && System.currentTimeMillis() < end) Thread.sleep(5);
    }

    @Test public void playsOnceAfterTheDelayWhenNothingElseHappened() throws Exception {
        FakePlayer p = new FakePlayer();
        AckPolicy a = new AckPolicy(sch, p, Arrays.asList("Секунду.", "Принял, смотрю."));
        long t0 = System.nanoTime();
        int ta = a.arm(120);
        Thread.sleep(60);
        // only meaningful while less than the delay has really elapsed (a loaded phone can oversleep the 60 ms)
        if ((System.nanoTime() - t0) / 1_000_000L < 100) assertTrue(p.played.isEmpty());
        waitFor(p, 1);
        assertEquals(1, p.played.size());
        assertEquals(p.played.get(0), a.lastPhrase());
        Thread.sleep(300);
        assertEquals("one ack per turn", 1, p.played.size());
    }

    @Test public void realTextBeforeTheDelayCancelsIt() throws Exception {
        FakePlayer p = new FakePlayer();
        AckPolicy a = new AckPolicy(sch, p, Arrays.asList("Секунду."));
        int ta = a.arm(150);
        Thread.sleep(50);
        assertEquals(AckPolicy.TextEffect.TIMER_CANCELLED, a.onRealText(ta));
        Thread.sleep(250);
        assertTrue(p.played.isEmpty());
        assertEquals(0, p.stops);
    }

    @Test public void realTextWhileAckIsAudibleCutsItUnlessAlmostDone() throws Exception {
        FakePlayer p = new FakePlayer();
        AckPolicy a = new AckPolicy(sch, p, Arrays.asList("Секунду.", "Сейчас гляну."));
        int ta = a.arm(0);
        Thread.sleep(100);
        assertEquals(1, p.played.size());
        p.remaining = 800;
        assertEquals(AckPolicy.TextEffect.STOPPED, a.onRealText(ta));
        assertEquals(1, p.stops);

        FakePlayer q = new FakePlayer();
        AckPolicy b = new AckPolicy(sch, q, Arrays.asList("Секунду."));
        int tb = b.arm(0);
        Thread.sleep(100);
        q.remaining = 200;
        assertEquals(AckPolicy.TextEffect.LEFT_TO_FINISH, b.onRealText(tb));
        assertEquals(0, q.stops);
    }

    @Test public void neverTheSamePhraseTwiceInARowAndSinglePhraseSkipsEveryOtherTurn() throws Exception {
        FakePlayer p = new FakePlayer();
        AckPolicy a = new AckPolicy(sch, p, Arrays.asList("А.", "Б.", "В."));
        for (int i = 0; i < 30; i++) { int t = a.arm(0); waitFor(p, i + 1); a.onRealText(t); }
        assertEquals(30, p.played.size());
        for (int i = 1; i < p.played.size(); i++) assertFalse(p.played.get(i).equals(p.played.get(i - 1)));
        FakePlayer q = new FakePlayer();
        AckPolicy b = new AckPolicy(sch, q, Arrays.asList("Секунду."));
        for (int i = 0; i < 4; i++) { int t = b.arm(0); Thread.sleep(40); b.onRealText(t); }   // single phrase: the skip rule depends on turns, not on timing
        assertEquals(2, q.played.size());
    }

    @Test public void disarmAndNewTurnInvalidateOldTimers() throws Exception {
        FakePlayer p = new FakePlayer();
        AckPolicy a = new AckPolicy(sch, p, Arrays.asList("Секунду."));
        int t1 = a.arm(100);
        a.disarm(t1);
        Thread.sleep(250);
        assertTrue(p.played.isEmpty());
        int t2 = a.arm(100);
        int t3 = a.arm(600);          // a newer turn replaces the older timer
        a.disarm(t2);                 // a stale turn finishing late must not kill the newer turn's timer
        assertEquals(AckPolicy.TextEffect.NOTHING_PENDING, a.onRealText(t2));
        Thread.sleep(300);
        assertTrue(p.played.isEmpty());
        Thread.sleep(500);
        assertEquals(1, p.played.size());
    }

    @Test public void unavailablePlayerAndModeParsing() throws Exception {
        FakePlayer p = new FakePlayer();
        p.canPlay = false;
        AckPolicy a = new AckPolicy(sch, p, Arrays.asList("Секунду."));
        int tu = a.arm(0);
        Thread.sleep(100);
        assertEquals(AckPolicy.TextEffect.NOTHING_PENDING, a.onRealText(tu));
        assertEquals("", a.lastPhrase());
        assertEquals(AckPolicy.Mode.VOICE, AckPolicy.Mode.parse(""));
        assertEquals(AckPolicy.Mode.OFF, AckPolicy.Mode.parse("off"));
        assertEquals(AckPolicy.Mode.ALWAYS, AckPolicy.Mode.parse("always"));
    }
}
