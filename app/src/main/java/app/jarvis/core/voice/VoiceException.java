package app.jarvis.core.voice;

public final class VoiceException extends Exception {
    public enum Kind { CANCELLED, NETWORK, TIMEOUT, AUTH, QUOTA, GEO_BLOCK, RATE_LIMIT, SERVER, PROTOCOL, UNSUPPORTED, CONFIG, NO_AUDIO }

    public final Kind kind;
    /** Short Russian text that is safe to show to the user and to speak. */
    public final String userMessage;
    /** Engineer details for the journal (status line, body snippet). Must not contain secrets. */
    public final String technical;

    public VoiceException(Kind kind, String userMessage, String technical, Throwable cause) {
        super(userMessage + (technical == null || technical.isEmpty() ? "" : " [" + technical + "]"), cause);
        this.kind = kind;
        this.userMessage = userMessage;
        this.technical = technical == null ? "" : technical;
    }

    public VoiceException(Kind kind, String userMessage) { this(kind, userMessage, "", null); }

    public static VoiceException cancelled() { return new VoiceException(Kind.CANCELLED, "Отменено"); }

    public boolean retryable() { return kind == Kind.NETWORK || kind == Kind.TIMEOUT || kind == Kind.SERVER || kind == Kind.RATE_LIMIT; }
}
