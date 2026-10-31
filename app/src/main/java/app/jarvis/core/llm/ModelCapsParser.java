package app.jarvis.core.llm;

import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reads the capability fields of one entry from a GET /models list. Understands the OpenRouter layout
 * (supported_parameters, architecture, top_provider, pricing as strings per token), DeepSeek (context_window,
 * max_output_tokens, effort.supported_levels), Groq (context_window, max_completion_tokens), Mistral (max_context_length,
 * capabilities) and plain OpenAI-style entries that report nothing. Missing fields stay unknown.
 */
public final class ModelCapsParser {
    private ModelCapsParser() { }

    public static ModelCaps parse(JSONObject m, String id) {
        ModelCaps c = new ModelCaps(id);
        if (m == null) return c;
        JSONObject top = obj(m, "top_provider");
        c.contextTokens = firstPositive(num(m, "context_length"), num(m, "context_window"), num(m, "max_context_length"), num(top, "context_length"));
        c.maxOutputTokens = firstPositive(num(top, "max_completion_tokens"), num(m, "max_completion_tokens"), num(m, "max_output_tokens"));

        JSONArray sp = arr(m, "supported_parameters");
        if (sp != null) {
            Set<String> s = strings(sp);
            c.tools = Cap.of(s.contains("tools"));
            c.temperature = Cap.of(s.contains("temperature"));
            c.reasoning = Cap.of(s.contains("reasoning") || s.contains("include_reasoning") || s.contains("reasoning_effort"));
        }

        JSONObject caps = obj(m, "capabilities");
        if (caps != null) {
            if (caps.has("function_calling")) c.tools = c.tools.or(Cap.of(caps.optBoolean("function_calling")));
            if (caps.has("vision")) c.vision = c.vision.or(Cap.of(caps.optBoolean("vision")));
        }

        JSONObject effort = obj(m, "effort");
        JSONArray levels = effort == null ? null : arr(effort, "supported_levels");
        if (levels != null) {
            for (String l : strings(levels)) if (!c.reasoningLevels.contains(l)) c.reasoningLevels.add(l);
            if (!c.reasoningLevels.isEmpty()) c.reasoning = Cap.YES;
        }

        JSONObject arch = obj(m, "architecture");
        applyModalities(c, arr(m, "input_modalities"), arr(m, "output_modalities"));
        if (arch != null) applyModalities(c, arr(arch, "input_modalities"), arr(arch, "output_modalities"));

        JSONObject pricing = obj(m, "pricing");
        if (pricing != null) {
            c.priceInPerM = perMillion(pricing.opt("prompt"));
            c.priceOutPerM = perMillion(pricing.opt("completion"));
        }
        if (c.hasData()) c.source = ModelCaps.Source.PROVIDER;
        return c;
    }

    private static void applyModalities(ModelCaps c, JSONArray in, JSONArray out) {
        if (in != null) {
            Set<String> s = strings(in);
            c.vision = c.vision.or(Cap.of(s.contains("image")));
            c.audioIn = c.audioIn.or(Cap.of(s.contains("audio")));
        }
        if (out != null) c.audioOut = c.audioOut.or(Cap.of(strings(out).contains("audio")));
    }

    private static Double perMillion(Object perToken) {
        Double v = asDouble(perToken);
        if (v == null || v < 0) return null;
        return Math.round(v * 1_000_000d * 1000d) / 1000d;
    }

    static Double asDouble(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            try { return Double.parseDouble(((String) o).trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static int firstPositive(int... vs) {
        for (int v : vs) if (v > 0) return v;
        return 0;
    }

    /** Numeric field that may be a number or a numeric string; 0 when absent or not numeric. */
    static int num(JSONObject o, String key) {
        if (o == null) return 0;
        Double d = asDouble(o.opt(key));
        return d == null || d.isNaN() || d.isInfinite() || d <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.round(d));
    }

    private static JSONObject obj(JSONObject o, String key) {
        Object v = o == null ? null : o.opt(key);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    private static JSONArray arr(JSONObject o, String key) {
        Object v = o == null ? null : o.opt(key);
        return v instanceof JSONArray ? (JSONArray) v : null;
    }

    private static Set<String> strings(JSONArray a) {
        Set<String> s = new LinkedHashSet<>();
        for (int i = 0; i < a.length(); i++) {
            String v = a.optString(i, "").trim();
            if (!v.isEmpty()) s.add(v);
        }
        return s;
    }
}
