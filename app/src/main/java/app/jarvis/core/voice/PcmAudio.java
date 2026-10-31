package app.jarvis.core.voice;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Mono 16-bit little-endian PCM captured from the microphone. */
public final class PcmAudio {
    public final byte[] pcm;
    public final int sampleRate;

    public PcmAudio(byte[] pcm, int sampleRate) { this.pcm = pcm; this.sampleRate = sampleRate; }

    public long durationMs() { return pcm.length / 2L * 1000L / Math.max(1, sampleRate); }

    /** RIFF/WAVE container (44-byte header + data). */
    public byte[] toWav() {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16).putShort((short) 1)
            .putShort((short) 1).putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16)
            .put("data".getBytes()).putInt(pcm.length).put(pcm);
        return b.array();
    }

    /** Parses a 16-bit PCM WAV (mono or stereo, any rate; stereo is down-mixed). Returns null if not PCM16 WAV. */
    public static PcmAudio fromWav(byte[] w) {
        if (w.length < 44 || w[0] != 'R' || w[1] != 'I' || w[2] != 'F' || w[3] != 'F') return null;
        ByteBuffer b = ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 12, rate = 0, ch = 1, bits = 16;
        while (pos + 8 <= w.length) {
            String id = new String(w, pos, 4);
            int size = b.getInt(pos + 4);
            if (id.equals("fmt ")) { ch = b.getShort(pos + 10); rate = b.getInt(pos + 12); bits = b.getShort(pos + 22); }
            else if (id.equals("data")) {
                if (bits != 16) return null;
                int n = Math.min(size < 0 ? Integer.MAX_VALUE : size, w.length - pos - 8);
                byte[] pcm = new byte[n];
                System.arraycopy(w, pos + 8, pcm, 0, n);
                if (ch == 2) {
                    ByteArrayOutputStream o = new ByteArrayOutputStream(n / 2);
                    for (int i = 0; i + 3 < n; i += 4) { int m = (pcm[i] & 0xff | pcm[i + 1] << 8) / 2 + (pcm[i + 2] & 0xff | pcm[i + 3] << 8) / 2; o.write(m); o.write(m >> 8); }
                    pcm = o.toByteArray();
                }
                return new PcmAudio(pcm, rate);
            }
            pos += 8 + size + (size & 1);
        }
        return null;
    }
}
