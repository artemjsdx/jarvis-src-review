package app.jarvis.core.audio;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

/** Shared helpers for the core.audio tests: resource lookup and synthetic PCM generation. */
final class TestAudio {
    private TestAudio() { }

    /** Finds app/src/test/resources no matter which directory the JUnit runner was started from. */
    static byte[] resource(String name) throws IOException {
        File dir = new File(".").getAbsoluteFile();
        while (dir != null && !new File(dir, "app/src/test/resources/" + name).isFile()) dir = dir.getParentFile();
        if (dir == null) throw new IOException("missing test resource " + name + " (no app/src/test/resources upward from cwd)");
        File f = new File(dir, "app/src/test/resources/" + name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[16384];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        }
        return out.toByteArray();
    }

    /** 16 kHz mono speech ("Привет! Я Джарвис, ваш голосовой помощник. Доброе утро."), ~3.2 s. */
    static short[] speech16k() throws IOException {
        return shorts(resource("speech16k.pcm"));
    }

    /** ms-slice of the sample; speech16k(1600, 3200) is one continuous sentence ("Я Джарвис, …") with no pauses. */
    static short[] speech16k(int fromMs, int toMs) throws IOException {
        short[] all = speech16k();
        int from = RATE * fromMs / 1000, to = Math.min(all.length, RATE * toMs / 1000);
        short[] out = new short[to - from];
        System.arraycopy(all, from, out, 0, out.length);
        return out;
    }

    static short[] shorts(byte[] le) {
        short[] s = new short[le.length / 2];
        for (int i = 0; i < s.length; i++) s[i] = (short) ((le[i * 2] & 0xff) | le[i * 2 + 1] << 8);
        return s;
    }

    static byte[] le(short[] s) {
        byte[] b = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) { b[i * 2] = (byte) s[i]; b[i * 2 + 1] = (byte) (s[i] >> 8); }
        return b;
    }

    /** White noise with the given peak-ish amplitude expressed in dBFS (rms-normalized). */
    static short[] noise(int samples, double dbfs, long seed) {
        Random r = new Random(seed);
        double amp = 32767 * Math.pow(10, dbfs / 20.0);
        short[] out = new short[samples];
        // normalize the uniform generator to hit the requested RMS
        double[] raw = new double[samples];
        double sum = 0;
        for (int i = 0; i < samples; i++) { raw[i] = r.nextDouble() * 2 - 1; sum += raw[i] * raw[i]; }
        double k = amp / Math.max(1e-9, Math.sqrt(sum / samples));
        for (int i = 0; i < samples; i++) out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(raw[i] * k)));
        return out;
    }

    static short[] sine(int samples, double freq, double dbfs, int rate) {
        double amp = 32767 * Math.pow(10, dbfs / 20.0);
        short[] out = new short[samples];
        for (int i = 0; i < samples; i++) out[i] = (short) Math.round(amp * Math.sin(2 * Math.PI * freq * i / rate));
        return out;
    }

    static short[] concat(short[]... parts) {
        int n = 0;
        for (short[] p : parts) n += p.length;
        short[] out = new short[n];
        int o = 0;
        for (short[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
    }

    static final int RATE = 16000;

    static short[] silenceMs(int ms) { return new short[RATE * ms / 1000]; }

    /**
     * Continuous speech-like signal: 3 sines syllabically modulated at ~3 Hz over a −35 dBFS noise floor. RMS dips
     * never last long enough to end an utterance, so VAD sees one long speech run (real speech has internal pauses).
     */
    static short[] speechLike(int ms) {
        int n = RATE * ms / 1000;
        short[] out = new short[n];
        double floor = 32767 * Math.pow(10, -35 / 20.0);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double env = Math.sqrt(Math.max(0, Math.sin(2 * Math.PI * 3.1 * t)));
            double s = Math.sin(2 * Math.PI * 300 * t) + 0.7 * Math.sin(2 * Math.PI * 700 * t) + 0.5 * Math.sin(2 * Math.PI * 1200 * t);
            out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(floor + env * s / 2.2 * 32767 * Math.pow(10, -14 / 20.0))));
        }
        return out;
    }
}
