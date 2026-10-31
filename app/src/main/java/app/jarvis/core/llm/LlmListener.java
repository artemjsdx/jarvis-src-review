package app.jarvis.core.llm;

public interface LlmListener {
    default void onTextDelta(String delta) { }
    default void onReasoningDelta(String delta) { }
    default void onToolCallDelta(int index, String id, String name, String argsFragment) { }
    default void onRetry(int attempt, String reason, long delayMs) { }
    LlmListener NOP = new LlmListener() { };
}
