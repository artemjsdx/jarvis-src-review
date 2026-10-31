package app.jarvis.core.llm;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

public final class LlmRequest {
    public String model = "";
    public List<ChatMessage> messages = new ArrayList<>();
    public List<ToolSpec> tools = new ArrayList<>();
    public Double temperature;
    public Integer maxTokens;
    /** "" = provider default; "off", "instant", "low", "medium", "high" (mapped per provider style; ignored if unsupported). */
    public String reasoning = "";
    /** null = provider default ("auto"); "none" | "required" | "auto". */
    public String toolChoice;
    public boolean stream = true;
    /** Ask the provider for its low-latency mode (Inception "realtime"). */
    public boolean realtime;
    public JSONObject extra;
    /** Index in {@link #messages} of the last message of the stable prefix (the one before the current user turn); -1 = none. Used for provider prompt-cache breakpoints. */
    public int cacheBreakpoint = -1;

    /** Shallow copy with another model id (the message and tool lists are shared: they are never mutated after the request is built). */
    public LlmRequest withModel(String newModel) {
        LlmRequest r = new LlmRequest();
        r.model = newModel;
        r.messages = messages; r.tools = tools; r.temperature = temperature; r.maxTokens = maxTokens; r.reasoning = reasoning;
        r.toolChoice = toolChoice; r.stream = stream; r.realtime = realtime; r.extra = extra; r.cacheBreakpoint = cacheBreakpoint;
        return r;
    }
}
