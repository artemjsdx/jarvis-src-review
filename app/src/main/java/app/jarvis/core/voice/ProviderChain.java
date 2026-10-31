package app.jarvis.core.voice;

import app.jarvis.core.util.CancelToken;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Serves one synthesis request through a list of providers, in the user's order.
 *
 * Fallback policy: the next provider is tried only when the previous one failed with a
 * retryable (NETWORK/TIMEOUT/SERVER/RATE_LIMIT), QUOTA, GEO_BLOCK or AUTH error.
 * CANCELLED always aborts the whole chain; PROTOCOL/UNSUPPORTED/CONFIG/NO_AUDIO fail immediately
 * (they indicate a systematic problem, not a service hiccup). Providers implementing
 * {@link SlowProvider} are never used here at all — studio synthesis is requested directly.
 *
 * The chain creates a fresh sink per attempt via {@link SinkFactory} (an AudioSink is single-use
 * per contract) and measures time from the call to the first audio byte of the successful attempt.
 */
public final class ProviderChain {

    /** A fresh AudioSink per attempt (an AudioSink must not be reused after begin). */
    public interface SinkFactory {
        AudioSink create() throws IOException;
    }

    /** Optional observer for journaling; all methods are no-ops by default. */
    public interface Listener {
        void onAttempt(TtsProvider provider, VoiceException error);
        void onSkipSlow(TtsProvider provider);
    }

    /** Which provider served the audio and how fast it started. */
    public static final class Result {
        public final TtsProvider provider;
        /** Provider id (info().id) of the winner. */
        public final String providerId;
        /** ms from chain start to the first audio byte; -1 when the winner wrote nothing (cannot happen: NO_AUDIO). */
        public final long firstAudioMs;
        public final long totalMs;

        Result(TtsProvider provider, String providerId, long firstAudioMs, long totalMs) {
            this.provider = provider;
            this.providerId = providerId;
            this.firstAudioMs = firstAudioMs;
            this.totalMs = totalMs;
        }
    }

    private static final Set<VoiceException.Kind> FALLBACK = EnumSet.of(
            VoiceException.Kind.NETWORK, VoiceException.Kind.TIMEOUT, VoiceException.Kind.SERVER,
            VoiceException.Kind.RATE_LIMIT, VoiceException.Kind.QUOTA, VoiceException.Kind.GEO_BLOCK,
            VoiceException.Kind.AUTH);

    private final List<TtsProvider> providers;
    private volatile Listener listener = new Listener() {
        @Override public void onAttempt(TtsProvider p, VoiceException e) { }
        @Override public void onSkipSlow(TtsProvider p) { }
    };

    public ProviderChain(List<TtsProvider> providers) {
        this.providers = new ArrayList<>(providers);
    }

    public ProviderChain(TtsProvider... providers) {
        this(Arrays.asList(providers));
    }

    public void setListener(Listener l) {
        if (l != null) listener = l;
    }

    /** Blocking; throws the last (or fatal) error when nobody could serve the request. */
    public Result synthesize(TtsRequest req, SinkFactory sinks, CancelToken cancel) throws VoiceException {
        long start = System.currentTimeMillis();
        VoiceException last = null;
        for (TtsProvider p : providers) {
            if (cancel != null && cancel.isCancelled()) throw VoiceException.cancelled();
            if (p instanceof SlowProvider) {
                listener.onSkipSlow(p);
                continue;
            }
            MeasuredSink s;
            try {
                s = new MeasuredSink(sinks.create());
            } catch (IOException e) {
                throw new VoiceException(VoiceException.Kind.CONFIG, "Не удалось подготовить воспроизведение", e.toString(), e);
            }
            try {
                p.synthesize(req, s, cancel);
                if (s.totalBytes == 0) {
                    throw new VoiceException(VoiceException.Kind.NO_AUDIO,
                            "Провайдер озвучки не вернул звук", p.info().id + ": 0 bytes", null);
                }
                long now = System.currentTimeMillis();
                return new Result(p, p.info().id, s.firstAudioAt - start, now - start);
            } catch (VoiceException e) {
                s.abort(); // providers abort on failure themselves; this is a safety net and idempotent
                listener.onAttempt(p, e);
                if (e.kind == VoiceException.Kind.CANCELLED || !FALLBACK.contains(e.kind)) throw e;
                last = e;
            } catch (CancelToken.CancelledException e) {
                s.abort();
                throw VoiceException.cancelled();
            } catch (RuntimeException e) {
                // A provider bug should not kill the whole chain; treat like a server error.
                VoiceException ve = new VoiceException(VoiceException.Kind.SERVER,
                        "Ошибка синтеза речи", p.info().id + ": " + e, e);
                s.abort();
                listener.onAttempt(p, ve);
                last = ve;
            }
        }
        if (last != null) throw last;
        throw new VoiceException(VoiceException.Kind.CONFIG, "Нет активных провайдеров озвучки", "empty/slow-only chain", null);
    }

    /** Forwards to the real sink and stamps the time of the first non-empty write. */
    private static final class MeasuredSink implements AudioSink {
        final AudioSink target;
        long totalBytes;
        long firstAudioAt;

        MeasuredSink(AudioSink target) { this.target = target; }

        @Override public void begin(AudioSpec spec) throws IOException { target.begin(spec); }

        @Override public void write(byte[] data, int off, int len) throws IOException {
            if (totalBytes == 0 && len > 0) firstAudioAt = System.currentTimeMillis();
            totalBytes += len;
            target.write(data, off, len);
        }

        @Override public void end() throws IOException { target.end(); }

        @Override public void abort() { target.abort(); }
    }
}
