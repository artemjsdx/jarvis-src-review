package app.jarvis.dev.checks;

import app.jarvis.core.util.CancelToken;
import app.jarvis.core.voice.AudioSink;
import app.jarvis.core.voice.AudioSpec;
import app.jarvis.core.voice.EdgeTts;
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
 * Live check of providers A (edge, gtts, silero) on the real Android runtime (ART) via app_process.
 * Run: bash tools/art-apk.sh app.jarvis.dev.checks.CheckVoiceA [edge|gtts|silero|all]
 * Writes $TMPDIR/live_art_<provider>.<ext>. No keys needed.
 */
public final class CheckVoiceA {
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
            System.out.println("CHECKVOICE-A CRASH: " + t);
            t.printStackTrace(System.out);
            System.out.flush();
            System.exit(1);
        }
    }

    static void run(String[] a) throws Exception {
        String env = System.getenv("TMPDIR");
        if (env != null && !env.isEmpty()) tmpDir = env;
        String only = a.length > 0 ? a[0] : "all";
        System.out.println("== CheckVoiceA on ART  TMPDIR=" + tmpDir
            + "  vendor=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
            + "  api=" + android.os.Build.VERSION.SDK_INT);
        if (only.equals("all") || only.equals("edge")) {
            EdgeTts p = new EdgeTts();
            p.configure(cfg());
            synth(p, "edge", "mp3");
        }
        if (only.equals("all") || only.equals("gtts")) {
            GoogleTranslateTts p = new GoogleTranslateTts();
            p.configure(cfg());
            synth(p, "gtts", "mp3");
        }
        if (only.equals("all") || only.equals("silero")) {
            SileroSpaceTts p = new SileroSpaceTts();
            p.configure(cfg());
            synth(p, "silero", "wav");
        }
        System.out.println("== done");
    }

    private static ProviderConfig cfg() {
        ProviderConfig c = new ProviderConfig();
        c.tmpDir = new File(tmpDir);
        return c;
    }

    private static void synth(app.jarvis.core.voice.TtsProvider p, String id, String ext) {
        File out = new File(tmpDir, "live_art_" + id + "." + ext);
        out.delete();
        FileSink sink = new FileSink(out);
        TtsRequest req = new TtsRequest();
        req.text = PHRASE;
        long t0 = System.currentTimeMillis();
        try {
            p.synthesize(req, sink, new CancelToken());
            System.out.printf(Locale.US, "[%s] OK codec=%s bytes=%d firstAudio=%d ms total=%d ms file=%s%n",
                id, sink.spec, sink.bytes, p.lastStatus().firstAudioMs,
                System.currentTimeMillis() - t0, out.getAbsolutePath());
        } catch (VoiceException e) {
            System.out.printf(Locale.US, "[%s] FAILED kind=%s user=%s tech=%s bytes=%d total=%d ms%n",
                id, e.kind, e.userMessage, e.technical, sink.bytes, System.currentTimeMillis() - t0);
        }
    }
}
