package app.jarvis.core.llm;

import app.jarvis.core.util.J;
import org.json.JSONObject;

/** OpenAI function-tool declaration. */
public final class ToolSpec {
    public final String name;
    public final String description;
    public final JSONObject parameters;

    public ToolSpec(String name, String description, JSONObject parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters != null ? parameters : schema();
    }

    public JSONObject toJson(boolean strict) {
        JSONObject fn = new JSONObject();
        J.put(fn, "name", name);
        J.put(fn, "description", description);
        J.put(fn, "parameters", parameters);
        if (strict) J.put(fn, "strict", true);
        JSONObject o = new JSONObject();
        J.put(o, "type", "function");
        J.put(o, "function", fn);
        return o;
    }

    // ---- tiny schema DSL -------------------------------------------------------------------------------------
    public static JSONObject schema() {
        JSONObject s = new JSONObject();
        J.put(s, "type", "object");
        J.put(s, "properties", new JSONObject());
        J.put(s, "required", new org.json.JSONArray());
        J.put(s, "additionalProperties", false);
        return s;
    }

    public static ToolSpec of(String name, String description, Param... params) {
        JSONObject s = schema();
        JSONObject props = J.obj(s, "properties");
        org.json.JSONArray req = J.arr(s, "required");
        for (Param p : params) {
            JSONObject pj = new JSONObject();
            J.put(pj, "type", p.type);
            J.put(pj, "description", p.desc);
            if (p.enumValues != null) {
                org.json.JSONArray e = new org.json.JSONArray();
                for (String v : p.enumValues) e.put(v);
                J.put(pj, "enum", e);
            }
            J.put(props, p.name, pj);
            if (p.required) req.put(p.name);
        }
        return new ToolSpec(name, description, s);
    }

    public static final class Param {
        final String name, type, desc;
        final boolean required;
        String[] enumValues;

        Param(String name, String type, String desc, boolean required) {
            this.name = name; this.type = type; this.desc = desc; this.required = required;
        }

        public Param oneOf(String... v) { this.enumValues = v; return this; }

        public static Param str(String name, String desc, boolean required) { return new Param(name, "string", desc, required); }
        public static Param integer(String name, String desc, boolean required) { return new Param(name, "integer", desc, required); }
        public static Param bool(String name, String desc, boolean required) { return new Param(name, "boolean", desc, required); }
        public static Param number(String name, String desc, boolean required) { return new Param(name, "number", desc, required); }
    }
}
