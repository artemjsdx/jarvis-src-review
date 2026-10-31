package app.jarvis.core.mcp.client;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Result of tools/call: the content blocks flattened to text, capped at {@link McpWire#MAX_RESULT_BYTES}. */
public final class ToolCallResult {
    public final boolean isError;
    public final String text;
    public final boolean truncated;

    ToolCallResult(boolean isError, String text, boolean truncated) {
        this.isError = isError;
        this.text = text;
        this.truncated = truncated;
    }

    static ToolCallResult fromJson(JSONObject result) {
        boolean isError = result.optBoolean("isError", false);
        StringBuilder sb = new StringBuilder();
        JSONArray content = result.optJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                JSONObject b = content.optJSONObject(i);
                if (b == null) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(block(b));
            }
        }
        String text = sb.toString();
        boolean truncated = text.getBytes(StandardCharsets.UTF_8).length > McpWire.MAX_RESULT_BYTES;
        return new ToolCallResult(isError, truncated ? capText(text) : text, truncated);
    }

    /** Keeps the head of the text within the byte cap (minus room for the marker) and says how much was cut. */
    static String capText(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= McpWire.MAX_RESULT_BYTES) return text;
        String head = new String(Arrays.copyOf(bytes, McpWire.MAX_RESULT_BYTES - 200), StandardCharsets.UTF_8);
        return head + "\n…[результат обрезан: " + bytes.length + " байт всего]";
    }

    private static String block(JSONObject b) {
        String type = McpWire.str(b, "type", "");
        if ("text".equals(type)) return McpWire.str(b, "text", "");
        if ("resource".equals(type)) {
            JSONObject r = b.optJSONObject("resource");
            if (r != null && r.has("text")) return McpWire.str(r, "text", "");
            return "[ресурс: " + McpWire.str(r, "uri", "?") + "]";
        }
        if ("resource_link".equals(type)) return "[ссылка на ресурс: " + McpWire.str(b, "uri", "?") + "]";
        if ("image".equals(type) || "audio".equals(type)) {
            return "[" + type + ": " + McpWire.str(b, "mimeType", "?") + ", данные не передаются]";
        }
        return "[блок типа " + type + "]";
    }
}
