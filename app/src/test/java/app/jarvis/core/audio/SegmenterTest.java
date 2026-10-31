package app.jarvis.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.voice.PcmAudio;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class SegmenterTest {

    private static final int RATE = 16000;

    private static final class Driver implements Segmenter.Listener {
        final List<PcmAudio> utterances = new ArrayList<>();
        final List<Long> starts = new ArrayList<>();
        final List<long[]> meta = new ArrayList<>(); // {startMs, endMs, truncated}
        float lastLevel;
        int levelCalls;

        @Override public void onSpeechStart() { starts.add(0L); }

        @Override public void onLevel(float level) { lastLevel = level; levelCalls++; }

        @Override public void onUtterance(PcmAudio audio, long startMs, long endMs, boolean truncated) {
            utterances.add(audio);
            meta.add(new long[]{startMs, endMs, truncated ? 1 : 0});
        }

        void feed(Segmenter s, short[] pcm) { s.feed(pcm, pcm.length); }
    }

    private static Segmenter newSegmenter(Driver d) {
        return new Segmenter(new Segmenter.Config(), d);
    }

    @Test public void speechBetweenSilenceGivesOneUtterance() throws Exception {
        short[] speech = TestAudio.speech16k(1600, 3200); // one continuous sentence, no internal pauses
        Driver d = new Driver();
        Segmenter s = newSegmenter(d);
        d.feed(s, TestAudio.concat(TestAudio.silenceMs(800), speech, TestAudio.silenceMs(1500)));
        assertEquals(1, d.utterances.size());
        assertFalse(d.meta.get(0)[2] == 1);
        assertEquals("utterance ≈ preroll + speech + tail", 2700, d.utterances.get(0).durationMs(), 500);
        assertEquals(1, d.starts.size());
    }

    @Test public void constantNoiseProducesNothing() {
        for (double dbfs : new double[]{-45, -35, -30}) {
            Driver d = new Driver();
            Segmenter s = newSegmenter(d);
            d.feed(s, TestAudio.noise(RATE * 6, dbfs, 7));
            assertEquals("dbfs " + dbfs, 0, d.utterances.size());
        }
    }

    @Test public void twoUtterancesSeparatedBy900ms() throws Exception {
        short[] speech = TestAudio.speech16k(1600, 3200);
        Driver d = new Driver();
        Segmenter s = newSegmenter(d);
        d.feed(s, TestAudio.concat(
            TestAudio.silenceMs(500), speech,
            TestAudio.silenceMs(900),
            speech, TestAudio.silenceMs(1200)));
        assertEquals(2, d.utterances.size());
        assertEquals(2, d.starts.size());
    }

    @Test public void twoUtterances300msApartStayOne() throws Exception {
        short[] speech = TestAudio.speech16k(1600, 3200);
        Driver d = new Driver();
        Segmenter s = newSegmenter(d);
        d.feed(s, TestAudio.concat(
            TestAudio.silenceMs(500), speech,
            TestAudio.silenceMs(300),
            speech, TestAudio.silenceMs(1200)));
        assertEquals(1, d.utterances.size());
        assertTrue(d.utterances.get(0).durationMs() > 2 * 1400);
    }

    /** The first 100 ms of the word must be present in the emitted audio (preroll works). */
    @Test public void startOfSpeechIsPreserved() throws Exception {
        short[] speech = TestAudio.speech16k(1600, 3200); // begins with the word onset
        Driver d = new Driver();
        Segmenter s = newSegmenter(d);
        d.feed(s, TestAudio.concat(TestAudio.silenceMs(600), speech, TestAudio.silenceMs(1500)));
        assertEquals(1, d.utterances.size());
        byte[] out = d.utterances.get(0).pcm;
        byte[] head = new byte[100 * 32]; // 100 ms of 16 kHz mono s16le
        System.arraycopy(TestAudio.le(speech), 0, head, 0, head.length);
        assertTrue("first 100 ms of speech must appear in the utterance", contains(out, head));
    }

    @Test public void prerollCoversLessThanItsLengthAtStreamStart() throws Exception {
        short[] speech = TestAudio.speech16k(1600, 3200); // ~150 ms of leading silence inside the slice
        Driver d = new Driver();
        Segmenter s = newSegmenter(d);
        d.feed(s, TestAudio.concat(TestAudio.silenceMs(400), speech, TestAudio.silenceMs(1200)));
        assertEquals(1, d.utterances.size());
        // onset at ~550 ms with 450 ms preroll → the utterance begins near stream position 100 ms; speech runs to
        // the end of the slice (the resource is a trimmed file), then the 650 ms tail closes it
        assertTrue("onset ms " + d.meta.get(0)[0], d.meta.get(0)[0] >= 380 && d.meta.get(0)[0] < 750);
        assertEquals("all leading audio is kept", 450 + 1340 + 650, d.utterances.get(0).durationMs(), 300);
    }

    @Test public void longSpeechIsTruncatedAtMax() throws Exception {
        Driver d = new Driver();
        Segmenter s = newSegmenter(d);
        d.feed(s, TestAudio.concat(TestAudio.silenceMs(600), TestAudio.speechLike(16000)));
        assertEquals(1, d.utterances.size());
        assertTrue("must be marked truncated", d.meta.get(0)[2] == 1);
        assertTrue("duration ≈ maxUtteranceMs", d.utterances.get(0).durationMs() >= 13900 && d.utterances.get(0).durationMs() <= 15000);
    }

    @Test public void utteranceJustOverMinSpeechIsKept() throws Exception {
        Driver d = new Driver();
        Segmenter s = newSegmenter(d);
        d.feed(s, TestAudio.concat(
            TestAudio.silenceMs(500),
            TestAudio.sine(RATE / 4, 440, -6, RATE), // 250 ms ≥ minSpeechMs
            TestAudio.silenceMs(1200)));
        assertEquals(1, d.utterances.size());
    }

    @Test public void blipShorterThanMinSpeechIsDropped() throws Exception {
        Driver d = new Driver();
        Segmenter s = newSegmenter(d);
        d.feed(s, TestAudio.concat(
            TestAudio.silenceMs(500),
            TestAudio.sine(3 * RATE / 20, 440, -6, RATE), // 150 ms < 220 ms minSpeechMs
            TestAudio.silenceMs(1500)));
        assertEquals(0, d.utterances.size());
        assertEquals(0, d.starts.size());
    }

    @Test public void disableClearsStateAndSymmetryHolds() throws Exception {
        short[] speech = TestAudio.speech16k(1600, 3200);
        short[] firstSecond = new short[TestAudio.RATE];
        System.arraycopy(speech, 0, firstSecond, 0, firstSecond.length);
        Driver d = new Driver();
        Segmenter s = newSegmenter(d);
        d.feed(s, TestAudio.concat(TestAudio.silenceMs(500), firstSecond)); // speech starts; no trailing silence yet
        s.setEnabled(false); // assistant starts talking
        d.feed(s, speech);   // must be ignored completely
        assertTrue(d.utterances.isEmpty());
        s.setEnabled(true);  // listening again
        d.feed(s, TestAudio.concat(TestAudio.silenceMs(200), speech, TestAudio.silenceMs(1500)));
        assertEquals(1, d.utterances.size());
        assertEquals("every utterance had exactly one speechStart (after clear)", 2, d.starts.size());
    }

    @Test public void levelGoesQuietAtTheEnd() throws Exception {
        Driver d = new Driver();
        Segmenter s = newSegmenter(d);
        d.feed(s, TestAudio.concat(TestAudio.silenceMs(500), TestAudio.speech16k(), TestAudio.silenceMs(1200)));
        assertTrue(d.levelCalls > 50);
        assertEquals(0f, d.lastLevel, 0.0001f);
    }

    private static boolean contains(byte[] hay, byte[] needle) {
        outer: for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    @Test public void defaultEndSilenceIs550AndDeliveryComesThatMuchAfterTheVoice() throws Exception {
        assertEquals(550, new Segmenter.Config().endSilenceMs);
        short[] speech = TestAudio.speech16k(1600, 3200);
        for (int silence : new int[]{450, 550, 650}) {
            Driver d = new Driver();
            Segmenter.Config c = new Segmenter.Config();
            c.endSilenceMs = silence;
            Segmenter s = new Segmenter(c, d);
            long fedBeforeTail = 0;
            short[] lead = TestAudio.silenceMs(600);
            d.feed(s, lead);
            d.feed(s, speech);
            fedBeforeTail = (lead.length + speech.length) * 1000L / RATE;
            assertEquals("not yet delivered right after the voice, silence " + silence, 0, d.utterances.size());
            d.feed(s, TestAudio.silenceMs(silence + 60));
            assertEquals(1, d.utterances.size());
            long endMs = d.meta.get(0)[1];
            assertEquals("endMs marks the end of the voice for silence " + silence, fedBeforeTail, endMs, 90);
        }
    }
}
