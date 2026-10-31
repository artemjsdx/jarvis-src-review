package app.jarvis.core.mcp;

/** Decides whether gated tools may run (settings mcp.allowAudio / mcp.allowDestructive). */
public interface McpGate {
    boolean allowAudio();
    boolean allowDestructive();

    McpGate DENY_ALL = new McpGate() {
        @Override public boolean allowAudio() { return false; }
        @Override public boolean allowDestructive() { return false; }
    };
    McpGate ALLOW_ALL = new McpGate() {
        @Override public boolean allowAudio() { return true; }
        @Override public boolean allowDestructive() { return true; }
    };
}
