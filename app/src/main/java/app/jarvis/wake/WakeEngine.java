package app.jarvis.wake;

import java.util.Locale;

/**
 * Decides how the wake word is screened. {@link Kind#LOCAL} runs the offline Vosk gate on the device; {@link Kind#CLOUD}
 * sends each utterance to the STT chain. Local is the default: audio stays on the phone, and the cloud is only asked to
 * transcribe a command after a local wake hit.
 *
 * <p>Setting {@code wake.engine}: {@code auto} (default) uses local when its model is installed and loads, else cloud;
 * {@code local} never uses the cloud for screening; {@code cloud} always does.</p>
 */
public final class WakeEngine {
    public enum Kind { LOCAL, CLOUD }

    public static final String AUTO = "auto", LOCAL = "local", CLOUD = "cloud";

    private WakeEngine() { }

    /** {@code localReady}: the offline model is installed and its native library loads. */
    public static Kind pick(String setting, boolean localReady) {
        String s = setting == null ? AUTO : setting.trim().toLowerCase(Locale.ROOT);
        if (CLOUD.equals(s)) return Kind.CLOUD;
        if (LOCAL.equals(s)) return Kind.LOCAL;
        return localReady ? Kind.LOCAL : Kind.CLOUD;
    }

    /** Only {@code auto} may fall back to the cloud when a local detection fails; an explicit {@code local} stays offline. */
    public static boolean cloudFallbackAllowed(String setting) {
        String s = setting == null ? AUTO : setting.trim().toLowerCase(Locale.ROOT);
        return !LOCAL.equals(s) && !CLOUD.equals(s);
    }

    /**
     * Only {@code auto} asks the cloud to confirm a local wake hit. The local model has a high false-trigger rate on
     * ordinary speech (measured on ART, see docs/SPEED.md section 9), so under {@code auto} a hit counts only when the
     * transcript of the same utterance contains the wake word too.
     */
    public static boolean verifyLocalHits(String setting) {
        return cloudFallbackAllowed(setting);
    }
}
