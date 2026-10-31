package app.jarvis.core.agent;

import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.LlmResult;
import app.jarvis.core.llm.ToolCall;

/** Receives everything an agent run produces. Called on the run's worker thread. */
public interface AgentListener {
    default void onRunStart(String runId) { }
    /** A new model request begins (1-based). */
    default void onStep(int step) { }
    default void onTextDelta(int step, String delta) { }
    default void onReasoningDelta(int step, String delta) { }
    default void onRetry(String reason, long delayMs) { }
    /** A complete assistant or tool message is final; persist it. */
    default void onMessage(ChatMessage m) { }
    default void onToolStart(ToolCall call) { }
    default void onToolProgress(ToolCall call, String text) { }
    default void onToolEnd(ToolCall call, ToolResult result, long durationMs) { }
    default void onUsage(int step, LlmResult r) { }
    default void onFinish(AgentOutcome outcome) { }

    AgentListener NOP = new AgentListener() { };
}
