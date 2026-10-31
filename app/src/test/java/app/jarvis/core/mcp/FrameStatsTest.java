package app.jarvis.core.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class FrameStatsTest {
    @Test public void countsJankAndSlowest() {
        List<Double> f = new ArrayList<>(Arrays.asList(10.0, 10.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0));
        JSONObject o = FrameStats.summarize(f, 3000);
        assertEquals(10, o.getInt("frames"));
        assertEquals(80.0, o.getJSONArray("slowestMs").getDouble(0), 1e-9);
        assertEquals(5, o.getJSONArray("slowestMs").length());
        assertEquals(70.0, o.getDouble("jankPct"), 1e-9);
        assertTrue(o.getDouble("p90Ms") > o.getDouble("p50Ms"));
    }

    @Test public void noFramesSaysWhy() {
        JSONObject o = FrameStats.summarize(new ArrayList<Double>(), 1000);
        assertEquals(0, o.getInt("frames"));
        assertTrue(o.has("note"));
        assertTrue(!o.has("p50Ms"));
    }
}
