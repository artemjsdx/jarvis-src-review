package app.jarvis.core.agent;

import app.jarvis.core.llm.ToolSpec;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

/** Shared test registries for the tool-group tests. */
final class ToolFixtures {
    private ToolFixtures() { }

    private static final ToolRegistry.Handler NOOP = new ToolRegistry.Handler() {
        @Override public ToolResult run(JSONObject a, ToolContext c) { return ToolResult.ok("сделано"); }
    };

    /** A no-op tool for every built-in tool name the app registers (Termux and ADB included), as the real registry has them. */
    static ToolRegistry fullRegistry() {
        ToolRegistry reg = new ToolRegistry();
        List<String> names = new ArrayList<>(ToolGroups.CORE_BUILTIN);
        for (String g : ToolGroups.ids()) names.addAll(ToolGroups.toolsOf(g));
        for (String n : names) reg.add(ToolSpec.of(n, "Описание " + n), "tools", 5000, false, NOOP);
        return reg;
    }

    /** {@link #fullRegistry()} plus an external MCP tool, which is in no group and so must always be announced. */
    static ToolRegistry withMcp() {
        ToolRegistry reg = fullRegistry();
        reg.register(new Tool() {
            @Override public ToolSpec spec() { return ToolSpec.of("telegram__send_message", "Отправить сообщение в Telegram"); }
            @Override public ToolResult run(JSONObject a, ToolContext c) { return ToolResult.ok("отправлено"); }
            @Override public String category() { return "mcp"; }
        });
        return reg;
    }

    static Set<String> names(List<ToolSpec> specs) {
        Set<String> r = new LinkedHashSet<>();
        for (ToolSpec s : specs) r.add(s.name);
        return r;
    }
}
