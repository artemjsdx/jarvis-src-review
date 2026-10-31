package app.jarvis.core.mcp.client;

import app.jarvis.core.util.CancelToken;
import java.util.List;

/** Read-only view of the external servers for the settings UI and the MCP bridge (no dependency on app.jarvis.mcp). */
public interface ExternalMcpInfo {
    enum State { DISABLED, IDLE, CONNECTING, CONNECTED, ERROR }

    final class Status {
        public final String id, name, url, transport, exposure, error;
        public final State state;
        public final int toolCount;
        public final long latencyMs;

        public Status(String id, String name, String url, String transport, String exposure, State state, String error, int toolCount, long latencyMs) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.transport = transport;
            this.exposure = exposure;
            this.state = state;
            this.error = error == null ? "" : error;
            this.toolCount = toolCount;
            this.latencyMs = latencyMs;
        }
    }

    List<Status> servers();

    /** Connects if needed, lists the tools and refreshes the cache. Returns the tool names; throws with a user-safe message. */
    List<String> testServer(String id, CancelToken cancel) throws McpException;
}
