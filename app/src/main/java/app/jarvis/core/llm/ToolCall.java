package app.jarvis.core.llm;

import app.jarvis.core.util.J;
import org.json.JSONObject;

public final class ToolCall {
    public final String id;
    public final String name;
    /** Raw JSON text exactly as produced by the model (may be invalid if the stream was truncated). */
    public final String arguments;

    public ToolCall(String id, String name, String arguments) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.arguments = arguments == null || arguments.trim().isEmpty() ? "{}" : arguments;
    }

    /** Parsed arguments, or null when the model produced invalid JSON. */
    public JSONObject args() { return J.parseOrNull(arguments); }

    public JSONObject toJson() {
        JSONObject fn = new JSONObject();
        J.put(fn, "name", name);
        J.put(fn, "arguments", arguments);
        JSONObject o = new JSONObject();
        J.put(o, "id", id);
        J.put(o, "type", "function");
        J.put(o, "function", fn);
        return o;
    }

    public static ToolCall fromJson(JSONObject o) {
        JSONObject fn = J.obj(o, "function");
        Object a = fn == null ? null : fn.opt("arguments");
        return new ToolCall(J.str(o, "id"), fn == null ? J.str(o, "name") : J.str(fn, "name"),
            a == null || a == JSONObject.NULL ? "{}" : (a instanceof String ? (String) a : a.toString()));
    }
}
