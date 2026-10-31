package app.jarvis.core.mcp;

import app.jarvis.core.util.J;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/**
 * Medians and p90 of the turn spans from TurnTrace JSON (perf_summary). Spans that a turn does not have are skipped,
 * so {@code n} can differ per metric. Turns are split into "voice" and "text" by their {@code source}.
 */
public final class PerfSummary {
    private PerfSummary() { }

    public static final String[] METRICS = {"to_sound", "stt", "llm_ttft", "llm_total", "tts_ttfa"};

    /** Linear-interpolated percentile of an ascending list, p in [0, 1]; NaN for an empty list. */
    public static double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return Double.NaN;
        double pos = p * (sorted.size() - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (pos - lo);
    }

    /** {@code traces} are TurnTrace JSON objects; returns {"all":{...},"voice":{...},"text":{...}} with count and per-metric stats. */
    public static JSONObject summarize(List<JSONObject> traces) {
        JSONObject out = new JSONObject();
        J.put(out, "turns", traces.size());
        J.put(out, "all", groupStats(traces, null));
        J.put(out, "voice", groupStats(traces, "voice"));
        J.put(out, "text", groupStats(traces, "text"));
        return out;
    }

    private static JSONObject groupStats(List<JSONObject> traces, String source) {
        List<JSONObject> sel = new ArrayList<>();
        for (JSONObject t : traces) if (source == null || source.equals(t.optString("source", ""))) sel.add(t);
        JSONObject g = new JSONObject();
        J.put(g, "turns", sel.size());
        for (String m : METRICS) {
            List<Double> vals = new ArrayList<>();
            for (JSONObject t : sel) {
                JSONObject spans = t.optJSONObject("spans");
                if (spans != null && spans.has(m) && spans.optLong(m, -1) >= 0) vals.add((double) spans.optLong(m));
            }
            Collections.sort(vals);
            JSONObject s = new JSONObject();
            J.put(s, "n", vals.size());
            if (!vals.isEmpty()) {
                J.put(s, "medianMs", round(percentile(vals, 0.5)));
                J.put(s, "p90Ms", round(percentile(vals, 0.9)));
                J.put(s, "minMs", round(vals.get(0)));
                J.put(s, "maxMs", round(vals.get(vals.size() - 1)));
            }
            J.put(g, m, s);
        }
        return g;
    }

    private static long round(double v) { return Math.round(v); }

}
