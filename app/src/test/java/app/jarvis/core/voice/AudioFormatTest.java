package app.jarvis.core.voice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.util.CancelToken;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** Format negotiation between providers and packers, and the upload bytes Groq receives. */
public class AudioFormatTest {
    private static final List<String> ALL = Arrays.asList("opus", "flac", "wav");

    private static Set<String> set(String... f) { return new HashSet<>(Arrays.asList(f)); }

    private static byte[] pcm(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 7);
        return b;
    }

    @Test public void choose_auto_prefersSmallestAcceptedAvailable() {
        assertEquals("opus", AudioPacker.choose("auto", ALL, set("wav", "flac", "opus")));
        assertEquals("flac", AudioPacker.choose("auto", ALL, set("wav", "flac")));
        assertEquals("wav", AudioPacker.choose("auto", ALL, set("wav")));
    }

    @Test public void choose_explicit_winsWhenAcceptedAndAvailable() {
        assertEquals("flac", AudioPacker.choose("flac", ALL, set("wav", "flac", "opus")));
        assertEquals("wav", AudioPacker.choose("wav", ALL, set("wav", "opus")));
    }

    @Test public void choose_explicitButUnavailable_fallsBackToAuto() {
        assertEquals("opus", AudioPacker.choose("flac", ALL, set("wav", "opus")));
        assertEquals("wav", AudioPacker.choose("opus", Arrays.asList("wav"), set("wav", "opus")));
    }

    @Test public void choose_unknownOrNull_isAuto() {
        assertEquals("opus", AudioPacker.choose(null, ALL, set("wav", "opus")));
        assertEquals("opus", AudioPacker.choose("  OPUS ", ALL, set("wav", "opus")));
        assertEquals("opus", AudioPacker.choose("mp3", ALL, set("wav", "opus")));
    }

    @Test public void choose_neverAcceptedWithoutWav_stillReturnsWav() {
        assertEquals("wav", AudioPacker.choose("auto", Collections.<String>emptyList(), set("opus")));
    }

    // ---- the cost model behind "auto": encoding time against upload time ---------------------------------------------------

    private static final long PHRASE_MS = 3000;

    @Test public void chooseFor_fastLink_sendsRawWav() {
        assertEquals("wav", AudioPacker.chooseFor("auto", ALL, set("wav", "flac", "opus"), PHRASE_MS, 2_000_000));   // 16 Mbit/s
        assertEquals("wav", AudioPacker.chooseFor("auto", ALL, set("wav", "flac", "opus"), PHRASE_MS, 600_000));
    }

    @Test public void chooseFor_middlingLink_prefersFlac() {
        assertEquals("flac", AudioPacker.chooseFor("auto", ALL, set("wav", "flac", "opus"), PHRASE_MS, 200_000));
        assertEquals("flac", AudioPacker.chooseFor("auto", ALL, set("wav", "flac", "opus"), PHRASE_MS, 120_000));
    }

    @Test public void chooseFor_slowLink_prefersOpus() {
        assertEquals("opus", AudioPacker.chooseFor("auto", ALL, set("wav", "flac", "opus"), PHRASE_MS, 40_000));
        assertEquals("opus", AudioPacker.chooseFor("auto", ALL, set("wav", "flac", "opus"), PHRASE_MS, 7_000));   // the 7 KB/s bench uplink
    }

    @Test public void chooseFor_unknownSpeed_usesTheDefaultAndPicksFlacForAPhrase() {
        assertEquals("flac", AudioPacker.chooseFor("auto", ALL, set("wav", "flac", "opus"), PHRASE_MS, -1));
    }

    @Test public void chooseFor_respectsAcceptedAndAvailableAndExplicitChoice() {
        assertEquals("wav", AudioPacker.chooseFor("auto", ALL, set("wav"), PHRASE_MS, 7_000));
        assertEquals("flac", AudioPacker.chooseFor("auto", ALL, set("wav", "flac"), PHRASE_MS, 7_000));
        assertEquals("wav", AudioPacker.chooseFor("auto", Arrays.asList("wav"), set("wav", "opus"), PHRASE_MS, 7_000));
        assertEquals("opus", AudioPacker.chooseFor("opus", ALL, set("wav", "flac", "opus"), PHRASE_MS, 2_000_000));   // explicit wins
        assertEquals("wav", AudioPacker.chooseFor("opus", ALL, set("wav"), PHRASE_MS, 7_000));
    }

    @Test public void chooseFor_longPhraseMovesTheCrossoverTowardsOpus() {
        // 10 s of speech at 150 KB/s: WAV 2.1 s, FLAC 0.9 s, Opus 0.9 s... the larger the file the more a small one pays
        String shortPhrase = AudioPacker.chooseFor("auto", ALL, set("wav", "flac", "opus"), 1500, 150_000);
        String longPhrase = AudioPacker.chooseFor("auto", ALL, set("wav", "flac", "opus"), 15_000, 150_000);
        assertEquals("flac", shortPhrase);
        assertEquals("opus", longPhrase);
    }

    @Test public void packFor_followsTheMeasuredUplink() {
        PcmAudio a = new PcmAudio(pcm(96_000), 16000);   // 3 s
        AudioPacker spy = new AudioPacker() {
            String last = "";
            @Override public Set<String> available() { return set("wav", "flac", "opus"); }
            @Override public AudioPacker.Packed pack(PcmAudio audio, String format) { last = format; return AudioPacker.Packed.wav(audio, ""); }
            @Override public String toString() { return last; }
        };
        try {
            UplinkMeter.reset();
            spy.packFor(a, "auto", ALL);
            assertEquals("nothing measured: the default speed picks flac", "flac", spy.toString());
            UplinkMeter.record(240_000, 500 + 120);          // 240 KB in 120 ms of transfer = 2 MB/s
            UplinkMeter.record(240_000, 500 + 120);
            spy.packFor(a, "auto", ALL);
            assertEquals("a fast link sends wav", "wav", spy.toString());
            for (int i = 0; i < 4; i++) UplinkMeter.record(40_000, 500 + 5_000);   // 8 KB/s
            spy.packFor(a, "auto", ALL);
            assertEquals("a slow link switches to opus within a few requests", "opus", spy.toString());
        } finally {
            UplinkMeter.reset();
        }
    }

    @Test public void uplinkMeter_ignoresTinyBodiesAndSmoothsSamples() {
        UplinkMeter.reset();
        try {
            UplinkMeter.record(1_000, 900);
            assertTrue("a tiny body says nothing about the link", UplinkMeter.bytesPerSec() < 0);
            UplinkMeter.record(100_000, 500 + 1_000);        // 100 KB/s
            assertEquals(100_000, UplinkMeter.bytesPerSec(), 1.0);
            UplinkMeter.record(100_000, 500 + 100);          // one very fast sample: 1 MB/s
            double v = UplinkMeter.bytesPerSec();
            assertTrue("smoothed in the log domain, between the two samples: " + v, v > 100_000 && v < 1_000_000);
            UplinkMeter.record(100_000, 20);                 // faster than the fixed overhead: saturates, does not blow up
            assertTrue(UplinkMeter.bytesPerSec() < 5_000_001);
        } finally {
            UplinkMeter.reset();
        }
    }

    @Test public void wavOnlyPacker_packsWavBytes() {
        PcmAudio a = new PcmAudio(pcm(1600), 16000);
        AudioPacker.Packed p = AudioPacker.WAV_ONLY.packFor(a, "opus", ALL);
        assertEquals("wav", p.format);
        assertEquals("audio/wav", p.mime);
        assertEquals("audio.wav", p.fileName);
        assertArrayEquals(a.toWav(), p.bytes);
        assertEquals("", p.fellBackFrom);
    }

    @Test public void label_namesOpusAsOggOpus() {
        AudioPacker.Packed p = new AudioPacker.Packed("opus", "audio/ogg", "audio.ogg", new byte[11 * 1024], "");
        assertEquals("ogg/opus 11 КБ", p.label());
    }

    /** A packer that pretends to encode Opus, so the Groq request shows which container and name were chosen. */
    private static final AudioPacker OPUS_PACKER = new AudioPacker() {
        @Override public Set<String> available() { return set("wav", "opus"); }
        @Override public AudioPacker.Packed pack(PcmAudio audio, String format) {
            if ("opus".equals(format)) return new AudioPacker.Packed("opus", "audio/ogg", "audio.ogg", pcm(700), "");
            return AudioPacker.Packed.wav(audio, format);
        }
    };

    @Test public void groq_uploadsNegotiatedContainer_andReportsBytes() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            s.fallback = r -> {
                assertTrue("filename must match the container: " + r.bodyText(), r.bodyText().contains("filename=\"audio.ogg\""));
                assertTrue(r.bodyText().contains("Content-Type: audio/ogg"));
                assertArrayEquals(pcm(700), VoiceMockServer.multipartFileBytes(r.body, r.contentType, "file"));
                VoiceMockServer.json(r, 200, "{\"text\":\"привет\"}");
            };
            GroqStt stt = new GroqStt();
            ProviderConfig c = new ProviderConfig();
            c.put("apiKey", "gsk_test_000111222333444");
            c.put("baseUrl", s.url(""));
            stt.configure(c);
            SttOptions o = new SttOptions();
            o.packer = OPUS_PACKER;
            o.format = "opus";           // this test is about the container the provider receives; the "auto" policy has its own tests above
            SttResult r = stt.transcribe(new PcmAudio(pcm(1600), 16000), o, new CancelToken());
            assertEquals("привет", r.text);
            assertEquals("opus", r.uploadFormat);
            assertTrue("request body includes the 700-byte container plus multipart fields, got " + r.uploadedBytes,
                r.uploadedBytes > 700 && r.uploadedBytes < 1200);
        }
    }

    @Test public void groq_formatWav_whenPreferredWavAndDefaultPacker() throws Exception {
        try (VoiceMockServer s = new VoiceMockServer()) {
            s.fallback = r -> {
                assertTrue(r.bodyText().contains("filename=\"audio.wav\""));
                VoiceMockServer.json(r, 200, "{\"text\":\"ок\"}");
            };
            GroqStt stt = new GroqStt();
            ProviderConfig c = new ProviderConfig();
            c.put("apiKey", "gsk_test_000111222333444");
            c.put("baseUrl", s.url(""));
            stt.configure(c);
            SttOptions o = new SttOptions();
            o.format = "wav";
            SttResult r = stt.transcribe(new PcmAudio(pcm(1600), 16000), o, new CancelToken());
            assertEquals("wav", r.uploadFormat);
            assertTrue("request body includes the 1644-byte WAV plus multipart fields, got " + r.uploadedBytes,
                r.uploadedBytes > 44 + 1600);
        }
    }
}
