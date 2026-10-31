package app.jarvis.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.jarvis.core.voice.PcmAudio;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public class WavStreamTest {

    private static final class Out implements WavStream.Listener {
        int rate = -1, channels;
        long dataSize;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();

        @Override public void onFormat(int sampleRate, int ch, long dataSizeBytes) {
            rate = sampleRate; channels = ch; dataSize = dataSizeBytes;
        }

        @Override public void onPcm(byte[] buf, int off, int len) { pcm.write(buf, off, len); }
    }

    private static Out feed(byte[] wav, int chunk) throws IOException {
        Out o = new Out();
        WavStream w = new WavStream(o);
        for (int i = 0; i < wav.length; i += chunk) w.feed(wav, i, Math.min(chunk, wav.length - i));
        w.end();
        return o;
    }

    private static byte[] stereoWav(short[] l, short[] r, int rate) {
        ByteBuffer d = ByteBuffer.allocate(l.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < l.length; i++) { d.putShort(l[i]); d.putShort(r[i]); }
        byte[] data = d.array();
        ByteBuffer b = ByteBuffer.allocate(44 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + data.length).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16)
            .putShort((short) 1).putShort((short) 2).putInt(rate).putInt(rate * 4).putShort((short) 4).putShort((short) 16)
            .put("data".getBytes()).putInt(data.length).put(data);
        return b.array();
    }

    private static byte[] withListChunk(byte[] wav) { // LIST chunk inserted before data
        byte[] list = new byte[8 + 26];
        System.arraycopy("LIST".getBytes(), 0, list, 0, 4);
        list[4] = 26;
        System.arraycopy("INFOjarvis".getBytes(), 0, list, 8, 10);
        byte[] out = new byte[wav.length + list.length];
        System.arraycopy(wav, 0, out, 0, 36);
        System.arraycopy(list, 0, out, 36, list.length);
        System.arraycopy(wav, 36, out, 36 + list.length, wav.length - 36);
        return out;
    }

    @Test public void monoWavParsesAndReassemblesInOddChunks() throws Exception {
        short[] pcm = TestAudio.sine(8000, 440, -10, 16000);
        byte[] wav = new PcmAudio(TestAudio.le(pcm), 16000).toWav();
        for (int chunk : new int[]{777, 1, 3, 4096}) {
            Out o = feed(wav, chunk);
            assertEquals(16000, o.rate);
            assertEquals(1, o.channels);
            assertEquals(pcm.length * 2, o.dataSize);
            assertTrue(eqBytes(TestAudio.le(pcm), o.pcm.toByteArray()));
        }
    }

    @Test public void stereoWavParses() throws Exception {
        short[] l = TestAudio.sine(4000, 440, -10, 16000), r = TestAudio.sine(4000, 880, -10, 16000);
        Out o = feed(stereoWav(l, r, 16000), 777);
        assertEquals(2, o.channels);
        assertEquals(l.length * 4, o.pcm.size());
        assertEquals(16000, o.rate);
    }

    @Test public void unknownDataSizesStreamUntilTheEnd() throws Exception {
        short[] pcm = TestAudio.sine(4000, 440, -10, 16000);
        byte[] wav = new PcmAudio(TestAudio.le(pcm), 16000).toWav();
        for (long fake : new long[]{0, 0xFFFFFFFFL}) {
            wav[40] = (byte) fake; wav[41] = (byte) (fake >> 8); wav[42] = (byte) (fake >> 16); wav[43] = (byte) (fake >> 24);
            Out o = feed(wav, 997);
            assertEquals(-1, o.dataSize);
            assertEquals(pcm.length * 2, o.pcm.size());
        }
    }

    @Test public void declaredDataSizeTruncatesExtraBytes() throws Exception {
        short[] pcm = TestAudio.sine(4000, 440, -10, 16000);
        byte[] wav = new PcmAudio(TestAudio.le(pcm), 16000).toWav();
        byte[] longer = new byte[wav.length + 100];
        System.arraycopy(wav, 0, longer, 0, wav.length);
        Out o = feed(longer, 777);
        assertEquals(pcm.length * 2, o.pcm.size());
    }

    @Test public void listChunkBeforeDataIsSkipped() throws Exception {
        short[] pcm = TestAudio.sine(4000, 440, -10, 16000);
        Out o = feed(withListChunk(new PcmAudio(TestAudio.le(pcm), 16000).toWav()), 333);
        assertEquals(pcm.length * 2, o.pcm.size());
        assertEquals(16000, o.rate);
    }

    @Test public void nonPcmOrGarbageIsRejected() throws Exception {
        byte[] wav = new PcmAudio(TestAudio.le(TestAudio.sine(100, 440, -10, 16000)), 16000).toWav();
        wav[20] = 3; wav[21] = 0; // format tag 3 = IEEE float
        try { feed(wav, 777); fail("float wav must be rejected"); } catch (IOException expected) { }
        try { feed("not a wav at all........".getBytes(), 4); fail(); } catch (IOException expected) { }
    }

    private static boolean eqBytes(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }
}
