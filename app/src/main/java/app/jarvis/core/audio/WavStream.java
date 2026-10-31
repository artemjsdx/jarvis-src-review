package app.jarvis.core.audio;

import java.io.IOException;

/**
 * Incremental RIFF/WAVE header parser for a streaming byte source (header and PCM arrive in arbitrary chunks).
 * Extracts the format and hands raw PCM payload bytes to the caller. Tolerates streaming writers that announce a
 * data size of 0 or 0xFFFFFFFF ("unknown") — PCM then flows until end of stream. Only PCM16 (mono/stereo) is
 * supported. Header chunks are buffered in a small buffer; once the data chunk starts, payload bytes bypass it.
 */
public final class WavStream {

    /** Called once the format is known (before the first onPcm); dataSizeBytes -1 = streaming/unknown. */
    public interface Listener {
        void onFormat(int sampleRate, int channels, long dataSizeBytes) throws IOException;

        void onPcm(byte[] buf, int off, int len) throws IOException;
    }

    private final Listener listener;
    private final byte[] head = new byte[256];
    private int headLen;
    private int pos; // parse position within the buffered header region
    private int rate = -1, channels;
    private long dataSize = -1, dataSeen;
    private boolean writing;

    public WavStream(Listener listener) { this.listener = listener; }

    public void feed(byte[] b, int off, int len) throws IOException {
        if (writing) { emitPcm(b, off, len); return; }
        while (len > 0) {
            int chunk = Math.min(len, head.length - headLen);
            if (chunk == 0) throw new IOException("WAV: слишком большой или битый заголовок");
            System.arraycopy(b, off, head, headLen, chunk);
            headLen += chunk; off += chunk; len -= chunk;
            parse();
            if (writing) { // payload mode: everything not yet copied from b flows straight through
                if (len > 0) emitPcm(b, off, len);
                return;
            }
        }
    }

    /** End of stream; a truncated tail (< a full chunk) is dropped. */
    public void end() { }

    private void parse() throws IOException {
        while (true) {
            if (pos == 0 && headLen < 12) return;
            if (pos == 0) {
                if (head[0] != 'R' || head[1] != 'I' || head[2] != 'F' || head[3] != 'F' || head[8] != 'W' || head[9] != 'A' || head[10] != 'V' || head[11] != 'E')
                    throw new IOException("WAV: это не RIFF/WAVE файл");
                pos = 12;
            }
            if (headLen - pos < 8) return;
            String id = new String(head, pos, 4, "ISO-8859-1");
            long size = (head[pos + 4] & 0xffL) | (head[pos + 5] & 0xffL) << 8 | (head[pos + 6] & 0xffL) << 16 | (head[pos + 7] & 0xffL) << 24;
            if (id.equals("fmt ")) {
                if (headLen - pos - 8 < 16) return;
                int format = (head[pos + 8] & 0xff) | (head[pos + 9] & 0xff) << 8;
                channels = (head[pos + 10] & 0xff) | (head[pos + 11] & 0xff) << 8;
                rate = (head[pos + 12] & 0xff) | (head[pos + 13] & 0xff) << 8 | (head[pos + 14] & 0xff) << 16 | (head[pos + 15] & 0xff) << 24;
                int bits = (head[pos + 22] & 0xff) | (head[pos + 23] & 0xff) << 8;
                if (format != 1 || bits != 16 || channels < 1 || channels > 2 || rate < 4000 || rate > 192000)
                    throw new IOException("WAV: поддерживается только PCM16 моно/стерео, а не format=" + format + " bits=" + bits + " rate=" + rate + " ch=" + channels);
                pos += (int) (8 + size + (size & 1)); // skip the rest of fmt (cbSize/extensible body)
                continue;
            }
            if (id.equals("data")) {
                dataSize = size == 0 || size == 0xFFFFFFFFL ? -1 : size;
                pos += 8;
                listener.onFormat(rate, channels, dataSize);
                writing = true;
                emitPcm(head, pos, headLen - pos);
                pos = headLen = 0;
                return;
            }
            // unknown chunk (LIST, fact, ...): skip whole (padded to even) or wait for its full body
            long total = 8 + size + (size & 1);
            if (headLen - pos < total) return;
            pos += (int) total;
        }
    }

    private void emitPcm(byte[] b, int off, int len) throws IOException {
        if (len <= 0) return;
        int n = (int) (dataSize < 0 ? len : Math.min(dataSize - dataSeen, len));
        if (n > 0) listener.onPcm(b, off, n);
        dataSeen += len;
    }
}
