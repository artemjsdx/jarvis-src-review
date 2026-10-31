package app.jarvis.core.mcp.client;

/** Failure of a remote MCP call. {@link #getMessage()} is the user-safe Russian text; {@link #technical} is for the journal. */
public final class McpException extends Exception {
    public enum Kind {
        /** Connection refused, reset, stream cut, timeout while connecting. Safe to retry before the request reached the server. */
        TRANSPORT,
        /** Non-2xx HTTP status; {@link #httpStatus} carries it. */
        HTTP,
        /** The server forgot our session (HTTP 404 with a session id). The client re-initializes once. */
        SESSION_GONE,
        /** JSON-RPC error object returned by the server. */
        RPC,
        /** Malformed JSON-RPC or protocol violation. */
        PROTOCOL,
        /** No response within the call timeout. */
        TIMEOUT,
        /** Cancelled through CancelToken. */
        CANCELLED,
        /** Body or tool result beyond the size cap. */
        TOO_LARGE,
        /** Protocol version or payload the client does not support. */
        UNSUPPORTED
    }

    public final Kind kind;
    public final String technical;
    public final int httpStatus;

    public McpException(Kind kind, String userMessage, String technical) {
        this(kind, userMessage, technical, 0, null);
    }

    public McpException(Kind kind, String userMessage, String technical, int httpStatus, Throwable cause) {
        super(userMessage, cause);
        this.kind = kind;
        this.technical = technical == null ? "" : technical;
        this.httpStatus = httpStatus;
    }

    /** Transient failure worth a retry with backoff. Only idempotent requests (initialize, list) are retried. */
    public boolean isTransient() { return kind == Kind.TRANSPORT || (kind == Kind.HTTP && (httpStatus == 502 || httpStatus == 503 || httpStatus == 504)); }
}
