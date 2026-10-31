package app.jarvis.core.mcp;

import app.jarvis.core.util.J;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Statistics over frame durations (ms) for perf_frames. A frame is jank when it takes longer than one 60 Hz period. */
public final class FrameStats {
    private FrameStats() { }

    public static final double BUDGET_MS = 1000.0 / 60.0;

    public static JSONObject summarize(List<Double> frames, int durationMs) {
        JSONObject o = new JSONObject();
        J.put(o, "durationMs", durationMs);
        int n = frames.size();
        J.put(o, "frames", n);
        if (n == 0) {
            J.put(o, "note", "кадров не было: окно не перерисовывалось (экран выключен или приложение в фоне)");
            return o;
        }
        List<Double> sorted = new ArrayList<>(frames);
        Collections.sort(sorted);
        int jank = 0;
        for (double v : sorted) if (v > BUDGET_MS) jank++;
        J.put(o, "jankPct", Math.round(jank * 1000.0 / n) / 10.0);
        J.put(o, "p50Ms", round(percentile(sorted, 0.50)));
        J.put(o, "p90Ms", round(percentile(sorted, 0.90)));
        J.put(o, "p99Ms", round(percentile(sorted, 0.99)));
        List<Double> slow = new ArrayList<>();
        for (int i = sorted.size() - 1; i >= 0 && slow.size() < 5; i--) slow.add(round(sorted.get(i)));
        J.put(o, "slowestMs", new JSONArray(slow));
        return o;
    }

    /** Linear-interpolated percentile of an ascending list, p in [0, 1]. */
    public static double percentile(List<Double> sorted, double p) {
        double pos = p * (sorted.size() - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (pos - lo);
    }

    private static double round(double v) { return Math.round(v * 10) / 10.0; }
}
