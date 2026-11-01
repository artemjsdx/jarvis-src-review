package app.jarvis.core.audio;

/** Pure-Java mixing policy: music volume while speech and microphone capture own the duck. */
public final class DuckPolicy {
    private DuckPolicy() { }

    public static float target(float base, boolean speech, boolean capture, boolean speechEnabled, int percent) {
        float v = Math.max(0f, Math.min(1f, base));
        float factor = 1f;
        if (speech && speechEnabled) factor = Math.min(factor, Math.max(0, Math.min(100, percent)) / 100f);
        if (capture) factor = Math.min(factor, 0.22f);
        return v * factor;
    }

    /** Smooth linear interpolation; callers control the real-time easing and duration. */
    public static float between(float from, float to, float t) {
        float p = Math.max(0f, Math.min(1f, t));
        return from + (to - from) * p;
    }
}
