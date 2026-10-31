package app.jarvis.core.audio;

import java.util.regex.Pattern;

/**
 * Listening-cue preferences resolved from plain setting values. Every default lives here, so a missing or malformed
 * value never reaches the player: unknown set ids fall back to the default set, volume is clamped, file names that could
 * escape the cues directory are dropped.
 */
public final class CueSettings {
    public static final String ENABLED = "cue.enabled", SET = "cue.set", START_SET = "cue.start.set", STOP_SET = "cue.stop.set",
        START_ON = "cue.start.enabled", STOP_ON = "cue.stop.enabled", ERROR_ON = "cue.error.enabled", VOLUME = "cue.volume",
        HAPTIC = "cue.haptic", CUSTOM_START = "cue.custom.start", CUSTOM_STOP = "cue.custom.stop";

    public static final String DEFAULT_SET = "crystal";
    public static final float DEFAULT_VOLUME = 0.55f, MIN_VOLUME = 0.1f, MAX_VOLUME = 1f;

    private static final Pattern SET_ID = Pattern.compile("[a-z0-9_]{1,32}");
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,48}\\.(ogg|wav|mp3)");

    /** Reads one raw setting value; null when the key is unset. */
    public interface Source { String get(String key); }

    public final boolean enabled, startOn, stopOn, errorOn, haptic;
    /** The default set, also used for the error cue; startSet and stopSet fall back to it when unset. */
    public final String set, startSet, stopSet, customStart, customStop;
    public final float volume;

    private CueSettings(boolean enabled, boolean startOn, boolean stopOn, boolean errorOn, boolean haptic, String set,
                        String startSet, String stopSet, float volume, String customStart, String customStop) {
        this.enabled = enabled;
        this.startOn = startOn;
        this.stopOn = stopOn;
        this.errorOn = errorOn;
        this.haptic = haptic;
        this.set = set;
        this.startSet = startSet;
        this.stopSet = stopSet;
        this.volume = volume;
        this.customStart = customStart;
        this.customStop = customStop;
    }

    public static CueSettings resolve(Source s) {
        String set = setId(s.get(SET));
        return new CueSettings(
            bool(s.get(ENABLED), true),
            bool(s.get(START_ON), true),
            bool(s.get(STOP_ON), true),
            bool(s.get(ERROR_ON), true),
            bool(s.get(HAPTIC), true),
            set,
            kindSet(s.get(START_SET), set),
            kindSet(s.get(STOP_SET), set),
            volume(s.get(VOLUME)),
            fileName(s.get(CUSTOM_START)),
            fileName(s.get(CUSTOM_STOP)));
    }

    static boolean bool(String v, boolean def) {
        if (v == null || v.isEmpty()) return def;
        return v.equals("true") || v.equals("1");
    }

    static String setId(String v) {
        return v != null && SET_ID.matcher(v).matches() ? v : DEFAULT_SET;
    }

    /** A per-kind set id, or the default set when unset or malformed. */
    static String kindSet(String v, String fallback) {
        return v != null && SET_ID.matcher(v).matches() ? v : fallback;
    }

    static float volume(String v) {
        if (v == null || v.isEmpty()) return DEFAULT_VOLUME;
        try {
            float f = Float.parseFloat(v);
            if (Float.isNaN(f)) return DEFAULT_VOLUME;
            return Math.max(MIN_VOLUME, Math.min(MAX_VOLUME, f));
        } catch (NumberFormatException e) {
            return DEFAULT_VOLUME;
        }
    }

    /** A bare file name inside the cues directory, or "" (the built-in set is used). */
    static String fileName(String v) {
        if (v == null || v.isEmpty()) return "";
        return FILE_NAME.matcher(v).matches() ? v : "";
    }
}
