package app.jarvis.core.audio;

import java.util.ArrayList;
import java.util.List;

/**
 * Streaming MP3 frame splitter for MediaCodec: consumes arbitrary byte chunks (possibly with garbage or a leading
 * ID3v2 tag) and emits complete, valid MPEG audio frames. Tolerant to chunk boundaries anywhere including inside a
 * candidate header; non-frame bytes are skipped one byte at a time until the stream re-syncs.
 *
 * Validation of a sync candidate: full header parse (version, layer, bitrate index, sample-rate index) plus, when
 * enough bytes are buffered, a check that the next frame also begins with a valid header. This rejects isolated
 * 0xFF 0xEx sequences inside ID3 art or text payloads.
 */
public final class Mp3FrameSplitter {

    /** Receives complete frames in stream order. sampleRate/channels come from the frame header of that frame. */
    public interface Listener {
        void onFrame(byte[] buf, int off, int len, int sampleRate, int channels);
    }

    // Bitrates (kbit/s) indexed [versionGroup][layerIndex1..3][bitrateIndex1..14]; versionGroup: 0=MPEG1, 1=MPEG2/2.5
    private static final int[][][] BITRATES = {
        { // MPEG1
            {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 0}, // Layer I
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0},    // Layer II
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 0}},    // Layer III
        { // MPEG2 & MPEG2.5
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 0},   // Layer I
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 0},        // Layer II
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 0}}};      // Layer III
    private static final int[] RATES_MPEG1 = {44100, 48000, 32000};
    private static final int[] RATES_MPEG2 = {22050, 24000, 16000};
    private static final int[] RATES_MPEG25 = {11025, 12000, 8000};
    private static final int[] SAMPLES_PER_FRAME_MPEG1 = {384, 1152, 1152}; // per layer I, II, III
    private static final int[] SAMPLES_PER_FRAME_MPEG2 = {384, 1152, 576};

    /** One parsed header; also the frame length in bytes. */
    public static final class FrameInfo {
        public int length, sampleRate, channels, layer;
        public long samplesPerFrame;
    }

    private final Listener listener;
    private byte[] buf = new byte[8192];
    private int start, end; // valid data is buf[start..end)
    private boolean id3Done;
    private int framesEmitted;
    private long totalSamples;
    private long skippedBytes;
    public int lastSampleRate, lastChannels, lastLayer;

    public Mp3FrameSplitter(Listener listener) { this.listener = listener; }

    public void feed(byte[] b, int off, int len) {
        if (end + len > buf.length) { // move pending data to the front, grow if still not enough
            System.arraycopy(buf, start, buf, 0, end - start);
            end -= start; start = 0;
            if (end + len > buf.length) {
                byte[] n = new byte[Math.max(buf.length * 2, end + len)];
                System.arraycopy(buf, 0, n, 0, end);
                buf = n;
            }
        }
        System.arraycopy(b, off, buf, end, len);
        end += len;
        process();
    }

    /** Call at end of stream: discards a trailing partial frame. */
    public void end() { process(); start = end = 0; }

    public int frames() { return framesEmitted; }

    public long totalSamples() { return totalSamples; }

    /** Bytes consumed that were not part of any frame (garbage, ID3 tag). */
    public long skippedBytes() { return skippedBytes; }

    private void process() {
        if (!id3Done) { start = skipId3(start); if (!id3Done) return; } // tag (or its size) not buffered yet
        while (true) {
            if (end - start < 4) return;
            FrameInfo f = parseHeader(buf, start);
            if (f == null) { start++; skippedBytes++; continue; }
            boolean complete = end - start >= f.length;
            boolean look = lookaheadOk(start, f);
            // a frame whose own header is valid is accepted when the next header checks out, or when the stream
            // format is already established (garbage between frames must not cost us a frame); a lone header with
            // no established stream (sync pair inside junk) is skipped
            boolean established = framesEmitted > 0 && f.sampleRate == lastSampleRate && f.channels == lastChannels;
            if (!complete) {
                if (!look) { start++; skippedBytes++; continue; }
                return;
            }
            if (!look && !established) { start++; skippedBytes++; continue; }
            listener.onFrame(buf, start, f.length, f.sampleRate, f.channels);
            framesEmitted++;
            totalSamples += f.samplesPerFrame;
            lastSampleRate = f.sampleRate; lastChannels = f.channels; lastLayer = f.layer;
            start += f.length;
        }
    }

    /** Second-header check: the next frame must parse too (guards against lone 0xFF 0xE0 inside garbage). */
    private boolean lookaheadOk(int pos, FrameInfo f) {
        int next = pos + f.length;
        if (end - next < 4) return true; // not enough data yet: tentatively accept
        return parseHeader(buf, next) != null;
    }

    private int skipId3(int pos) {
        if (end - pos < 10) return pos;
        if (buf[pos] != 'I' || buf[pos + 1] != 'D' || buf[pos + 2] != '3') { id3Done = true; return pos; }
        boolean footer = (buf[pos + 5] & 0x10) != 0;
        long size = ((buf[pos + 6] & 0x7fL) << 21) | ((buf[pos + 7] & 0x7fL) << 14) | ((buf[pos + 8] & 0x7fL) << 7) | (buf[pos + 9] & 0x7fL);
        long total = 10 + size + (footer ? 10 : 0);
        if (end - pos < total) return pos; // wait for the whole tag
        skippedBytes += total;
        id3Done = true;
        return pos + (int) total;
    }

    /** Parses a full MPEG audio header at pos; null if not a valid frame start. */
    public static FrameInfo parseHeader(byte[] b, int pos) {
        if (b[pos] != (byte) 0xFF || (b[pos + 1] & 0xE0) != 0xE0) return null;
        int versionBits = (b[pos + 1] >> 3) & 3;   // 0=MPEG2.5 1=res 2=MPEG2 3=MPEG1
        int layerBits = (b[pos + 1] >> 1) & 3;     // 0=res 1=III 2=II 3=I
        if (versionBits == 1 || layerBits == 0) return null;
        int bitrateIdx = (b[pos + 2] >> 4) & 0xF;
        int rateIdx = (b[pos + 2] >> 2) & 3;
        if (bitrateIdx == 0 || bitrateIdx == 15 || rateIdx == 3) return null;
        int versionGroup = versionBits == 3 ? 0 : 1;
        int layerIdx = 4 - layerBits; // I->1 II->2 III->3 (array index)
        int kbps = BITRATES[versionGroup][layerIdx - 1][bitrateIdx];
        int rate = versionBits == 3 ? RATES_MPEG1[rateIdx] : versionBits == 2 ? RATES_MPEG2[rateIdx] : RATES_MPEG25[rateIdx];
        int channels = ((b[pos + 3] >> 6) & 3) == 3 ? 1 : 2;
        int padding = (b[pos + 2] >> 1) & 1;
        int size;
        if (layerIdx == 1) size = (12 * kbps * 1000 / rate + padding) * 4;         // Layer I: 4-byte slots
        else if (versionGroup == 0) size = 144 * kbps * 1000 / rate + padding;     // MPEG1 Layer II/III
        else size = (layerIdx == 3 ? 72 : 144) * kbps * 1000 / rate + padding;     // MPEG2/2.5 Layer III: 576 samples
        if (size < 24 || size > 4096) return null;
        FrameInfo f = new FrameInfo();
        f.length = size;
        f.sampleRate = rate;
        f.channels = channels;
        f.layer = layerIdx;
        f.samplesPerFrame = versionGroup == 0 ? SAMPLES_PER_FRAME_MPEG1[layerIdx - 1] : SAMPLES_PER_FRAME_MPEG2[layerIdx - 1];
        return f;
    }

    /** Convenience for tests/tools: split a whole buffer, return the frames as a list of copies. */
    public static List<byte[]> splitAll(byte[] data) {
        final List<byte[]> out = new ArrayList<>();
        Mp3FrameSplitter s = new Mp3FrameSplitter(new Listener() {
            @Override public void onFrame(byte[] buf, int off, int len, int sampleRate, int channels) {
                byte[] c = new byte[len];
                System.arraycopy(buf, off, c, 0, len);
                out.add(c);
            }
        });
        s.feed(data, 0, data.length);
        s.end();
        return out;
    }
}
