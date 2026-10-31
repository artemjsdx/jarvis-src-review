package app.jarvis.core.llm;

import app.jarvis.core.util.J;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ChatMessage {
    public static final String SYSTEM = "system", USER = "user", ASSISTANT = "assistant", TOOL = "tool";

    public final String role;
    public final String content;
    public final List<ToolCall> toolCalls;
    public final String toolCallId;
    public final String name;
    /** Model reasoning of this assistant turn. Never persisted; echoed to providers that require it within one run. */
    public final String reasoning;

    private ChatMessage(String role, String content, List<ToolCall> calls, String toolCallId, String name) {
        this(role, content, calls, toolCallId, name, "");
    }

    private ChatMessage(String role, String content, List<ToolCall> calls, String toolCallId, String name, String reasoning) {
        this.reasoning = reasoning == null ? "" : reasoning;
        this.role = role;
        this.content = content == null ? "" : content;
        this.toolCalls = calls == null ? Collections.<ToolCall>emptyList() : Collections.unmodifiableList(new ArrayList<>(calls));
        this.toolCallId = toolCallId;
        this.name = name;
    }

    public static ChatMessage system(String c) { return new ChatMessage(SYSTEM, c, null, null, null); }
    public static ChatMessage user(String c) { return new ChatMessage(USER, c, null, null, null); }
    public static ChatMessage assistant(String c, List<ToolCall> calls) { return new ChatMessage(ASSISTANT, c, calls, null, null); }
    public static ChatMessage assistant(String c, List<ToolCall> calls, String reasoning) {
        return new ChatMessage(ASSISTANT, c, calls, null, null, reasoning);
    }
    public static ChatMessage tool(String callId, String toolName, String c) { return new ChatMessage(TOOL, c, null, callId, toolName); }

    public boolean hasToolCalls() { return !toolCalls.isEmpty(); }

    /** Rough size used by the context budget. */
    public int sizeChars() {
        int n = content.length() + 16;
        for (ToolCall t : toolCalls) n += t.arguments.length() + t.name.length() + 24;
        return n;
    }

    public JSONObject toJson(boolean emptyContentAsString, boolean toolNameField) { return toJson(emptyContentAsString, toolNameField, false); }

    public JSONObject toJson(boolean emptyContentAsString, boolean toolNameField, boolean includeReasoning) {
        JSONObject o = new JSONObject();
        J.put(o, "role", role);
        if (ASSISTANT.equals(role) && hasToolCalls()) {
            if (!content.isEmpty()) J.put(o, "content", content);
            else if (emptyContentAsString) J.put(o, "content", "");
            JSONArray a = new JSONArray();
            for (ToolCall t : toolCalls) a.put(t.toJson());
            J.put(o, "tool_calls", a);
        } else {
            J.put(o, "content", content);
        }
        if (includeReasoning && ASSISTANT.equals(role) && !reasoning.isEmpty()) J.put(o, "reasoning_content", reasoning);
        if (TOOL.equals(role)) {
            J.put(o, "tool_call_id", toolCallId == null ? "" : toolCallId);
            if (toolNameField && name != null) J.put(o, "name", name);
        }
        return o;
    }

    /** Storage format (round-trips through fromJson). */
    public JSONObject toJson() { return toJson(false, true); }

    public static ChatMessage fromJson(JSONObject o) {
        String role = J.str(o, "role");
        List<ToolCall> calls = new ArrayList<>();
        JSONArray a = J.arr(o, "tool_calls");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            if (t != null) calls.add(ToolCall.fromJson(t));
        }
        String id = o.has("tool_call_id") ? J.str(o, "tool_call_id") : null;
        String name = o.has("name") ? J.str(o, "name") : null;
        return new ChatMessage(role, J.str(o, "content"), calls, id, name);
    }
}
