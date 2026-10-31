package app.jarvis.core.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class PerfSummaryTest {
    private static JSONObject turn(String source, long toSound, long llmTtft) {
        JSONObject spans = new JSONObject();
        try {
            spans.put("to_sound", toSound);
            spans.put("llm_ttft", llmTtft);
            JSONObject t = new JSONObject();
            t.put("source", source);
            t.put("spans", spans);
            return t;
        } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test public void percentileInterpolates() {
        List<Double> v = Arrays.asList(10.0, 20.0, 30.0, 40.0);
        assertEquals(25.0, PerfSummary.percentile(v, 0.5), 1e-9);
        assertEquals(37.0, PerfSummary.percentile(v, 0.9), 1e-9);
        assertEquals(10.0, PerfSummary.percentile(v, 0.0), 1e-9);
        assertTrue(Double.isNaN(PerfSummary.percentile(new ArrayList<Double>(), 0.5)));
    }

    @Test public void splitsBySourceAndSkipsMissingSpans() {
        List<JSONObject> ts = new ArrayList<>();
        ts.add(turn("voice", 1000, 400));
        ts.add(turn("voice", 3000, 600));
        ts.add(turn("voice", 2000, -1));
        ts.add(turn("text", 500, 200));
        JSONObject s = PerfSummary.summarize(ts);
        assertEquals(4, s.getInt("turns"));
        JSONObject voice = s.getJSONObject("voice");
        assertEquals(3, voice.getInt("turns"));
        assertEquals(2000, voice.getJSONObject("to_sound").getLong("medianMs"));
        assertEquals(2800, voice.getJSONObject("to_sound").getLong("p90Ms"));
        assertEquals(2, voice.getJSONObject("llm_ttft").getInt("n"));
        assertEquals(500, s.getJSONObject("text").getJSONObject("to_sound").getLong("medianMs"));
        assertEquals(4, s.getJSONObject("all").getJSONObject("to_sound").getInt("n"));
    }

    @Test public void emptyInputHasNoStats() {
        JSONObject s = PerfSummary.summarize(new ArrayList<JSONObject>());
        assertEquals(0, s.getInt("turns"));
        assertFalse(s.getJSONObject("all").getJSONObject("stt").has("medianMs"));
        assertEquals(0, s.getJSONObject("all").getJSONObject("stt").getInt("n"));
    }
}
