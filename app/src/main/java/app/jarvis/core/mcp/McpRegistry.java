package app.jarvis.core.mcp;

import app.jarvis.core.util.J;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Tools and resources exposed by the bridge. Thread-safe; tools may be added while the server runs. */
public final class McpRegistry {
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,64}");
    private final Map<String, McpTool> tools = new LinkedHashMap<>();
    private final Map<String, McpResource> resources = new LinkedHashMap<>();

    public synchronized McpRegistry register(McpTool t) {
        if (!NAME.matcher(t.name()).matches()) throw new IllegalArgumentException("bad tool name: " + t.name());
        if (tools.containsKey(t.name())) throw new IllegalArgumentException("duplicate tool: " + t.name());
        tools.put(t.name(), t);
        return this;
    }

    /** Replaces an existing tool of the same name (used when a stub gets a real implementation). */
    public synchronized McpRegistry put(McpTool t) {
        if (!NAME.matcher(t.name()).matches()) throw new IllegalArgumentException("bad tool name: " + t.name());
        tools.put(t.name(), t);
        return this;
    }

    public synchronized McpRegistry registerResource(McpResource r) { resources.put(r.uri(), r); return this; }

    public synchronized McpTool get(String name) { return tools.get(name); }
    public synchronized boolean has(String name) { return tools.containsKey(name); }
    public synchronized List<McpTool> tools() { return new ArrayList<>(tools.values()); }
    public synchronized List<String> names() { return new ArrayList<>(tools.keySet()); }
    public synchronized McpResource resource(String uri) { return resources.get(uri); }
    public synchronized List<McpResource> resources() { return new ArrayList<>(resources.values()); }

    public JSONArray toolsJson() {
        JSONArray a = new JSONArray();
        for (McpTool t : tools()) {
            JSONObject o = new JSONObject();
            J.put(o, "name", t.name());
            J.put(o, "description", t.description());
            J.put(o, "inputSchema", t.inputSchema());
            JSONObject ann = new JSONObject();
            J.put(ann, "readOnlyHint", (t.flags() & McpTool.READ_ONLY) != 0);
            J.put(ann, "destructiveHint", (t.flags() & McpTool.DESTRUCTIVE) != 0);
            J.put(o, "annotations", ann);
            a.put(o);
        }
        return a;
    }
}
