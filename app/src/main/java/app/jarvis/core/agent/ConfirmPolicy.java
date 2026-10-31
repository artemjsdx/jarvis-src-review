package app.jarvis.core.agent;

import app.jarvis.core.net.LatencyBook;

/** Chooses how a finished direct command is confirmed: instantly from the audio cache (fast) or phrased by the model from the real result (live). */
public final class ConfirmPolicy {
    private ConfirmPolicy() { }

    public enum Mode {
        FAST, LIVE, AUTO;
        public static Mode parse(String s) {
            if ("fast".equalsIgnoreCase(s)) return FAST;
            if ("live".equalsIgnoreCase(s)) return LIVE;
            return AUTO;
        }
    }

    /** Median time to the first model token over the last 5 steps above this means "the model is slow now". */
    public static final long SLOW_TTFT_MS = 2000;
    /** Median time to the response headers above this is read as a weak network (the probe the app already gets for free from every LLM call). */
    public static final long WEAK_HEADERS_MS = 1500;

    public static boolean useFast(Mode mode, LatencyBook book, String providerId) {
        if (mode == Mode.FAST) return true;
        if (mode == Mode.LIVE) return false;
        return book != null && (book.medianAbove("llm:" + providerId, 5, SLOW_TTFT_MS, 2) || book.medianAbove("llm.headers:" + providerId, 5, WEAK_HEADERS_MS, 3));
    }
}
