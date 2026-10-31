package app.jarvis.core.mcp;

import app.jarvis.core.util.J;
import org.json.JSONArray;
import org.json.JSONObject;

/** Result of tools/call: a list of content blocks (text / image) and the isError flag. */
public final class McpResult {
    public boolean isError;
    private final JSONArray content = new JSONArray();

    public static McpResult json(JSONObject o) { return new McpResult().text(o.toString()); }
    public static McpResult json(JSONArray a) { return new McpResult().text(a.toString()); }
    public static McpResult text(String s) { return new McpResult().addText(s); }

    public static McpResult image(String base64, String mime) { return new McpResult().addImage(base64, mime); }

    /** Error as the spec requires: isError=true and {"error":{"code","message","technical"}}. */
    public static McpResult error(String code, String message, String technical) {
        McpResult r = new McpResult();
        r.isError = true;
        JSONObject e = new JSONObject();
        J.put(e, "code", code);
        J.put(e, "message", message == null ? "" : message);
        J.put(e, "technical", technical == null ? "" : technical);
        return r.addText(J.put(new JSONObject(), "error", e).toString());
    }

    public static McpResult error(String code, String message) { return error(code, message, ""); }

    public McpResult addText(String s) {
        content.put(J.put(J.put(new JSONObject(), "type", "text"), "text", s == null ? "" : s));
        return this;
    }

    public McpResult addImage(String base64, String mime) {
        JSONObject o = J.put(new JSONObject(), "type", "image");
        J.put(o, "data", base64);
        J.put(o, "mimeType", mime);
        content.put(o);
        return this;
    }

    public JSONArray content() { return content; }

    /** Text of the first text block ("" if none); used by tests and the audit trail. */
    public String firstText() {
        for (int i = 0; i < content.length(); i++) {
            JSONObject c = content.optJSONObject(i);
            if (c != null && "text".equals(J.str(c, "type"))) return J.str(c, "text");
        }
        return "";
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        J.put(o, "content", content);
        J.put(o, "isError", isError);
        return o;
    }
}
