package app.jarvis.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class Mp3FrameSplitterTest {

    private static List<byte[]> split(byte[] data, int chunkSize) {
        final List<byte[]> out = new ArrayList<>();
        Mp3FrameSplitter s = new Mp3FrameSplitter(new Mp3FrameSplitter.Listener() {
            @Override public void onFrame(byte[] buf, int off, int len, int sampleRate, int channels) {
                byte[] c = new byte[len];
                System.arraycopy(buf, off, c, 0, len);
                out.add(c);
            }
        });
        for (int i = 0; i < data.length; i += chunkSize) s.feed(data, i, Math.min(chunkSize, data.length - i));
        s.end();
        return out;
    }

    private static byte[] join(List<byte[]> frames) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] f : frames) o.write(f, 0, f.length);
        return o.toByteArray();
    }

    private static void assertSameFrames(List<byte[]> expected, List<byte[]> actual) {
        assertEquals("frame count", expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals("frame " + i + " length", expected.get(i).length, actual.get(i).length);
            assertTrue(eq(expected.get(i), actual.get(i)));
        }
    }

    /** Bytes after the ID3v2 tag (ElevenLabs files carry one; frames must reassemble to the rest). */
    private static byte[] withoutId3(byte[] mp3) {
        if (mp3[0] != 'I' || mp3[1] != 'D' || mp3[2] != '3') return mp3;
        long size = ((mp3[6] & 0x7fL) << 21) | ((mp3[7] & 0x7fL) << 14) | ((mp3[8] & 0x7fL) << 7) | (mp3[9] & 0x7fL);
        int skip = 10 + (int) size + ((mp3[5] & 0x10) != 0 ? 10 : 0);
        byte[] out = new byte[mp3.length - skip];
        System.arraycopy(mp3, skip, out, 0, out.length);
        return out;
    }

    @Test public void realElevenLabsFileSplitsAndReassembles() throws Exception {
        byte[] mp3 = TestAudio.resource("speech22k.mp3");
        List<byte[]> frames = split(mp3, 4096);
        assertTrue("expected ~54 frames, got " + frames.size(), frames.size() > 40);
        // duration from frame count must match ffprobe (1.4105 s) within 1%
        double dur = frames.size() * 576.0 / 22050.0;
        assertEquals(1.4105, dur, 0.015);
        assertTrue("frames must reassemble to the file body", eq(join(frames), withoutId3(mp3)));
        assertEquals("skipped = id3 tag only", mp3.length - withoutId3(mp3).length, splitMeta(mp3).skippedBytes());
    }

    private static Mp3FrameSplitter splitMeta(byte[] data) {
        final Mp3FrameSplitter s = new Mp3FrameSplitter(new Mp3FrameSplitter.Listener() {
            @Override public void onFrame(byte[] buf, int off, int len, int sampleRate, int channels) { }
        });
        s.feed(data, 0, data.length);
        s.end();
        return s;
    }

    @Test public void byteByByteFeedingGivesTheSameFrames() throws Exception {
        byte[] mp3 = TestAudio.resource("speech22k.mp3");
        assertSameFrames(split(mp3, 1 << 20), split(mp3, 1));
    }

    @Test public void oddChunkBoundariesGiveTheSameFrames() throws Exception {
        byte[] mp3 = TestAudio.resource("speech22k.mp3");
        for (int chunk : new int[]{777, 3, 513, 1024}) assertSameFrames(split(mp3, 1 << 20), split(mp3, chunk));
    }

    @Test public void stereo48kFileSplits() throws Exception {
        byte[] mp3 = TestAudio.resource("speech48st.mp3");
        List<byte[]> frames = split(mp3, 777);
        assertTrue("frames " + frames.size(), frames.size() > 30); // 1 s of MPEG1 L3 48 kHz ≈ 38.3 frames
        double dur = frames.size() * 1152.0 / 48000.0;
        assertEquals("mp3 frame count includes the encoder delay/padding", 1.0, dur, 0.08);
        assertEquals(2, splitMeta(mp3).lastChannels);
        assertEquals(48000, splitMeta(mp3).lastSampleRate);
    }

    @Test public void garbageAroundAndInsideTheStreamIsSkipped() throws Exception {
        byte[] mp3 = withoutId3(TestAudio.resource("speech22k.mp3"));
        List<byte[]> frames = split(mp3, 4096);
        int half = join(frames.subList(0, frames.size() / 2)).length; // cut exactly at a frame boundary
        Random r = new Random(42);
        byte[] pre = new byte[500], mid = new byte[300];
        r.nextBytes(pre); r.nextBytes(mid);
        pre[10] = (byte) 0xFF; pre[11] = (byte) 0xFB; pre[12] = (byte) 0x90; // lone sync-looking trio in garbage
        byte[] mixed = new byte[pre.length + mid.length + mp3.length];
        System.arraycopy(pre, 0, mixed, 0, pre.length);
        System.arraycopy(mp3, 0, mixed, pre.length, half);
        System.arraycopy(mid, 0, mixed, pre.length + half, mid.length);
        System.arraycopy(mp3, half, mixed, pre.length + half + mid.length, mp3.length - half);
        assertSameFrames(frames, split(mixed, 777));
        assertEquals(pre.length + mid.length, splitMeta(mixed).skippedBytes());
    }

    @Test public void id3v2TagIsSkippedEvenWithFakeSyncInside() throws Exception {
        byte[] mp3 = withoutId3(TestAudio.resource("speech22k.mp3"));
        byte[] body = new byte[1000];
        body[500] = (byte) 0xFF; body[501] = (byte) 0xFB; body[502] = (byte) 0x90; // fake frame header inside the tag
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write("ID3".getBytes());
        o.write(new byte[]{4, 0, 0}); // version 2.4, no flags
        o.write(syncsafe(body.length));
        o.write(body);
        o.write(mp3);
        assertSameFrames(split(mp3, 777), split(o.toByteArray(), 251));
        Mp3FrameSplitter s = splitMeta(o.toByteArray());
        assertEquals(1010, s.skippedBytes()); // 10-byte tag header + 1000-byte body
    }

    private static byte[] syncsafe(int v) {
        return new byte[]{(byte) ((v >> 21) & 0x7f), (byte) ((v >> 14) & 0x7f), (byte) (v >> 7 & 0x7f), (byte) (v & 0x7f)};
    }

    @Test public void truncatedTailDoesNotEmitPartialFrame() throws Exception {
        byte[] mp3 = withoutId3(TestAudio.resource("speech22k.mp3"));
        List<byte[]> full = split(mp3, 4096);
        int cut = mp3.length - full.get(full.size() - 1).length / 2; // cut inside the last frame
        List<byte[]> got = split(java.util.Arrays.copyOf(mp3, cut), 97);
        assertEquals(full.size() - 1, got.size());
        for (int i = 0; i < got.size(); i++) assertTrue(eq(got.get(i), full.get(i)));
    }

    @Test public void headerParserKnowsTypicalFormats() {
        // MPEG1 Layer III, 128 kbit/s, 44100 Hz, stereo, no padding → 144*128000/44100 = 417
        byte[] h = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x00};
        Mp3FrameSplitter.FrameInfo f = Mp3FrameSplitter.parseHeader(h, 0);
        assertNotNull(f);
        assertEquals(44100, f.sampleRate);
        assertEquals(2, f.channels);
        assertEquals(417, f.length);
        assertEquals(1152, f.samplesPerFrame);
        // same with padding → 418
        h[2] = (byte) 0x92;
        assertEquals(418, Mp3FrameSplitter.parseHeader(h, 0).length);
        // MPEG2 Layer III (0xF3), 80 kbit/s (bitrateIdx 9), 16000 Hz (rateIdx 2 → bits 3..2 = "10", byte 0x98), mono (0xC4) → 72*80000/16000 = 360
        byte[] h2 = {(byte) 0xFF, (byte) 0xF3, (byte) 0x98, (byte) 0xC4};
        f = Mp3FrameSplitter.parseHeader(h2, 0);
        assertEquals(16000, f.sampleRate);
        assertEquals(1, f.channels);
        assertEquals(360, f.length);
        assertEquals(576, f.samplesPerFrame);
        // reserved version bits and reserved layer are rejected
        assertNull(Mp3FrameSplitter.parseHeader(new byte[]{(byte) 0xFF, (byte) 0x70, 0x00, 0x00}, 0));
        assertNull(Mp3FrameSplitter.parseHeader(new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, 0x00}, 0)); // layer 0 reserved
        assertNull(Mp3FrameSplitter.parseHeader(new byte[]{(byte) 0xFF, (byte) 0xFB, (byte) 0xF0, 0x00}, 0)); // bitrateIdx 15
        assertNull(Mp3FrameSplitter.parseHeader(new byte[]{(byte) 0xFF, (byte) 0xFB, 0x0C, 0x00}, 0)); // rateIdx 3 reserved
    }

    private static boolean eq(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }
}
