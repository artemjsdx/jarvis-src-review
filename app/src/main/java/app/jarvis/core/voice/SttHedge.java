package app.jarvis.core.voice;

import app.jarvis.core.util.CancelToken;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Recognition over an ordered provider chain with hedging. The first provider starts at once; whenever {@code hedgeMs}
 * pass without any answer the next provider is started in parallel. A provider that fails or answers with no text
 * starts the next one immediately. The first non-empty answer wins and the other attempts are cancelled.
 *
 * <p>Recognition has no side effects, so a duplicate request only costs quota. {@code hedgeMs <= 0} starts every
 * provider at once; a very large value makes the chain sequential.</p>
 */
public final class SttHedge {

    /** Called for every failed attempt, so the caller can journal it. Winners and empty answers are not reported. */
    public interface Listener {
        void onFailed(SttProvider provider, VoiceException error);
    }

    public static final class Outcome {
        /** Non-empty answer, or the last empty answer when every provider answered without text. */
        public final SttResult result;
        /** Providers started (the winner is one of them). */
        public final int attempts;

        Outcome(SttResult result, int attempts) {
            this.result = result;
            this.attempts = attempts;
        }
    }

    private static final class Attempt {
        final SttProvider provider;
        final SttResult result;
        final VoiceException error;

        Attempt(SttProvider provider, SttResult result, VoiceException error) {
            this.provider = provider; this.result = result; this.error = error;
        }
    }

    private static final ThreadFactory THREADS = new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "jv-stt-hedge");
            t.setDaemon(true);
            return t;
        }
    };

    private SttHedge() { }

    /** Blocking. Throws the last error when no provider produced an answer, CANCELLED when {@code cancel} fired. */
    public static Outcome run(List<SttProvider> chain, final PcmAudio audio, final SttOptions opts, long hedgeMs,
                              CancelToken cancel, Listener listener) throws VoiceException {
        if (chain.isEmpty()) throw new VoiceException(VoiceException.Kind.CONFIG, "Распознавание речи недоступно", "empty chain", null);
        final Listener log = listener == null ? new Listener() { @Override public void onFailed(SttProvider p, VoiceException e) { } } : listener;
        ExecutorService pool = Executors.newCachedThreadPool(THREADS);
        CompletionService<Attempt> done = new ExecutorCompletionService<>(pool);
        List<CancelToken> tokens = new ArrayList<>();
        int started = 1, pending = 1;
        VoiceException lastError = null;
        SttResult emptyAnswer = null;
        try {
            start(chain.get(0), audio, opts, cancel, tokens, done);
            for (;;) {
                if (cancel.isCancelled()) throw VoiceException.cancelled();
                Future<Attempt> f;
                if (started < chain.size()) {
                    f = done.poll(Math.max(0, hedgeMs), TimeUnit.MILLISECONDS);
                    if (f == null) {
                        start(chain.get(started++), audio, opts, cancel, tokens, done);
                        pending++;
                        continue;
                    }
                } else {
                    if (pending == 0) break;
                    f = done.take();
                }
                pending--;
                Attempt a = await(f);
                if (a.error != null) {
                    lastError = a.error;
                    log.onFailed(a.provider, a.error);
                } else if (a.result.text != null && !a.result.text.trim().isEmpty()) {
                    cancelOthers(tokens);
                    return new Outcome(a.result, started);
                } else {
                    emptyAnswer = a.result;
                }
                if (started < chain.size()) {
                    start(chain.get(started++), audio, opts, cancel, tokens, done);
                    pending++;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw VoiceException.cancelled();
        } finally {
            cancelOthers(tokens);
            pool.shutdownNow();
        }
        if (emptyAnswer != null) return new Outcome(emptyAnswer, started);
        if (lastError != null) throw lastError;
        throw new VoiceException(VoiceException.Kind.CONFIG, "Распознавание речи недоступно", "no attempts", null);
    }

    private static void start(final SttProvider p, final PcmAudio audio, final SttOptions opts, CancelToken cancel,
                              List<CancelToken> tokens, CompletionService<Attempt> done) {
        final CancelToken token = cancel.child();
        tokens.add(token);
        done.submit(new Callable<Attempt>() {
            @Override public Attempt call() {
                try {
                    return new Attempt(p, p.transcribe(audio, opts, token), null);
                } catch (VoiceException e) {
                    return new Attempt(p, null, e);
                } catch (CancelToken.CancelledException e) {
                    return new Attempt(p, null, VoiceException.cancelled());
                } catch (RuntimeException e) {
                    return new Attempt(p, null, new VoiceException(VoiceException.Kind.SERVER,
                        "Ошибка распознавания речи", p.info().id + ": " + e, e));
                }
            }
        });
    }

    private static Attempt await(Future<Attempt> f) throws InterruptedException {
        try {
            return f.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("attempt task cannot throw", e);
        }
    }

    private static void cancelOthers(List<CancelToken> tokens) {
        for (CancelToken t : tokens) t.cancel("hedge");
    }
}
