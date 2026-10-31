package app.jarvis.core.live;

import app.jarvis.core.util.CancelToken;
import app.jarvis.core.voice.AudioSink;
import app.jarvis.core.voice.AudioSpec;
import app.jarvis.core.voice.EdgeTts;
import app.jarvis.core.voice.F5SpaceTts;
import app.jarvis.core.voice.GoogleTranslateTts;
import app.jarvis.core.voice.ProviderConfig;
import app.jarvis.core.voice.SileroSpaceTts;
import app.jarvis.core.voice.TtsRequest;
import app.jarvis.core.voice.VoiceException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Live check of TTS providers A against the real services (no keys needed):
 *   edge, gtts, silero, f5-ru — pass any subset as args, default all but f5-ru.
 * Writes $TMPDIR/live_<provider>.<ext> and prints codec/bytes/first-audio/total.
 * F5 cloning uses the gtts output as the reference sample (transcript known).
 */
public final class LiveVoiceCheckA {
    private static final String PHRASE = "Привет! Я Джарвис, ваш голосовой помощник.";
    private static volatile String tmpDir = System.getProperty("java.io.tmpdir", ".");

    static final class FileSink implements AudioSink {
        final File file;
        OutputStream out;
        long bytes;
        AudioSpec spec;
        boolean ended, aborted;

        FileSink(File f) { file = f; }

        @Override public void begin(AudioSpec s) throws IOException {
            spec = s;
            out = new FileOutputStream(file);
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            bytes += len;
        }

        @Override public void end() throws IOException { out.close(); ended = true; }

        @Override public void abort() {
            aborted = true;
            try { if (out != null) out.close(); } catch (IOException ignored) { }
        }
    }

    public static void main(String[] a) {
        try { run(a); } catch (Throwable t) {
            System.out.println("LIVEVOICE-A CRASH: " + t);
            t.printStackTrace(System.out);
            System.out.flush();
            System.exit(1);
        }
    }

    static void run(String[] a) throws Exception {
        String env = System.getenv("TMPDIR");
        if (env != null && !env.isEmpty()) tmpDir = env;
        String only = a.length > 0 ? a[0] : "all";
        boolean f5 = only.equals("f5");
        boolean all = only.equals("all");

        System.out.println("== LiveVoiceCheckA  TMPDIR=" + tmpDir + "  (no keys needed for these providers)");
        if (all || only.equals("edge")) {
            EdgeTts p = new EdgeTts();
            p.configure(baseConfig());
            voices(p, "edge");
            synth(p, "edge", "mp3", null);
        }
        if (all || only.equals("gtts")) {
            GoogleTranslateTts p = new GoogleTranslateTts();
            p.configure(baseConfig());
            synth(p, "gtts", "mp3", null);
        }
        if (all || only.equals("silero")) {
            SileroSpaceTts p = new SileroSpaceTts();
            p.configure(baseConfig());
            synth(p, "silero", "wav", null);
        }
        if (f5 || (all && System.getenv("JV_F5") != null)) {
            f5Check();
        }
        System.out.println("== done");
    }

    private static ProviderConfig baseConfig() {
        ProviderConfig cfg = new ProviderConfig();
        cfg.tmpDir = new File(tmpDir);
        return cfg;
    }

    private static void voices(app.jarvis.core.voice.TtsProvider p, String id) {
        try {
            long t0 = System.currentTimeMillis();
            System.out.printf(Locale.US, "[%s] voices: %d (%d ms)%n", id,
                p.listVoices(new CancelToken()).size(), System.currentTimeMillis() - t0);
        } catch (VoiceException e) {
            System.out.printf(Locale.US, "[%s] voices FAILED: %s | %s%n", id, e.userMessage, e.technical);
        }
    }

    private static void synth(app.jarvis.core.voice.TtsProvider p, String id, String ext, TtsRequest custom) {
        File out = new File(tmpDir, "live_" + id + "." + ext);
        out.delete();
        FileSink sink = new FileSink(out);
        TtsRequest req = custom != null ? custom : req(PHRASE, 1f);
        long t0 = System.currentTimeMillis();
        try {
            p.synthesize(req, sink, new CancelToken());
            System.out.printf(Locale.US, "[%s] OK codec=%s bytes=%d firstAudio=%d ms total=%d ms file=%s%n",
                id, sink.spec, sink.bytes, p.lastStatus().firstAudioMs,
                System.currentTimeMillis() - t0, out.getAbsolutePath());
        } catch (VoiceException e) {
            System.out.printf(Locale.US, "[%s] FAILED kind=%s user=%s tech=%s bytes=%d firstAudio=%d total=%d ms%n",
                id, e.kind, e.userMessage, e.technical, sink.bytes,
                p.lastStatus().firstAudioMs, System.currentTimeMillis() - t0);
        }
    }

    private static TtsRequest req(String text, float speed) {
        TtsRequest r = new TtsRequest();
        r.text = text;
        r.speed = speed;
        return r;
    }

    /** F5 cloning: reference = the gtts output (transcript = PHRASE). */
    private static void f5Check() {
        File ref = new File(tmpDir, "live_gtts.mp3");
        if (!ref.isFile() || ref.length() == 0) {
            System.out.println("[f5-ru] SKIPPED: need live_gtts.mp3 as the reference (run 'gtts' first)");
            return;
        }
        F5SpaceTts p = new F5SpaceTts();
        p.configure(baseConfig());
        TtsRequest r = req("Здравствуйте! Клонирование голоса работает.", 1f);
        r.refAudioPath = ref.getAbsolutePath();
        r.refText = PHRASE;
        synth(p, "f5-ru", "wav", r);
    }
}
