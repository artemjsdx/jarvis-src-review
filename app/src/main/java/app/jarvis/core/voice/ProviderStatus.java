package app.jarvis.core.voice;

/** Result of the last real check (an actual synthesis/transcription), not a ping. */
public final class ProviderStatus {
    public static final ProviderStatus NEVER = new ProviderStatus(0, false, "", -1, -1, "не проверялся");

    public final long checkedAtMs;
    public final boolean ok;
    /** User-facing error text of the last failure ("" when ok). */
    public final String lastError;
    /** Time from request start to first audio byte / first transcript; -1 unknown. */
    public final long firstAudioMs, totalMs;
    public final String detail;

    public ProviderStatus(long checkedAtMs, boolean ok, String lastError, long firstAudioMs, long totalMs, String detail) {
        this.checkedAtMs = checkedAtMs; this.ok = ok; this.lastError = lastError == null ? "" : lastError;
        this.firstAudioMs = firstAudioMs; this.totalMs = totalMs; this.detail = detail == null ? "" : detail;
    }
}
