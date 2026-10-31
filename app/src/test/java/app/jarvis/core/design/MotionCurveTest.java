package app.jarvis.core.design;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MotionCurveTest {
    private static final Bezier DECEL = new Bezier(0.05, 0.7, 0.1, 1.0);
    private static final Bezier ACCEL = new Bezier(0.3, 0.0, 0.8, 0.15);
    private static final Bezier STANDARD = new Bezier(0.2, 0.0, 0.0, 1.0);

    @Test public void bezierHitsEndpointsAndStaysInBounds() {
        for (Bezier b : new Bezier[]{DECEL, ACCEL, STANDARD}) {
            assertEquals(0.0, b.at(0), 1e-9);
            assertEquals(1.0, b.at(1), 1e-9);
            for (int i = 0; i <= 200; i++) {
                double y = b.at(i / 200.0);
                assertTrue("out of bounds at " + i + ": " + y, y >= 0 && y <= 1);
            }
        }
    }

    @Test public void bezierIsMonotoneForDecelerateCurves() {
        double prev = -1;
        for (int i = 0; i <= 500; i++) {
            double y = DECEL.at(i / 500.0);
            assertTrue("not monotone at " + i, y >= prev - 1e-9);
            prev = y;
        }
    }

    @Test public void decelerateFrontLoadsAndAccelerateBackLoads() {
        assertTrue("decelerate should be ahead at mid time", DECEL.at(0.5) > 0.5);
        assertTrue("accelerate should be behind at mid time", ACCEL.at(0.5) < 0.5);
    }

    @Test public void springProgressIsAnchoredAtBothEnds() {
        Spring s = new Spring(0.65, 14.0);
        assertEquals(0.0, s.progress(0), 1e-12);
        assertEquals(1.0, s.progress(1), 1e-12);
    }

    @Test public void springSettlesWithinOnePercent() {
        Spring s = new Spring(0.7, 12.0);
        double t = s.settleTime();
        for (double x = t; x <= t * 3; x += t / 50) {
            assertTrue("not settled at t=" + x, Math.abs(s.response(x) - 1) <= 0.01 + 1e-9);
        }
    }

    @Test public void springOvershootMatchesClosedForm() {
        Spring s = new Spring(0.5, 10.0);
        double peak = 0;
        for (int i = 1; i <= 4000; i++) peak = Math.max(peak, s.response(i * 0.0005));
        double expected = 1 + s.overshoot();
        assertEquals(expected, peak, 0.005);
        assertTrue("damped spring must overshoot a little", peak > 1.0);
    }

    @Test public void springProgressIsBoundedByOvershoot() {
        Spring s = new Spring(0.6, 15.0);
        double max = 0;
        for (int i = 0; i <= 1000; i++) max = Math.max(max, s.progress(i / 1000.0));
        assertTrue("overshoot too large: " + max, max <= 1 + s.overshoot() + 0.01);
    }

    @Test(expected = IllegalArgumentException.class)
    public void springRejectsCriticalDamping() { new Spring(1.0, 10.0); }
}
