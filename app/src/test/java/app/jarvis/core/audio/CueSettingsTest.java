package app.jarvis.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class CueSettingsTest {
    private static CueSettings resolve(String... kv) {
        final Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return CueSettings.resolve(new CueSettings.Source() { @Override public String get(String key) { return m.get(key); } });
    }

    @Test public void defaultsAreOnAndCrystal() {
        CueSettings s = resolve();
        assertTrue(s.enabled && s.startOn && s.stopOn && s.errorOn && s.haptic);
        assertEquals("crystal", s.set);
        assertEquals(0.55f, s.volume, 1e-6f);
        assertEquals("", s.customStart);
    }

    @Test public void volumeIsClampedAndBadValuesFallBack() {
        assertEquals(0.1f, resolve(CueSettings.VOLUME, "0").volume, 1e-6f);
        assertEquals(1f, resolve(CueSettings.VOLUME, "7.5").volume, 1e-6f);
        assertEquals(0.55f, resolve(CueSettings.VOLUME, "loud").volume, 1e-6f);
        assertEquals(0.55f, resolve(CueSettings.VOLUME, "NaN").volume, 1e-6f);
    }

    @Test public void setIdMustBeSafeForAFileName() {
        assertEquals("marimba", resolve(CueSettings.SET, "marimba").set);
        assertEquals("crystal", resolve(CueSettings.SET, "../../etc").set);
        assertEquals("crystal", resolve(CueSettings.SET, "Crystal").set);
    }

    @Test public void startAndStopSetsFollowTheDefaultSetUnlessChosenSeparately() {
        CueSettings s = resolve(CueSettings.SET, "marimba");
        assertEquals("marimba", s.startSet);
        assertEquals("marimba", s.stopSet);
        s = resolve(CueSettings.SET, "marimba", CueSettings.START_SET, "bell", CueSettings.STOP_SET, "water");
        assertEquals("bell", s.startSet);
        assertEquals("water", s.stopSet);
        assertEquals("marimba", s.set);
    }

    @Test public void malformedKindSetFallsBackToTheDefaultSet() {
        CueSettings s = resolve(CueSettings.SET, "bell", CueSettings.START_SET, "../x", CueSettings.STOP_SET, "Water");
        assertEquals("bell", s.startSet);
        assertEquals("bell", s.stopSet);
    }

    @Test public void customFileNameCannotEscapeCuesDirectory() {
        assertEquals("", resolve(CueSettings.CUSTOM_START, "../x.ogg").customStart);
        assertEquals("", resolve(CueSettings.CUSTOM_START, "a/b.ogg").customStart);
        assertEquals("", resolve(CueSettings.CUSTOM_STOP, "a..ogg").customStop);
        assertEquals("custom-start.wav", resolve(CueSettings.CUSTOM_START, "custom-start.wav").customStart);
    }

    @Test public void booleansAcceptTrueFalseAndDigits() {
        assertFalse(resolve(CueSettings.ENABLED, "false").enabled);
        assertFalse(resolve(CueSettings.STOP_ON, "0").stopOn);
        assertTrue(resolve(CueSettings.ERROR_ON, "1").errorOn);
        assertFalse(resolve(CueSettings.HAPTIC, "false").haptic);
    }
}
