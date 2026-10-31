package app.jarvis.core.voice;

import java.io.IOException;

/**
 * An {@link AudioSink} that transparently records the stream into a {@link TtsCache} entry while
 * forwarding everything to the real sink (the player). Audio always goes to the delegate first, so a
 * cache problem can never delay or break playback. The cache entry becomes visible only when
 * end() is reached — abort() or a failed stream never commits.
 */
public final class CachingSink implements AudioSink {

    private final TtsCache cache;
    private final String key;
    private final AudioSink delegate;
    private TtsCache.Writer writer;
    private boolean cacheDead;

    public CachingSink(TtsCache cache, String key, AudioSink delegate) {
        this.cache = cache;
        this.key = key;
        this.delegate = delegate;
    }

    @Override public void begin(AudioSpec spec) throws IOException {
        delegate.begin(spec);
        if (cacheDead) return;
        try {
            writer = cache.beginWrite(key, TtsCache.headerJson(spec));
        } catch (IOException e) {
            cacheDead = true; // no usable cache: keep playing
        }
    }

    @Override public void write(byte[] data, int off, int len) throws IOException {
        delegate.write(data, off, len);
        TtsCache.Writer w = writer;
        if (w == null) return;
        try {
            w.write(data, off, len);
        } catch (IOException e) {
            w.abort();
            writer = null;
            cacheDead = true;
        }
    }

    /**
     * Plays out and then commits the cache entry. The entry is committed even if the delegate's
     * end() throws: the recorded byte stream itself is complete; the delegate failure is rethrown.
     */
    @Override public void end() throws IOException {
        Throwable fromDelegate = null;
        try {
            delegate.end();
        } catch (Throwable t) {
            fromDelegate = t;
        }
        TtsCache.Writer w = writer;
        writer = null;
        if (w != null) w.commit();
        if (fromDelegate == null) return;
        if (fromDelegate instanceof IOException) throw (IOException) fromDelegate;
        if (fromDelegate instanceof RuntimeException) throw (RuntimeException) fromDelegate;
        if (fromDelegate instanceof Error) throw (Error) fromDelegate;
        throw new RuntimeException(fromDelegate);
    }

    @Override public void abort() {
        TtsCache.Writer w = writer;
        writer = null;
        cacheDead = true;
        if (w != null) w.abort();
        delegate.abort();
    }
}
