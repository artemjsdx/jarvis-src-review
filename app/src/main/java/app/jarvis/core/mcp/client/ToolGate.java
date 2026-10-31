package app.jarvis.core.mcp.client;

import app.jarvis.core.util.CancelToken;

/** Asks the user before a remote tool that may change something runs. Headless callers use {@link #DENY}. */
public interface ToolGate {
    ToolGate DENY = new ToolGate() {
        @Override public boolean confirm(String server, String tool, String argKeys, CancelToken cancel) { return false; }
    };

    /** Blocks the calling worker thread until the user answers; argKeys lists argument names only, never values. */
    boolean confirm(String server, String tool, String argKeys, CancelToken cancel);

    /** Runs for every write-capable remote tool, even when confirmation is off; false refuses the call (rate limits). */
    default boolean allowWrite(String serverId, String tool) { return true; }
}
