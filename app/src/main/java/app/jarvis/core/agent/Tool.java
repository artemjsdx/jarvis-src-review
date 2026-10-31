package app.jarvis.core.agent;

import app.jarvis.core.llm.ToolSpec;
import org.json.JSONObject;

public interface Tool {
    ToolSpec spec();

    /** Runs on a worker thread. Must honor ctx.cancel and report failure through ToolResult, not by faking success. */
    ToolResult run(JSONObject args, ToolContext ctx) throws Exception;

    default long timeoutMs() { return 60_000; }

    /** False for pure reads: safe to repeat after an interrupted run. True means a repeat could duplicate an effect. */
    default boolean sideEffects() { return true; }

    /** Journal category (files, terminal, player, ...). */
    default String category() { return "tools"; }
}
