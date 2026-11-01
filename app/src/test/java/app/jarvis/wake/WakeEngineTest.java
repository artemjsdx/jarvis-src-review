package app.jarvis.wake;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WakeEngineTest {

    @Test public void auto_prefersLocal_whenReady() {
        assertEquals(WakeEngine.Kind.LOCAL, WakeEngine.pick("auto", true));
        assertEquals(WakeEngine.Kind.LOCAL, WakeEngine.pick(null, true));
        assertEquals(WakeEngine.Kind.LOCAL, WakeEngine.pick("  AUTO ", true));
    }

    @Test public void auto_fallsBackToCloud_whenLocalNotReady() {
        assertEquals(WakeEngine.Kind.CLOUD, WakeEngine.pick("auto", false));
        assertEquals(WakeEngine.Kind.CLOUD, WakeEngine.pick("", false));
        assertEquals(WakeEngine.Kind.CLOUD, WakeEngine.pick("bogus", false));
    }

    @Test public void explicitLocal_isLocal_evenWhenNotReady() {
        assertEquals(WakeEngine.Kind.LOCAL, WakeEngine.pick("local", false));
        assertFalse(WakeEngine.cloudFallbackAllowed("local"));
    }

    @Test public void explicitCloud_isCloud_evenWhenLocalReady() {
        assertEquals(WakeEngine.Kind.CLOUD, WakeEngine.pick("cloud", true));
    }

    @Test public void localHits_doNotRequireCloudUnlessOptedIn() {
        assertFalse(WakeEngine.verifyLocalHits("auto"));
        assertFalse(WakeEngine.verifyLocalHits(null));
        assertTrue(WakeEngine.verifyLocalHits("auto", true));
        assertFalse(WakeEngine.verifyLocalHits("auto", false));
        assertFalse(WakeEngine.verifyLocalHits("local", true));
        assertFalse(WakeEngine.verifyLocalHits("cloud", true));
    }

    @Test public void cloudFallback_onlyForAuto() {
        assertTrue(WakeEngine.cloudFallbackAllowed("auto"));
        assertTrue(WakeEngine.cloudFallbackAllowed(null));
        assertFalse(WakeEngine.cloudFallbackAllowed("cloud"));
    }
}
