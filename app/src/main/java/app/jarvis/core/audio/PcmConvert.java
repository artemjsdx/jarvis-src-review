package app.jarvis.core.audio;

/** Sample-format helpers for audio that is not on the microphone path: channel downmix, rate conversion, byte packing. */
public final class PcmConvert {
    public static final int STT_RATE = 16000;

    private PcmConvert() { }

    /** Averages interleaved channels into one. */
    public static short[] toMono(short[] interleaved, int channels) {
        int ch = Math.max(1, channels);
        int frames = interleaved.length / ch;
        short[] out = new short[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < ch; c++) sum += interleaved[i * ch + c];
            out[i] = (short) (sum / ch);
        }
        return out;
    }

    /** Mono samples to 16 kHz: box average when shrinking (anti-alias), linear interpolation when growing. */
    public static short[] resample(short[] mono, int rate, int target) {
        if (rate <= 0 || target <= 0) throw new IllegalArgumentException("bad rate " + rate);
        if (rate == target) return mono.clone();
        int outLen = (int) ((long) mono.length * target / rate);
        short[] out = new short[outLen];
        double step = (double) rate / target;
        for (int i = 0; i < outLen; i++) {
            double pos = i * step;
            if (rate > target) {
                int from = (int) pos, to = Math.min(mono.length, Math.max(from + 1, (int) (pos + step)));
                long sum = 0;
                for (int k = from; k < to; k++) sum += mono[k];
                out[i] = (short) (sum / Math.max(1, to - from));
            } else {
                int i0 = (int) pos, i1 = Math.min(mono.length - 1, i0 + 1);
                double f = pos - i0;
                out[i] = (short) Math.round(mono[i0] * (1 - f) + mono[i1] * f);
            }
        }
        return out;
    }

    /** Any interleaved PCM16 at any rate to 16 kHz mono. */
    public static short[] to16kMono(short[] interleaved, int rate, int channels) {
        return resample(toMono(interleaved, channels), rate, STT_RATE);
    }

    /** Little-endian bytes of the samples. */
    public static byte[] toBytes(short[] s) {
        byte[] b = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) { b[2 * i] = (byte) s[i]; b[2 * i + 1] = (byte) (s[i] >> 8); }
        return b;
    }

    /** Little-endian bytes to samples; an odd trailing byte is dropped. */
    public static short[] fromBytes(byte[] b) {
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++) s[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
        return s;
    }
}
