package app.jarvis.core.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpeedMappingTest {
    @Test public void settingIsClampedToTheSupportedRange() {
        assertEquals(0.7f, TtsCapabilities.clampSpeed(0.1f), 1e-6f);
        assertEquals(1.4f, TtsCapabilities.clampSpeed(3f), 1e-6f);
        assertEquals(1f, TtsCapabilities.clampSpeed(Float.NaN), 1e-6f);
        assertEquals(1.15f, TtsCapabilities.clampSpeed(1.15f), 1e-6f);
    }

    @Test public void onlyProvidersThatActOnSpeedAreMarked() {
        assertTrue(TtsCapabilities.supportsSpeed("elevenlabs"));
        assertTrue(TtsCapabilities.supportsSpeed("edge"));
        assertTrue(TtsCapabilities.supportsSpeed("gtts"));
        assertTrue(TtsCapabilities.supportsSpeed("f5-ru"));
        assertFalse(TtsCapabilities.supportsSpeed("silero"));
        assertFalse(TtsCapabilities.supportsSpeed("openrouter"));
        assertFalse(TtsCapabilities.supportsSpeed(null));
    }

    @Test public void elevenLabsVoiceSettingStaysInItsRange() {
        assertEquals(0.7, ElevenLabsApi.apiSpeed(0.5f), 1e-9);
        assertEquals(0.8, ElevenLabsApi.apiSpeed(0.8f), 1e-6);
        assertEquals(1.0, ElevenLabsApi.apiSpeed(1f), 1e-9);
        assertEquals(1.2, ElevenLabsApi.apiSpeed(1.4f), 1e-9);
    }

    @Test public void edgeProsodyRateIsPercentOfNormal() {
        assertTrue(EdgeTts.buildSsml("v", "т", 1f).contains("rate='+0%'"));
        assertTrue(EdgeTts.buildSsml("v", "т", 0.7f).contains("rate='-30%'"));
        assertTrue(EdgeTts.buildSsml("v", "т", 1.4f).contains("rate='+40%'"));
    }

    @Test public void googleTranslateOnlySlowsDownBelowNinetyPercent() {
        assertNull(GoogleTranslateTts.ttsSpeedParam(1f));
        assertNull(GoogleTranslateTts.ttsSpeedParam(0.95f));
        assertNull(GoogleTranslateTts.ttsSpeedParam(1.4f));
        assertEquals("0.85", GoogleTranslateTts.ttsSpeedParam(0.85f));
        assertEquals("0.50", GoogleTranslateTts.ttsSpeedParam(0.5f));
        assertEquals("0.24", GoogleTranslateTts.ttsSpeedParam(0.05f));
    }
}
