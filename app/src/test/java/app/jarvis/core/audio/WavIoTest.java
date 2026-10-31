package app.jarvis.core.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import app.jarvis.core.voice.PcmAudio;
import java.io.ByteArrayOutputStream;
import org.junit.Test;

public class WavIoTest {
    @Test public void headerIsFortyFourBytesAndDescribesThePcm() {
        byte[] h = WavIo.header(16000, 1, 3200);
        assertEquals(WavIo.HEADER_BYTES, h.length);
        assertEquals("RIFF", new String(h, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(h, 8, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals("data", new String(h, 36, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(3236, le32(h, 4));
        assertEquals(3200, le32(h, 40));
        assertEquals(32000, le32(h, 28));
    }

    @Test public void streamedHeaderParsesBackToTheSamples() {
        short[] s = {0, 100, -100, 32000, -32000};
        byte[] pcm = PcmConvert.toBytes(s);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(WavIo.header(22050, 1, pcm.length), 0, WavIo.HEADER_BYTES);
        o.write(pcm, 0, pcm.length);
        PcmAudio a = PcmAudio.fromWav(o.toByteArray());
        assertNotNull(a);
        assertEquals(22050, a.sampleRate);
        assertArrayEquals(pcm, a.pcm);
    }

    @Test public void nonWavIsNotParsed() {
        assertNull(PcmAudio.fromWav(new byte[100]));
    }

    private static int le32(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
    }
}
