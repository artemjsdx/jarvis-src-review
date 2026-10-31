package app.jarvis.core.audio;

/** One-line summaries for the sound and microphone settings. The Android side passes the labels in. */
public final class CueSummary {
    private CueSummary() { }

    /** "Хрусталь · вкл", "начало: Хрусталь · конец: Капля", or "выключены" when the master switch is off. */
    public static String sounds(boolean enabled, String startLabel, String stopLabel) {
        if (!enabled) return "выключены";
        if (startLabel.equals(stopLabel)) return startLabel + " · вкл";
        return "начало: " + startLabel + " · конец: " + stopLabel;
    }

    public static String percent(float volume) {
        return Math.round(volume * 100) + " %";
    }

    /** Another app's recording or a call wins over the normal state, because it is why the assistant is silent. */
    public static String mic(boolean listening, String problem) {
        if (problem != null && !problem.isEmpty()) return problem;
        return listening ? "Джарвис слушает, микрофон у него" : "Джарвис не слушает, микрофон свободен";
    }
}
