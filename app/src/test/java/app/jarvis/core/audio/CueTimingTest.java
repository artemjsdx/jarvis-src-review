package app.jarvis.core.audio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CueTimingTest {
    @Test public void noCueMeansNoGate() {
        assertEquals(10_000L, CueTiming.dropUntil(10_000L, 0));
    }

    @Test public void gateCoversClipPlusTail() {
        assertEquals(10_000L + 320 + 120, CueTiming.dropUntil(10_000L, 320));
        assertEquals(10_000L + 447 + 120, CueTiming.dropUntil(10_000L, 447));
    }
}
