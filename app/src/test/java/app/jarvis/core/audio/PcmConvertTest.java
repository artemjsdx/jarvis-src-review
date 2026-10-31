package app.jarvis.core.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

import org.junit.Test;

public class PcmConvertTest {
    @Test public void stereoIsAveraged() {
        assertArrayEquals(new short[]{150, 400}, PcmConvert.toMono(new short[]{100, 200, 300, 500}, 2));
    }

    @Test public void monoPassesThrough() {
        assertArrayEquals(new short[]{1, -2, 3}, PcmConvert.toMono(new short[]{1, -2, 3}, 1));
    }

    @Test public void shrinkingBoxAveragesAndKeepsLevel() {
        short[] in = new short[480];
        java.util.Arrays.fill(in, (short) 1000);
        short[] out = PcmConvert.resample(in, 48000, 16000);
        assertEquals(160, out.length);
        for (short s : out) assertEquals(1000, s);
    }

    @Test public void growingInterpolatesALine() {
        short[] out = PcmConvert.resample(new short[]{0, 100, 200}, 8000, 16000);
        assertEquals(6, out.length);
        assertEquals(0, out[0]);
        assertEquals(50, out[1]);
        assertEquals(100, out[2]);
    }

    @Test public void sameRateIsACopy() {
        short[] in = {5, 6, 7};
        short[] out = PcmConvert.resample(in, 16000, 16000);
        assertArrayEquals(in, out);
        assertNotSame(in, out);
    }

    @Test public void stereo44kTo16kMono() {
        short[] stereo = new short[44100 * 2];
        java.util.Arrays.fill(stereo, (short) 300);
        short[] out = PcmConvert.to16kMono(stereo, 44100, 2);
        assertEquals(16000, out.length);
        assertEquals(300, out[10]);
    }

    @Test public void bytesRoundTrip() {
        short[] s = {0, 1, -1, 32767, -32768, 1234};
        assertArrayEquals(s, PcmConvert.fromBytes(PcmConvert.toBytes(s)));
        assertEquals(2, PcmConvert.toBytes(new short[]{7}).length);
    }
}
