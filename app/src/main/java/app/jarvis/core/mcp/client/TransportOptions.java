package app.jarvis.core.mcp.client;

/** Timeouts and retry policy shared by the transports. Tests shrink the backoff to keep runs fast. */
public final class TransportOptions {
    public int connectMs = 10_000;
    public int readMs = 60_000;
    public long backoffBaseMs = 300;
    /** Consecutive failed attempts before a stream or a connect is given up. */
    public int maxAttempts = 5;

    public long backoff(int attempt) {
        long v = backoffBaseMs << Math.min(Math.max(attempt, 0), 6);
        return Math.min(v, 4_000L);
    }
}
