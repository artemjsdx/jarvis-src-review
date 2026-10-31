package app.jarvis.core.voice;

/**
 * Rough estimate of the phone's current upload speed, learned from the speech uploads it already makes (no probe traffic).
 * It drives the choice of the upload format: encoding costs time, so on a fast link raw WAV is quicker than any encoder, and only on a slow
 * link does a smaller file pay for its encoding (see {@link AudioPacker#chooseFor}).
 *
 * One sample = request body bytes and the whole request time. The time also contains the provider's recognition and the round trip, which
 * is subtracted as a fixed {@link #BASE_MS}; on a fast link what remains is tiny and the estimate saturates upwards, which is exactly what
 * the decision needs. Samples are smoothed in the log domain so one odd request does not flip the choice, and a link that changes
 * (Wi-Fi to mobile data) is followed within two or three requests.
 */
public final class UplinkMeter {
    private UplinkMeter() { }

    /** Round trip plus recognition time that every request has regardless of the upload (Groq Whisper on a short phrase: about 0.5 s). */
    static final long BASE_MS = 500;
    static final double MIN_BPS = 2_000, MAX_BPS = 5_000_000;
    /** Requests this small tell nothing about the link (their time is almost all recognition). */
    static final long MIN_BYTES = 4_000;

    private static volatile double estimate = -1;

    /** @param bytes size of the request body that carried the audio; @param totalMs time of the whole request */
    public static synchronized void record(long bytes, long totalMs) {
        if (bytes < MIN_BYTES || totalMs <= 0) return;
        double transferMs = Math.max(40, totalMs - BASE_MS);
        double bps = Math.max(MIN_BPS, Math.min(MAX_BPS, bytes * 1000.0 / transferMs));
        estimate = estimate < 0 ? bps : Math.exp(0.5 * Math.log(estimate) + 0.5 * Math.log(bps));
    }

    /** Estimated upload speed in bytes per second, or -1 while nothing has been measured yet. */
    public static double bytesPerSec() { return estimate; }

    /** For tests and for "network changed" events. */
    public static synchronized void reset() { estimate = -1; }
}
