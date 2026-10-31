package app.jarvis.core.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.jarvis.core.util.CancelToken;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Fake-provider tests of fallback policy, slow skipping, and first-audio measurement. */
public class ProviderChainTest {

    /** Fake TTS: throws the configured error, or streams N bytes (after an optional delay). */
    private static class FakeTts implements TtsProvider {
        final String id;
        final VoiceException.Kind failWith; // null = success
        final int bytesToWrite;
        final long delayMs;
        final AtomicInteger calls = new AtomicInteger();
        VoiceException lastSeen;

        FakeTts(String id, VoiceException.Kind failWith) { this(id, failWith, 8, 0); }

        FakeTts(String id, VoiceException.Kind failWith, int bytesToWrite, long delayMs) {
            this.id = id;
            this.failWith = failWith;
            this.bytesToWrite = bytesToWrite;
            this.delayMs = delayMs;
        }

        @Override public ProviderInfo info() {
            return new ProviderInfo(id, id, "", false, false, "", "", false, "", true, "", "");
        }

        @Override public void configure(ProviderConfig cfg) { }

        @Override public boolean isConfigured() { return true; }

        @Override public List<Voice> listVoices(CancelToken cancel) { return new ArrayList<>(); }

        @Override public void synthesize(TtsRequest req, AudioSink sink, CancelToken cancel) throws VoiceException {
            calls.incrementAndGet();
            if (failWith != null) {
                throw new VoiceException(failWith, "фейк: " + failWith, "fake:" + failWith, null);
            }
            if (delayMs > 0) cancel.sleep(delayMs);
            try {
                sink.begin(AudioSpec.mp3());
                sink.write(new byte[bytesToWrite], 0, bytesToWrite);
                sink.end();
            } catch (IOException e) {
                throw new VoiceException(VoiceException.Kind.NETWORK, "синк сломан", e.toString(), e);
            }
        }

        @Override public ProviderStatus lastStatus() { return ProviderStatus.NEVER; }

        @Override public ProviderStatus check(String phrase, String voiceId, CancelToken cancel) { return ProviderStatus.NEVER; }

        @Override public String quotaText(CancelToken cancel) { return null; }
    }

    private static final class SlowTts extends FakeTts implements SlowProvider {
        SlowTts() { super("f5", null); }
    }

    /** Collecting sink that can be created fresh per attempt (mimics the player layer). */
    private static final class Player implements ProviderChain.SinkFactory {
        final List<ByteArrayOutputStream> received = new ArrayList<>();
        final List<AudioSink> sinks = new ArrayList<>();

