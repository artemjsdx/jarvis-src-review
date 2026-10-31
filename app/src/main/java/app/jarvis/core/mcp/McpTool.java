package app.jarvis.core.mcp;

import java.util.Collections;
import java.util.Set;
import org.json.JSONObject;

/** One MCP tool. {@code call} runs on a worker thread under the per-tool timeout and must never fake success. */
public interface McpTool {
    /** Needs {@code mcp.allowAudio}: plays sound or opens the microphone. */
    int AUDIO = 1;
    /** Needs {@code mcp.allowDestructive}: reveals secrets or deletes/overwrites user data. */
    int DESTRUCTIVE = 2;
    /** Pure read, safe to repeat. */
    int READ_ONLY = 4;

    String name();
    String description();
    /** JSON Schema of the arguments (type=object, properties, required). */
    JSONObject inputSchema();
    McpResult call(JSONObject args, McpCall call) throws Exception;

    default long timeoutMs() { return 30_000; }
    default int flags() { return 0; }
    /** Argument names whose values are masked in the audit trail. */
    default Set<String> sensitiveArgs() { return Collections.emptySet(); }
}
