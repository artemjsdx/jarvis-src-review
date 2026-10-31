package app.jarvis.core.adb;

/** Result of one shell command over Wi-Fi ADB. */
public final class AdbShellResult {
    public final int exitCode;
    public final String stdout;
    public final String stderr;
    public final long durationMs;
    public final boolean timedOut;
    /** True when a stream was capped at 256 KB and the rest was discarded. */
    public final boolean truncated;

    public AdbShellResult(int exitCode, String stdout, String stderr, long durationMs,
                          boolean timedOut, boolean truncated) {
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
        this.durationMs = durationMs;
        this.timedOut = timedOut;
        this.truncated = truncated;
    }

    @Override
    public String toString() {
        return "AdbShellResult{exit=" + exitCode + ", out=" + stdout.length() + "B, err="
            + stderr.length() + "B, " + durationMs + " ms, timedOut=" + timedOut
            + ", truncated=" + truncated + "}";
    }
}
