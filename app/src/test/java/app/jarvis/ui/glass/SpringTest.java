package app.jarvis.ui.glass;

import static org.junit.Assert.*;

import app.jarvis.ui.glass.Spring;
import org.junit.Test;

public class SpringTest {
    private static final float DT = 1f / 60f;

    /** Runs the spring at 60 Hz until it reports rest; returns the frame count. */
    private static int runToRest(Spring s, float[] maxOut) {
        int frames = 0;
        float max = s.value();
        while (frames < 2000 && s.step(DT)) {
            max = Math.max(max, s.value());
            frames++;
        }
        maxOut[0] = max;
        return frames;
    }

    @Test public void settlesOnTargetWithSmallOvershootInUnderOneSecond() {
        Spring s = new Spring();
        s.snap(0f);
        s.setTarget(100f);
        float[] max = new float[1];
        int frames = runToRest(s, max);
        assertEquals(100f, s.value(), 0f);
        assertTrue("settled after " + frames + " frames", frames * DT < 1f);
        float overshoot = (max[0] - 100f) / 100f;
        assertTrue("overshoot " + overshoot, overshoot < 0.02f);
        assertTrue("must still be near-critical and move past the target a little", overshoot > 0f);
    }

    @Test public void reverseDirectionHasTheSameBehaviour() {
        Spring s = new Spring();
        s.snap(300f);
        s.setTarget(20f);
        float[] min = {s.value()};
        int frames = 0;
        while (frames < 2000 && s.step(DT)) { min[0] = Math.min(min[0], s.value()); frames++; }
        assertEquals(20f, s.value(), 0f);
        assertTrue(frames * DT < 1f);
        assertTrue("undershoot " + min[0], min[0] > 20f - 0.02f * 280f);
    }

    @Test public void restingSpringDoesNotMove() {
        Spring s = new Spring();
        s.snap(42f);
        assertFalse(s.step(DT));
        assertEquals(42f, s.value(), 0f);
    }

    @Test public void longStallIsClampedAndStaysFinite() {
        Spring s = new Spring();
        s.snap(0f);
        s.setTarget(100f);
        s.step(30f);
        assertTrue(Float.isFinite(s.value()));
        assertTrue(Math.abs(s.value() - 100f) < 150f);
    }
}
