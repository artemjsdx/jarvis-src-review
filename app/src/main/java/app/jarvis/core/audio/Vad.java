package app.jarvis.core.audio;

/**
 * Cheap voice-activity detection for 16 kHz mono frames (any frame size; call once per analysis frame, e.g. 30 ms).
 *
 * Method: adaptive noise-floor tracking on the frame RMS with hysteresis. The floor falls fast and rises slowly;
 * constant background (noise, tonal hum) is therefore learned during the ~300 ms calibration window and during
 * speech a "sustained sound" watchdog recognizes noise that started mid-stream (steady level, no syllabic peaks)
 * and folds it into the floor. Speech, with its 3–5 Hz syllabic envelope, never satisfies the watchdog. No FFT.
 */
public final class Vad {

    /** Decision for one frame. */
    public static final class Result {
        public final boolean speech;
        public final float rms;   // linear 0..32768
        public final float dbfs;  // 20*log10(rms/32767), clamped at -100
        public final float zcr;   // zero crossings per sample (diagnostics only)

        Result(boolean speech, float rms, float dbfs, float zcr) {
            this.speech = speech; this.rms = rms; this.dbfs = dbfs; this.zcr = zcr;
        }
    }

    // enter speech when rms exceeds the noise floor by this factor (~+10.5 dB)...
    private static final float ENTER_RATIO = 3.4f;
    // ...and stay in speech while above this lower factor (hysteresis)
    private static final float EXIT_RATIO = 1.8f;
    // absolute gate: never trigger below this RMS (~-47 dBFS) no matter what the floor says
    private static final float MIN_ABS = 140f;
    // calibration frames at stream start: the floor only falls, to the quietest observed level
    private static final int WARMUP_FRAMES = 10; // ~300 ms of 30 ms frames
    private static final float FALL_RATE = 0.35f, RISE_RATE = 0.03f;
    // sustained-sound watchdog: while "speech", a level that stays within ±0.7× of its own slow average (and is
    // audible, not a pause) for 20 frames is constant noise, not speech
    private static final float SUSTAINED_RATE = 0.05f;
    private static final float SUSTAINED_BAND = 1.7f;
    private static final int SUSTAINED_FRAMES = 14;
    private static final float SUSTAINED_MIN = 280f; // ~-41 dBFS: below this it is just a pause

    // initial floor ≈ −33 dBFS: calibration can only lower it, so this is the "unknown ambience" guess; louder
    // constant sound that starts mid-stream is caught by the sustained-sound watchdog below
    private float noise = 700f;
    private boolean speech;
    private int frames;
    private float sustainedAvg;
    private int sustained;

    /** Processes one frame; pcm[off..off+n) is 16-bit mono. */
    public Result process(short[] pcm, int off, int n) {
        double sum = 0;
        int crossings = 0;
        int prev = pcm[off] < 0 ? -1 : 1;
        for (int i = 0; i < n; i++) {
            int s = pcm[off + i];
            sum += (double) s * s;
            int sign = s < 0 ? -1 : 1;
            if (sign != prev) { crossings++; prev = sign; }
        }
        float rms = (float) Math.sqrt(sum / Math.max(1, n));
        float zcr = crossings / (float) Math.max(1, n - 1);
        float dbfs = (float) Math.max(-100, 20 * Math.log10(Math.max(rms, 1e-4) / 32767.0));
        frames++;

        if (frames <= WARMUP_FRAMES) { // calibration: floor = quietest level seen so far
            noise = Math.min(noise, Math.max(rms, 1f));
            speech = false;
            return new Result(false, rms, dbfs, zcr);
        }

        if (!speech) {
            noise += (rms - noise) * (rms < noise ? FALL_RATE : RISE_RATE);
        } else {
            sustainedAvg += (rms - sustainedAvg) * SUSTAINED_RATE;
            if (rms < sustainedAvg * SUSTAINED_BAND && rms > SUSTAINED_MIN && rms < noise * ENTER_RATIO * 4) {
                if (++sustained >= SUSTAINED_FRAMES) { // steady tone/noise that outlived speech: adopt it as floor
                    noise = Math.max(noise, sustainedAvg * 0.8f);
                    speech = false;
                    sustained = 0;
                }
            } else {
                sustained = 0;
            }
        }
        if (speech) {
            if (rms < Math.max(MIN_ABS * 0.6f, noise * EXIT_RATIO)) speech = false;
        } else {
            if (rms > Math.max(MIN_ABS, noise * ENTER_RATIO)) { speech = true; sustainedAvg = rms; sustained = 0; }
        }
        return new Result(speech, rms, dbfs, zcr);
    }

    /** Current tracked noise floor (linear RMS units). */
    public float noiseFloor() { return noise; }

    public void reset() { noise = 700f; speech = false; frames = 0; sustainedAvg = 0; sustained = 0; }
}
