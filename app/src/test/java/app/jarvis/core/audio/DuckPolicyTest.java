package app.jarvis.core.audio;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class DuckPolicyTest {
    @Test public void speechDefaultsToTenPercent() {
        assertEquals(0.08f, DuckPolicy.target(0.8f, true, false, true, 10), 0.0001f);
    }
    @Test public void disabledSpeechPreservesMusic() {
        assertEquals(0.8f, DuckPolicy.target(0.8f, true, false, false, 10), 0.0001f);
    }
    @Test public void captureAndSpeechUseQuieterOwner() {
        assertEquals(0.016f, DuckPolicy.target(0.8f, true, true, true, 2), 0.0001f);
        assertEquals(0.176f, DuckPolicy.target(0.8f, true, true, false, 10), 0.0001f);
    }
    @Test public void rampClampsBoundaries() {
        assertEquals(1f, DuckPolicy.between(1f, 0.1f, -1f), 0.0001f);
        assertEquals(0.1f, DuckPolicy.between(1f, 0.1f, 2f), 0.0001f);
        assertEquals(0.55f, DuckPolicy.between(1f, 0.1f, 0.5f), 0.0001f);
    }
}