        @Override public AudioSink create() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            received.add(out);
            AudioSink s = new AudioSink() {
                @Override public void begin(AudioSpec spec) { }
                @Override public void write(byte[] data, int off, int len) { out.write(data, off, len); }
                @Override public void end() { }
                @Override public void abort() { }
            };
            sinks.add(s);
            return s;
        }
    }

    private static TtsRequest req() {
        TtsRequest r = new TtsRequest();
        r.text = "привет";
        return r;
    }

    @Test
    public void servesThroughFirstWorkingProvider() throws VoiceException {
        Player p = new Player();
        FakeTts a = new FakeTts("a", VoiceException.Kind.NETWORK);
        FakeTts b = new FakeTts("b", null);
        ProviderChain.Result r = new ProviderChain(Arrays.<TtsProvider>asList(a, b)).synthesize(req(), p, new CancelToken());
        assertEquals("b", r.providerId);
        assertEquals(1, b.calls.get());
        assertEquals(1, a.calls.get());
        assertEquals("one fresh sink per attempt", 2, p.received.size());
        assertEquals(8, p.received.get(1).size());
        assertTrue(r.firstAudioMs >= 0 && r.firstAudioMs <= r.totalMs);
    }

    @Test
    public void fallsBackOnRetryableQuotaGeoAuth() throws Exception {
        VoiceException.Kind[] fallbackKinds = {
                VoiceException.Kind.NETWORK, VoiceException.Kind.TIMEOUT, VoiceException.Kind.SERVER,
                VoiceException.Kind.RATE_LIMIT, VoiceException.Kind.QUOTA, VoiceException.Kind.GEO_BLOCK,
                VoiceException.Kind.AUTH };
        for (VoiceException.Kind k : fallbackKinds) {
            Player p = new Player();
            FakeTts bad = new FakeTts("bad", k);
            FakeTts good = new FakeTts("good", null);
            ProviderChain.Result r = new ProviderChain(Arrays.<TtsProvider>asList(bad, good)).synthesize(req(), p, new CancelToken());
            assertEquals("kind " + k, "good", r.providerId);
        }
    }

    @Test
    public void neverFallsBackOnFatalKinds() {
        VoiceException.Kind[] fatal = {
                VoiceException.Kind.CANCELLED, VoiceException.Kind.PROTOCOL, VoiceException.Kind.UNSUPPORTED,
                VoiceException.Kind.CONFIG, VoiceException.Kind.NO_AUDIO };
        for (VoiceException.Kind k : fatal) {
            Player p = new Player();
            FakeTts bad = new FakeTts("bad", k);
            FakeTts good = new FakeTts("good", null);
            try {
                new ProviderChain(Arrays.<TtsProvider>asList(bad, good)).synthesize(req(), p, new CancelToken());
                fail("kind " + k + " must propagate");
            } catch (VoiceException e) {
                assertEquals("kind " + k, k, e.kind);
            }
            assertEquals("kind " + k + ": next provider must not be called", 0, good.calls.get());
        }
    }

    @Test
    public void cancelledTokenAbortsEverything() {
        CancelToken token = new CancelToken();
        token.cancel("user");
        Player p = new Player();
        FakeTts a = new FakeTts("a", null);
        try {
            new ProviderChain(Arrays.<TtsProvider>asList(a)).synthesize(req(), p, token);
            fail();
        } catch (VoiceException e) {
            assertEquals(VoiceException.Kind.CANCELLED, e.kind);
        }
        assertEquals(0, a.calls.get());
    }

    @Test
    public void slowProvidersAreNeverUsed() throws VoiceException {
        Player p = new Player();
        SlowTts slow = new SlowTts();
        FakeTts good = new FakeTts("good", null);
        ProviderChain.Result r = new ProviderChain(Arrays.<TtsProvider>asList(slow, good)).synthesize(req(), p, new CancelToken());
        assertEquals("good", r.providerId);
        assertEquals(0, slow.calls.get());

        // a slow provider after a failing one is skipped, not tried
        FakeTts bad = new FakeTts("bad", VoiceException.Kind.NETWORK);
        Player p2 = new Player();
        try {
            new ProviderChain(Arrays.<TtsProvider>asList(bad, new SlowTts())).synthesize(req(), p2, new CancelToken());
            fail("nothing usable: last error must propagate");
        } catch (VoiceException e) {
            assertEquals(VoiceException.Kind.NETWORK, e.kind);
        }
    }

    @Test
    public void allFailingPropagatesLastError() {
        Player p = new Player();
        FakeTts a = new FakeTts("a", VoiceException.Kind.QUOTA);
        FakeTts b = new FakeTts("b", VoiceException.Kind.TIMEOUT);
        try {
            new ProviderChain(Arrays.<TtsProvider>asList(a, b)).synthesize(req(), p, new CancelToken());
            fail();
        } catch (VoiceException e) {
            assertEquals(VoiceException.Kind.TIMEOUT, e.kind);
        }
        assertEquals(1, a.calls.get());
        assertEquals(1, b.calls.get());
        assertEquals(2, p.received.size()); // fresh sink per attempt
    }

    @Test
    public void emptyOrSlowOnlyChainFailsWithConfig() {
        Player p = new Player();
        try {
            new ProviderChain(new ArrayList<TtsProvider>()).synthesize(req(), p, new CancelToken());
            fail();
        } catch (VoiceException e) {
            assertEquals(VoiceException.Kind.CONFIG, e.kind);
        }
        try {
            new ProviderChain(Arrays.<TtsProvider>asList(new SlowTts())).synthesize(req(), p, new CancelToken());
            fail();
        } catch (VoiceException e) {
            assertEquals(VoiceException.Kind.CONFIG, e.kind);
        }
    }

    @Test
    public void zeroByteSuccessIsNoAudioAndNoFallback() {
        Player p = new Player();
        FakeTts silent = new FakeTts("silent", null, 0, 0);
        FakeTts good = new FakeTts("good", null);
        try {
            new ProviderChain(Arrays.<TtsProvider>asList(silent, good)).synthesize(req(), p, new CancelToken());
            fail();
        } catch (VoiceException e) {
            assertEquals(VoiceException.Kind.NO_AUDIO, e.kind);
        }
        assertEquals(0, good.calls.get());
    }

    @Test
    public void measuresTimeToFirstAudio() throws VoiceException {
        Player p = new Player();
        FakeTts slowStart = new FakeTts("slowstart", null, 4, 60);
        ProviderChain.Result r = new ProviderChain(Arrays.<TtsProvider>asList(slowStart)).synthesize(req(), p, new CancelToken());
        assertEquals("slowstart", r.providerId);
        assertTrue("firstAudioMs=" + r.firstAudioMs, r.firstAudioMs >= 50);
        assertTrue(r.totalMs >= r.firstAudioMs);
    }

    @Test
    public void listenerSeesAttemptsAndSkips() throws VoiceException {
        final List<String> log = new ArrayList<>();
        FakeTts a = new FakeTts("a", VoiceException.Kind.QUOTA);
        SlowTts slow = new SlowTts();
        FakeTts b = new FakeTts("b", null);
        ProviderChain chain = new ProviderChain(Arrays.<TtsProvider>asList(a, slow, b));
        chain.setListener(new ProviderChain.Listener() {
            @Override public void onAttempt(TtsProvider p, VoiceException error) { log.add("attempt:" + p.info().id); }
            @Override public void onSkipSlow(TtsProvider p) { log.add("skip:" + p.info().id); }
        });
        ProviderChain.Result r = chain.synthesize(req(), new Player(), new CancelToken());
        assertEquals("b", r.providerId);
        assertNotNull(log.toString(), log.toString());
        assertTrue(log.contains("attempt:a"));
        assertTrue(log.contains("skip:f5"));
    }

    @Test
    public void providerCrashIsWrappedAndChainContinues() throws VoiceException {
        Player p = new Player();
        TtsProvider crasher = new FakeTts("crasher", null) {
            @Override public void synthesize(TtsRequest req, AudioSink sink, CancelToken cancel) {
                calls.incrementAndGet();
                throw new IllegalStateException("NPE-style bug");
            }
        };
        FakeTts good = new FakeTts("good", null);
        ProviderChain.Result r = new ProviderChain(Arrays.<TtsProvider>asList(crasher, good)).synthesize(req(), p, new CancelToken());
        assertEquals("good", r.providerId);
        assertEquals(1, good.calls.get());
    }
}
