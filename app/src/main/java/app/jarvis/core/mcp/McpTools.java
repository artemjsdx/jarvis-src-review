package app.jarvis.core.mcp;

import app.jarvis.core.util.J;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Lambda-style tool definitions and a tiny JSON Schema builder. */
public final class McpTools {
    private McpTools() { }

    public interface Handler { McpResult call(JSONObject args, McpCall call) throws Exception; }

    /** Builder for the schema of the arguments object. */
    public static final class Schema {
        private final JSONObject props = new JSONObject();
        private final JSONArray required = new JSONArray();

        public static Schema obj() { return new Schema(); }

        private Schema add(String name, String type, String desc, boolean req, String... enumValues) {
            JSONObject p = new JSONObject();
            if (type != null) J.put(p, "type", type);
            J.put(p, "description", desc);
            if (enumValues.length > 0) { JSONArray e = new JSONArray(); for (String v : enumValues) e.put(v); J.put(p, "enum", e); }
            J.put(props, name, p);
            if (req) required.put(name);
            return this;
        }

        public Schema str(String name, String desc, boolean req) { return add(name, "string", desc, req); }
        public Schema oneOf(String name, String desc, boolean req, String... values) { return add(name, "string", desc, req, values); }
        public Schema integer(String name, String desc, boolean req) { return add(name, "integer", desc, req); }
        public Schema number(String name, String desc, boolean req) { return add(name, "number", desc, req); }
        public Schema bool(String name, String desc, boolean req) { return add(name, "boolean", desc, req); }
        public Schema array(String name, String desc, boolean req) { return add(name, "array", desc, req); }
        public Schema object(String name, String desc, boolean req) { return add(name, "object", desc, req); }
        public Schema any(String name, String desc, boolean req) { return add(name, null, desc, req); }

        public JSONObject build() {
            JSONObject s = new JSONObject();
            J.put(s, "type", "object");
            J.put(s, "properties", props);
            J.put(s, "required", required);
            return s;
        }
    }

    public static Def tool(String name, String description, Schema schema, Handler h) { return new Def(name, description, schema.build(), h); }

    public static Def tool(String name, String description, Handler h) { return new Def(name, description, Schema.obj().build(), h); }

    public static final class Def implements McpTool {
        private final String name, description;
        private final JSONObject schema;
        private final Handler handler;
        private long timeoutMs = 30_000;
        private int flags;
        private Set<String> sensitive = Collections.emptySet();

        Def(String name, String description, JSONObject schema, Handler h) { this.name = name; this.description = description; this.schema = schema; this.handler = h; }

        public Def timeout(long ms) { timeoutMs = ms; return this; }
        public Def flags(int f) { flags |= f; return this; }
        public Def readOnly() { return flags(McpTool.READ_ONLY); }
        public Def destructive() { return flags(McpTool.DESTRUCTIVE); }
        public Def audio() { return flags(McpTool.AUDIO); }
        public Def sensitive(String... names) { sensitive = new HashSet<>(Arrays.asList(names)); return this; }

        @Override public String name() { return name; }
        @Override public String description() { return description; }
        @Override public JSONObject inputSchema() { return schema; }
        @Override public McpResult call(JSONObject args, McpCall call) throws Exception { return handler.call(args, call); }
        @Override public long timeoutMs() { return timeoutMs; }
        @Override public int flags() { return flags; }
        @Override public Set<String> sensitiveArgs() { return sensitive; }
    }
}
