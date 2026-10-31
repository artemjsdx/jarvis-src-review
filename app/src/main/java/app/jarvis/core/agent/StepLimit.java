package app.jarvis.core.agent;

/** The agent step setting: 0 means no limit, which still stops at {@link #UNLIMITED_CAP} steps. */
public final class StepLimit {
    public static final int UNLIMITED_CAP = 200;
    public static final int MIN_STEPS = 2;

    private StepLimit() { }

    public static boolean unlimited(int setting) { return setting == 0; }

    public static int effective(int setting) { return unlimited(setting) ? UNLIMITED_CAP : Math.max(MIN_STEPS, setting); }
}
