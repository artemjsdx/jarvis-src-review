package app.jarvis.mcp;

import app.jarvis.core.mcp.McpRegistry;
import app.jarvis.core.mcp.McpTools;

/**
 * Catalog entries whose feature is not in the app yet. Each answers NOT_IMPLEMENTED with the name of what it waits for; the owning
 * feature replaces the entry with {@code registry.put(...)} in its own group class, so the catalog stays complete and merges stay trivial.
 */
final class StubTools {
    private StubTools() { }

    static void register(McpRegistry r, McpBridge b) {
    }
}
