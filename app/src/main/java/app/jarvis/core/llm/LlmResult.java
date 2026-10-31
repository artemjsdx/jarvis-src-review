package app.jarvis.core.llm;

import java.util.ArrayList;
import java.util.List;

public final class LlmResult {
    public String content = "";
    public String reasoning = "";
    public List<ToolCall> toolCalls = new ArrayList<>();
    public String finishReason = "";
    public String model = "";
    public String responseId = "";
    public String warning = "";
    public int promptTokens = -1, completionTokens = -1, reasoningTokens = -1, cachedTokens = -1;
    /** Wall-clock milestones (ms since the request started); -1 = never happened. */
    public long headersMs = -1, firstDeltaMs = -1, totalMs = -1;
    public int httpStatus;
    public int attempts = 1;
    /** Parameters the client had to drop after the provider rejected them. */
    public List<String> droppedParams = new ArrayList<>();
}
