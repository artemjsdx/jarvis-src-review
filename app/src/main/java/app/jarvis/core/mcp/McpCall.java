package app.jarvis.core.mcp;

/** Per-invocation context handed to a tool. */
public final class McpCall {
    public final String session;
    public final long startedMs = System.currentTimeMillis();
    private volatile boolean cancelled;

    public McpCall(String session) { this.session = session == null ? "" : session; }

    public boolean isCancelled() { return cancelled || Thread.currentThread().isInterrupted(); }
    void cancel() { cancelled = true; }
}
