package app.jarvis.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VadTest {

    private static final int FRAME = 480; // 30 ms at 16 kHz

    private static int run(short[] pcm, boolean[] speech) {
        Vad vad = new Vad();
        int voiced = 0;
        for (int off = 0; off + FRAME <= pcm.length; off += FRAME) {
            Vad.Result r = vad.process(pcm, off, FRAME);
            if (r.speech) voiced++;
            if (speech != null) speech[off / FRAME] = r.speech;
        }
        return voiced;
    }

    @Test public void silenceNeverTriggers() {
        assertEquals(0, run(TestAudio.silenceMs(5000), null));
    }

    @Test public void constantNoiseDoesNotTrigger() {
        for (double dbfs : new double[]{-45, -35, -30}) assertEquals(0, run(TestAudio.noise(TestAudio.RATE * 6, dbfs, 1), null));
    }

    @Test public void tonalHumDoesNotTrigger() {
        assertEquals(0, run(TestAudio.sine(TestAudio.RATE * 6, 100, -30, TestAudio.RATE), null));
        assertEquals(0, run(TestAudio.sine(TestAudio.RATE * 6, 50, -24, TestAudio.RATE), null));
    }

    @Test public void slowlyRisingNoiseDoesNotTrigger() {
        // from -55 to -15 dBFS over 10 s (~4 dB/s): the floor must keep up
        int total = TestAudio.RATE * 10;
        short[] pcm = new short[total];
        int pos = 0, seg = FRAME;
        while (pos < total) {
            double dbfs = -55 + 40.0 * pos / total;
            short[] part = TestAudio.noise(Math.min(seg, total - pos), dbfs, 1000 + pos);
            System.arraycopy(part, 0, pcm, pos, part.length);
            pos += part.length;
        }
        assertEquals(0, run(pcm, null));
    }

    @Test public void realSpeechTriggers() throws Exception {
        short[] speech = TestAudio.speech16k();
        boolean[] flags = new boolean[speech.length / FRAME + 1];
        int voiced = run(speech, flags);
        assertTrue("voiced frames " + voiced, voiced > 20);
        assertTrue("speech must be detected in the first second", flags[5] || flags[10] || flags[20]);
    }

    @Test public void speechOverModerateNoiseStillTriggers() throws Exception {
        short[] mixed = TestAudio.concat(
            TestAudio.noise(TestAudio.RATE, -35, 2),
            TestAudio.speech16k(),
            TestAudio.noise(TestAudio.RATE, -35, 3));
        assertTrue(run(mixed, null) > 20);
    }

    @Test public void resetClearsTheFloor() {
        Vad vad = new Vad();
        for (int off = 0; off + FRAME <= 2 * TestAudio.RATE; off += FRAME) vad.process(TestAudio.noise(FRAME, -20, 5), 0, FRAME);
        assertTrue("floor must have risen to the loud noise: " + vad.noiseFloor(), vad.noiseFloor() > 1000);
        vad.reset();
        assertFalse(vad.noiseFloor() > 1000);
    }
}
