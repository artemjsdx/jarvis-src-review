package app.jarvis.core.audio;

/**
 * When the microphone must ignore its input because a cue is sounding in the room. The cue is played through the
 * speaker, so the frames captured while it plays (and the tail that follows) are dropped before they reach the segmenter.
 */
public final class CueTiming {
    /** Extra milliseconds dropped after the clip ends: the room reverberates and the mic buffer lags behind. */
    public static final long TAIL_MS = 120;

    private CueTiming() { }

    /** The time (ms, same clock as nowMs) until which captured audio is discarded; nowMs itself when no cue plays. */
    public static long dropUntil(long nowMs, long cueMs) {
        if (cueMs <= 0) return nowMs;
        return nowMs + cueMs + TAIL_MS;
    }
}
