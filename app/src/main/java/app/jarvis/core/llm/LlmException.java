package app.jarvis.core.llm;

public final class LlmException extends Exception {
    public enum Kind { CANCELLED, GEO_BLOCK, AUTH, PAYMENT, NOT_FOUND, RATE_LIMIT, CONTEXT_LENGTH, BAD_REQUEST, SERVER, NETWORK, TIMEOUT, TLS, PROTOCOL, CONFIG }

    public final Kind kind;
    public final int status;
    /** Short, Russian, safe to show/speak. */
    public final String userMessage;
    /** Engineer details (status line, body snippet); already redacted by the logging layer. */
    public final String technical;
    public final long retryAfterMs;
    /** Offending request parameter when the provider reports one. */
    public final String param;
    /** True when streamed output had already been delivered (so a transparent retry would duplicate it). */
    public boolean partial;
    /** True when the stream ended with no text, no tool calls and no reasoning (the agent loop asks once more instead of failing). */
    public boolean emptyResponse;

    public LlmException(Kind kind, int status, String userMessage, String technical, long retryAfterMs, String param, Throwable cause) {
        super(userMessage + (technical == null || technical.isEmpty() ? "" : " [" + technical + "]"), cause);
        this.kind = kind;
        this.status = status;
        this.userMessage = userMessage;
        this.technical = technical == null ? "" : technical;
        this.retryAfterMs = retryAfterMs;
        this.param = param;
    }

    public static LlmException cancelled() {
        return new LlmException(Kind.CANCELLED, 0, "Отменено", "", 0, null, null);
    }

    public boolean retryable() {
        switch (kind) {
            case RATE_LIMIT: case SERVER: case NETWORK: case TIMEOUT: return true;
            default: return false;
        }
    }
}
