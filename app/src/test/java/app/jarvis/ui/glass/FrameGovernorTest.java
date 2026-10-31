package app.jarvis.ui.glass;

import static org.junit.Assert.*;

import app.jarvis.ui.glass.FrameGovernor;
import org.junit.Test;

public class FrameGovernorTest {
    /** Feeds one glass frame every 16 ms for durationMs; returns how many times the governor asked for a step down. */
    private static int run(FrameGovernor g, float costMs, double durationMs) {
        int steps = 0;
        for (double t = 0; t <= durationMs; t += 16) if (g.onFrame(t, costMs)) steps++;
        return steps;
    }

    @Test public void cheapGlassFramesNeverStepDown() {
        assertEquals(0, run(new FrameGovernor(), 3f, 5000));
    }

    @Test public void expensiveGlassFramesStepDownOnceTheWindowIsFull() {
        FrameGovernor g = new FrameGovernor();
        int steps = 0;
        double firstStep = -1;
        for (double t = 0; t <= 5000; t += 16) {
            if (g.onFrame(t, 20f)) { steps++; if (firstStep < 0) firstStep = t; }
        }
        assertTrue("a slow level keeps stepping, one step per full window", steps >= 1 && steps <= 2);
        assertTrue("step must wait for the 2 s window, fired at " + firstStep, firstStep >= FrameGovernor.WINDOW_MS);
    }

    @Test public void stopsGoverningAfterAPassingWindow() {
        FrameGovernor g = new FrameGovernor();
        assertEquals(0, run(g, 2f, 2500));
        assertEquals("a finished window must not fire later", 0, run(g, 50f, 5000));
    }

    @Test public void p95IsNearestRank() {
        float[] v = new float[100];
        for (int i = 0; i < 100; i++) v[i] = i + 1;
        assertEquals(95f, FrameGovernor.p95(v, 100, new float[100]), 0f);
        assertEquals(9f, FrameGovernor.p95(new float[]{5, 7, 1, 3, 9, 2, 8, 6, 4, 0}, 10, new float[10]), 0f);
        assertEquals(0f, FrameGovernor.p95(v, 0, new float[100]), 0f);
    }
}
