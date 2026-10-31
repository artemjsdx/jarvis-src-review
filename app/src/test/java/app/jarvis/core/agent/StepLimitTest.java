package app.jarvis.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StepLimitTest {
    @Test public void zeroMeansUnlimitedWithHardCap() {
        assertTrue(StepLimit.unlimited(0));
        assertEquals(200, StepLimit.effective(0));
        assertEquals(200, StepLimit.UNLIMITED_CAP);
    }

    @Test public void limitedValuesKeepTheirNumberWithAFloorOfTwo() {
        assertFalse(StepLimit.unlimited(10));
        assertEquals(10, StepLimit.effective(10));
        assertEquals(2, StepLimit.effective(1));
        assertEquals(30, StepLimit.effective(30));
    }
}
