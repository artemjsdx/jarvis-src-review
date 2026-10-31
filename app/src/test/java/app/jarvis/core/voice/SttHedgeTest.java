package app.jarvis.core.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.jarvis.core.util.CancelToken;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Hedged STT over fake providers: timing, winners, empty answers, errors, cancellation. No network. */
public class SttHedgeTest {

    /** Fake provider: answers after delayMs with text or throws an error kind; records whether it was cancelled. */
    static final class Fake implements SttProvider {
        final String id;
        final long delayMs;
        final String text;
        final VoiceException.Kind error;
        final AtomicBoolean cancelledSeen = new AtomicBoolean();
        final AtomicInteger calls = new AtomicInteger();

        Fake(String id, long delayMs, String text, VoiceException.Kind error) {
            this.id = id; this.delayMs = delayMs; this.text = text; this.error = error;
        }

        @Override public ProviderInfo info() {
            return new ProviderInfo(id, id, "", false, false, "", "", false, "", true, "", "");
        }
        @Override public void configure(ProviderConfig cfg) { }
        @Override public boolean isConfigured() { return true; }
        @Override public SttResult transcribe(PcmAudio audio, SttOptions opts, CancelToken cancel) throws VoiceException {
            calls.incrementAndGet();
            if (!cancel.sleep(delayMs)) { cancelledSeen.set(true); throw VoiceException.cancelled(); }
            if (error != null) throw new VoiceException(error, "ошибка " + id, id, null);
            SttResult r = new SttResult();
            r.text = text;
            r.providerId = id;
            return r;
        }
        @Override public ProviderStatus lastStatus() { return ProviderStatus.NEVER; }
        @Override public ProviderStatus check(PcmAudio sample, CancelToken cancel) { return ProviderStatus.NEVER; }
    }

    private static final PcmAudio AUDIO = new PcmAudio(new byte[3200], 16000);

    private static SttHedge.Outcome run(long hedgeMs, CancelToken cancel, List<SttProvider> chain, List<String> failed) throws VoiceException {
        return SttHedge.run(chain, AUDIO, new SttOptions(), hedgeMs, cancel, new SttHedge.Listener() {
            @Override public void onFailed(SttProvider p, VoiceException e) { if (failed != null) failed.add(p.info().id + ":" + e.kind); }
        });
    }

    private static void awaitTrue(AtomicBoolean flag) throws InterruptedException {
        for (int i = 0; i < 100 && !flag.get(); i++) Thread.sleep(10);
        assertTrue("loser was not cancelled", flag.get());
    }

    @Test public void fastFirstWins_secondNeverStarted() throws Exception {
        Fake a = new Fake("a", 20, "привет", null), b = new Fake("b", 20, "другой", null);
        SttHedge.Outcome o = run(3000, new CancelToken(), Arrays.<SttProvider>asList(a, b), null);
        assertEquals("привет", o.result.text);
        assertEquals(1, o.attempts);
        assertEquals(0, b.calls.get());
    }

    @Test public void slowFirst_hedgeStartsSecond_firstAnswerWins_loserCancelled() throws Exception {
        Fake slow = new Fake("slow", 5000, "медленно", null), fast = new Fake("fast", 50, "быстро", null);
        long t0 = System.nanoTime();
        SttHedge.Outcome o = run(200, new CancelToken(), Arrays.<SttProvider>asList(slow, fast), null);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertEquals("быстро", o.result.text);
        assertEquals("fast", o.result.providerId);
        assertEquals(2, o.attempts);
        assertTrue("took " + ms + " ms, expected about hedge + fast answer", ms < 1500);
        awaitTrue(slow.cancelledSeen);
    }

    @Test public void errorFirst_secondStartsAtOnce_notAfterHedge() throws Exception {
        Fake err = new Fake("err", 10, null, VoiceException.Kind.NETWORK), ok = new Fake("ok", 10, "ок", null);
        List<String> failed = new CopyOnWriteArrayList<>();
        long t0 = System.nanoTime();
        SttHedge.Outcome o = run(10_000, new CancelToken(), Arrays.<SttProvider>asList(err, ok), failed);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertEquals("ок", o.result.text);
        assertEquals(Arrays.asList("err:NETWORK"), failed);
        assertTrue("error must not wait for the hedge timer, took " + ms + " ms", ms < 5000);
    }

    @Test public void emptyFirst_waitsForNonEmptySecond() throws Exception {
        Fake empty = new Fake("empty", 10, "   ", null), ok = new Fake("ok", 30, "есть текст", null);
        SttHedge.Outcome o = run(10_000, new CancelToken(), Arrays.<SttProvider>asList(empty, ok), null);
        assertEquals("есть текст", o.result.text);
        assertEquals(2, o.attempts);
    }

    @Test public void allEmpty_returnsEmptyAnswer() throws Exception {
        Fake a = new Fake("a", 10, "", null), b = new Fake("b", 10, "", null);
        SttHedge.Outcome o = run(10_000, new CancelToken(), Arrays.<SttProvider>asList(a, b), null);
        assertEquals("", o.result.text);
        assertEquals(2, o.attempts);
    }

    @Test public void allErrors_throwsLastError() throws Exception {
        Fake a = new Fake("a", 10, null, VoiceException.Kind.NETWORK), b = new Fake("b", 10, null, VoiceException.Kind.RATE_LIMIT);
        List<String> failed = new ArrayList<>();
        try {
            run(10_000, new CancelToken(), Arrays.<SttProvider>asList(a, b), failed);
            fail("expected VoiceException");
        } catch (VoiceException e) {
            assertEquals(VoiceException.Kind.RATE_LIMIT, e.kind);
        }
        assertEquals(Arrays.asList("a:NETWORK", "b:RATE_LIMIT"), failed);
    }

    @Test public void emptyThenError_returnsEmptyNotError() throws Exception {
        Fake empty = new Fake("empty", 10, "", null), err = new Fake("err", 10, null, VoiceException.Kind.SERVER);
        SttHedge.Outcome o = run(10_000, new CancelToken(), Arrays.<SttProvider>asList(empty, err), null);
        assertEquals("", o.result.text);
    }

    @Test public void hedgeZero_startsEveryProviderAtOnce() throws Exception {
        Fake a = new Fake("a", 400, "A", null), b = new Fake("b", 400, "B", null);
        long t0 = System.nanoTime();
        SttHedge.Outcome o = run(0, new CancelToken(), Arrays.<SttProvider>asList(a, b), null);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertEquals(2, o.attempts);
        assertTrue("both should run in parallel, took " + ms + " ms", ms < 900);
    }

    @Test public void parentCancel_abortsAllAttempts() throws Exception {
        final Fake a = new Fake("a", 5000, "A", null), b = new Fake("b", 5000, "B", null);
        final CancelToken cancel = new CancelToken();
        new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(150); } catch (InterruptedException ignored) { }
                cancel.cancel("user");
            }
        }).start();
        long t0 = System.nanoTime();
        try {
            run(100, cancel, Arrays.<SttProvider>asList(a, b), null);
            fail("expected cancellation");
        } catch (VoiceException e) {
            assertEquals(VoiceException.Kind.CANCELLED, e.kind);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue("cancel must be prompt, took " + ms + " ms", ms < 2000);
        awaitTrue(a.cancelledSeen);
        awaitTrue(b.cancelledSeen);
    }

    @Test public void emptyChain_isConfigError() throws Exception {
        try {
            run(100, new CancelToken(), new ArrayList<SttProvider>(), null);
            fail("expected CONFIG");
        } catch (VoiceException e) {
            assertEquals(VoiceException.Kind.CONFIG, e.kind);
        }
    }
}
