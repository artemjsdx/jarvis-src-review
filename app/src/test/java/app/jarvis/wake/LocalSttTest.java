package app.jarvis.wake;

import static org.junit.Assert.*;

import app.jarvis.core.util.CancelToken;
import app.jarvis.core.voice.PcmAudio;
import app.jarvis.core.voice.ProviderConfig;
import app.jarvis.core.voice.ProviderInfo;
import app.jarvis.core.voice.ProviderStatus;
import app.jarvis.core.voice.SttOptions;
import app.jarvis.core.voice.VoiceException;
import java.io.File;
import java.util.Arrays;
import org.junit.Test;

/** Contract tests for the offline SttProvider; no vosk natives needed (all native paths are guarded). */
public class LocalSttTest {

    @Test public void infoIsHonest() {
        ProviderInfo i = new LocalStt().info();
        assertEquals("vosk-local", i.id);
        assertTrue("must claim official", i.official);
        assertFalse("no account needed", i.needsAccount);
        assertFalse("no key needed", i.needsKey);
        assertTrue("must say where data goes (nowhere)", i.sendsDataTo.contains("локальн") || i.sendsDataTo.contains("ничего"));
        assertTrue("must disclose the accuracy tradeoff", i.limitations.contains("точность"));
        assertTrue("must disclose RAM cost", i.limitations.contains("ОЗУ"));
        assertTrue(i.limitations.contains("45 МБ") || i.limitations.contains("маленькая модель"));
    }

    @Test public void notConfiguredWithoutModel() {
        LocalStt s = new LocalStt();
        ProviderConfig cfg = new ProviderConfig();
        cfg.put("modelDir", "/nonexistent/pack");
        s.configure(cfg);
        assertFalse(s.isConfigured());
    }

    @Test public void transcribeWithoutModelGivesConfigError() {
        LocalStt s = new LocalStt();
        ProviderConfig cfg = new ProviderConfig();
        cfg.put("modelDir", "/nonexistent/pack");
        s.configure(cfg);
        try {
            s.transcribe(new PcmAudio(new byte[3200], 16000), new SttOptions(), new CancelToken());
            fail("expected VoiceException");
        } catch (VoiceException e) {
            assertEquals(VoiceException.Kind.CONFIG, e.kind);
            assertTrue("user-safe Russian", e.userMessage.matches("(?s).*[а-яА-Я].*"));
        }
    }

    @Test public void transcribeRejectsWrongRate() {
        LocalStt s = new LocalStt();
        try {
            s.transcribe(new PcmAudio(new byte[100], 44100), new SttOptions(), new CancelToken());
            fail("expected VoiceException");
        } catch (VoiceException e) {
            assertEquals(VoiceException.Kind.UNSUPPORTED, e.kind);
        }
    }

    @Test public void checkReportsFailureWhenUnavailable() {
        LocalStt s = new LocalStt();
        ProviderConfig cfg = new ProviderConfig();
        cfg.put("modelDir", "/nonexistent/pack");
        s.configure(cfg);
        ProviderStatus st = s.check(new PcmAudio(new byte[3200], 16000), new CancelToken());
        assertFalse(st.ok);
        assertFalse("Russian error", st.lastError.isEmpty());
    }

    @Test public void assembleTextJoinsChunks() {
        String t = LocalStt.assembleText(Arrays.asList(
            "{\"result\":[{\"conf\":1,\"start\":0.1,\"end\":0.4,\"word\":\"останови\"}],\"text\":\"останови\"}",
            "",
            "garbage",
            "{\"result\":[{\"conf\":1,\"start\":0.5,\"end\":0.9,\"word\":\"музыку\"}],\"text\":\"музыку\"}"));
        assertEquals("останови музыку", t);
    }

    @Test public void averageConfidenceAveragesWordConf() {
        float c = LocalStt.averageConfidence(Arrays.asList(
            "{\"result\":[{\"conf\":0.5,\"start\":0.1,\"end\":0.2,\"word\":\"a\"},{\"conf\":1.0,\"start\":0.3,\"end\":0.4,\"word\":\"b\"}]}",
            "{\"result\":[{\"conf\":0.5,\"start\":0.5,\"end\":0.6,\"word\":\"c\"}],\"text\":\"x\"}"));
        assertEquals((0.5f + 1.0f + 0.5f) / 3, c, 0.001f);
        assertEquals(-1f, LocalStt.averageConfidence(Arrays.asList("{}")), 0.001f);
    }

    @Test public void wordMatchCountsExpectedWords() {
        double r = LocalStt.wordMatch("привет я джарвис ваш помощник", LocalStt.SAMPLE_TEXT);
        assertTrue("5 of 8 words recognized → 5/8", Math.abs(r - 5.0 / 8) < 0.01);
        assertEquals(0, LocalStt.wordMatch("", LocalStt.SAMPLE_TEXT), 1e-9);
        assertEquals(1.0, LocalStt.wordMatch("Привет! Я Джарвис, ваш голосовой помощник. Доброе утро.", LocalStt.SAMPLE_TEXT), 1e-9);
    }
}
