package app.jarvis.core.mcp.client;

import app.jarvis.core.util.J;
import org.json.JSONObject;

/** A tool as the external server describes it (tools/list). The schema is kept raw; {@link McpSchemas} sanitizes it for models. */
public final class RemoteTool {
    public final String name;
    public final String description;
    public final JSONObject inputSchema;
    public final boolean readOnly;

    public RemoteTool(String name, String description, JSONObject inputSchema, boolean readOnly) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.inputSchema = inputSchema == null ? McpSchemas.emptyObject() : inputSchema;
        this.readOnly = readOnly;
    }

    /** Null when the entry has no usable name. readOnlyHint is trusted only when explicitly true. */
    static RemoteTool fromJson(JSONObject t) {
        String name = McpWire.str(t, "name", "");
        if (name.isEmpty()) return null;
        JSONObject ann = t.optJSONObject("annotations");
        boolean ro = ann != null && ann.optBoolean("readOnlyHint", false);
        return new RemoteTool(name, McpWire.str(t, "description", ""), t.optJSONObject("inputSchema"), ro);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        J.put(o, "name", name);
        J.put(o, "description", description);
        J.put(o, "inputSchema", inputSchema);
        JSONObject ann = new JSONObject();
        J.put(ann, "readOnlyHint", readOnly);
        J.put(o, "annotations", ann);
        return o;
    }
}
