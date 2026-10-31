package app.jarvis.dev.checks.media;

import app.jarvis.core.voice.AudioSpec;
import app.jarvis.media.StreamDecoder;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * ART check (no audio device needed): every $TMPDIR speech sample is fed in odd-sized 777-byte chunks through the
 * SAME decode path SpeechPlayer uses (StreamDecoder into a counting PCM sink) and compared against ffprobe:
 * decoded duration within 3%, time to first PCM chunk, RMS (silence detection), no exceptions. AAC_ADTS files are
 * a bonus beyond the required MP3/WAV/PCM set.
 */
public final class DecodeCheck {
    static int fails;

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { System.out.println("CRASH " + t); t.printStackTrace(System.out); fails++; }
        System.out.println(fails == 0 ? "DECODECHECK OK" : "DECODECHECK FAILURES: " + fails);
        System.out.flush();
        System.exit(fails == 0 ? 0 : 1);
    }

    static final class CountingSink implements StreamDecoder.PcmOut {
        long bytes, sumSq, samples, peak;
        int rate, ch;
        long firstPcmNanos;

        void reset() { bytes = sumSq = samples = peak = 0; rate = ch = 0; firstPcmNanos = 0; }

        @Override public void format(int sampleRate, int channels) { rate = sampleRate; ch = channels; }

        @Override public void pcm(byte[] b, int off, int len) {
            if (firstPcmNanos == 0) firstPcmNanos = System.nanoTime();
            bytes += len;
            for (int i = off; i + 1 < off + len; i += 2) {
                int v = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
                sumSq += (long) v * v;
                int a = v < 0 ? -v : v;
                if (a > peak) peak = a;
            }
            samples += len / 2;
        }
    }

    static void run() throws Exception {
        String tmp = System.getenv("TMPDIR");
        if (tmp == null || tmp.isEmpty()) tmp = "/data/data/com.termux/files/usr/tmp"; // app_process may lack TMPDIR
        CountingSink sink = new CountingSink();
        check(tmp, "edge_ru-RU-SvetlanaNeural.mp3", AudioSpec.mp3(), sink, true);
        check(tmp, "el_eleven_flash_v2_5_EXAV_mp3_22050_32.mp3", AudioSpec.mp3(), sink, true);
        check(tmp, "el_eleven_flash_v2_5_EXAV_mp3_44100_64.mp3", AudioSpec.mp3(), sink, true);
        check(tmp, "el_eleven_multilingual_v2_EXAV_mp3_44100_64.mp3", AudioSpec.mp3(), sink, true);
        check(tmp, "el_eleven_v3_EXAV_mp3_44100_64.mp3", AudioSpec.mp3(), sink, true);
        check(tmp, "el_eleven_v4_EXAV_mp3_44100_64.mp3", AudioSpec.mp3(), sink, true);
        check(tmp, "el_eleven_v4_turbo_EXAV_mp3_44100_64.mp3", AudioSpec.mp3(), sink, true);
        check(tmp, "silero_aidar.wav", AudioSpec.wav(), sink, true);
        check(tmp, "silero_kseniya.wav", AudioSpec.wav(), sink, true);
        check(tmp, "el_eleven_flash_v2_5_EXAV_pcm_24000.pcm", new AudioSpec(AudioSpec.Codec.PCM_S16LE, 24_000, 1), sink, true);
        check(tmp, "full.aac", new AudioSpec(AudioSpec.Codec.AAC_ADTS, 0, 0), sink, false);
        check(tmp, "clip3s.aac", new AudioSpec(AudioSpec.Codec.AAC_ADTS, 0, 0), sink, false);
    }

    static void check(String dir, String name, AudioSpec spec, CountingSink sink, boolean require3pct) throws Exception {
        File f = new File(dir, name);
        if (!f.isFile() || f.length() == 0) { System.out.println("  SKIP " + name + " (файла нет или пуст)"); return; }
        sink.reset();
        long t0 = System.nanoTime();
        StreamDecoder d = new StreamDecoder(sink);
        d.begin(spec);
        InputStream in = new FileInputStream(f);
        byte[] buf = new byte[777]; // odd size: chunk boundaries fall everywhere, incl. inside headers
        int n;
        while ((n = in.read(buf)) > 0) d.feed(buf, 0, n);
        in.close();
        d.end();
        long firstMs = sink.firstPcmNanos == 0 ? -1 : (sink.firstPcmNanos - t0) / 1_000_000;
        double decoded = sink.rate > 0 ? sink.bytes / 2.0 / sink.rate : 0;
        double ref = spec.codec == AudioSpec.Codec.PCM_S16LE
                ? f.length() / 2.0 / spec.sampleRate
                : ffprobeSeconds(f);
        double devPct = ref > 0 ? Math.abs(decoded - ref) / ref * 100 : 100;
        double rms = sink.samples == 0 ? 0 : Math.sqrt(sink.sumSq / (double) sink.samples) / 32768.0;
        boolean ok = sink.samples > 0 && sink.rate > 0 && rms > 1e-4 && firstMs >= 0 && firstMs < 2000
                && (!require3pct || devPct <= 3.0);
        if (!ok) fails++;
        System.out.println(String.format(Locale.US, "  %s %-44s %6.3fs vs ffprobe %6.3fs (%5.2f%%)  first=%4dms  rms=%.4f  %dHz/%dch  frames777",
                ok ? "ok  " : "FAIL", name, decoded, ref, devPct, firstMs, rms, sink.rate, sink.ch));
        System.out.flush();
    }

    static String probeBin;

    static String probeBin() {
        if (probeBin != null) return probeBin;
        for (String c : new String[]{"ffprobe", "/data/data/com.termux/files/usr/bin/ffprobe"}) {
            try {
                Process p = new ProcessBuilder(c, "-version").redirectErrorStream(true).start();
                p.getInputStream().read();
                p.waitFor();
                probeBin = c;
                return c;
            } catch (Throwable ignored) { }
        }
        probeBin = "ffprobe";
        return probeBin;
    }

    static double ffprobeSeconds(File f) throws Exception {
        Process p = new ProcessBuilder(probeBin(), "-v", "error", "-show_entries", "format=duration",
                "-of", "default=nw=1:nk=1", f.getAbsolutePath()).redirectErrorStream(true).start();
        byte[] out = new byte[256];
        int len = p.getInputStream().read(out);
        p.waitFor();
        String s = len > 0 ? new String(out, 0, len).trim() : "";
        int sp = s.indexOf('\n');
        if (sp > 0) s = s.substring(0, sp);
        return Double.parseDouble(s.split("\\s+")[0]);
    }
}
