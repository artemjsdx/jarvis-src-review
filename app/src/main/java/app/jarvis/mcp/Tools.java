package app.jarvis.mcp;

import app.jarvis.core.mcp.McpRegistry;
import app.jarvis.core.mcp.NetTools;
import app.jarvis.core.mcp.TraceTools;
import app.jarvis.core.trace.TraceStore;

/** Registers every tool group. One line per group keeps merges between branches trivial. */
final class Tools {
    private Tools() { }

    static void registerAll(McpRegistry r, McpBridge b) {
        AppTools.register(r, b);
        JournalTools.register(r, b);
        SettingsTools.register(r, b);
        DbTools.register(r, b);
        ChatTools.register(r, b);
        AgentTools.register(r, b);
        LlmTools.register(r, b);
        SystemTools.register(r, b);
        UiTools.register(r, b);
        NetTools.register(r);
        PerfTools.register(r, b);
        SessionTools.register(r, b);
        TraceTools.register(r, TraceStore::global);
        VoiceTools.register(r, b);
        StubTools.register(r, b);
    }
}
