package app.jarvis.dev.checks;

import app.jarvis.core.trace.TraceStore;
import app.jarvis.core.trace.TurnTrace;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.voice.AudioSink;
import app.jarvis.core.voice.AudioSpec;
import app.jarvis.core.voice.EdgeTts;
import app.jarvis.core.voice.ProviderConfig;
import app.jarvis.core.voice.SpeechText;
import app.jarvis.core.voice.TtsRequest;
import app.jarvis.core.voice.VoiceException;
import app.jarvis.media.StreamDecoder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Live latency check of the Edge TTS path on the real Android runtime (ART, Android's own TLS stack), no sound:
 * fresh connection per phrase vs the keep-alive pool, MP3 decode latency (real MediaCodec) after the first frames,
 * and what Edge answers for the text SpeechText.clean now removes ("...", "…").
 * Run: bash tools/art-apk.sh app.jarvis.dev.checks.SpeedCheck [N]
 */
public final class SpeedCheck {
    private static final String[] PHRASES = {"Слушаю.", "Хорошо, включаю.", "Сейчас посмотрю.", "Готово.", "Сегодня вторник.",
        "Не нашёл такой файл.", "Открываю настройки.", "Громкость сорок процентов.", "Поставил таймер.", "Доброе утро!"};

    /** Counts bytes, stamps first write; optionally decodes MP3 with the real codec and stamps the first PCM. */
    static final class TimedSink implements AudioSink, StreamDecoder.PcmOut {
        final long t0 = System.nanoTime();
        long firstWriteNs = -1, firstPcmNs = -1, endNs = -1;
        long bytes;
        StreamDecoder dec;
        final boolean decode;

        TimedSink(boolean decode) { this.decode = decode; }

        @Override public void begin(AudioSpec s) throws IOException {
            if (decode) { dec = new StreamDecoder(this); dec.begin(s); }
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (firstWriteNs < 0) firstWriteNs = System.nanoTime();
            bytes += len;
            if (dec != null) dec.feed(b, off, len);
        }

        @Override public void end() throws IOException {
            endNs = System.nanoTime();
            if (dec != null) dec.end();
        }

        @Override public void abort() { if (dec != null) dec.abort(); }

        @Override public void format(int sampleRate, int channels) { }

        @Override public void pcm(byte[] b, int off, int len) { if (firstPcmNs < 0) firstPcmNs = System.nanoTime(); }
    }

    private static EdgeTts provider(boolean keepAlive) {
        EdgeTts p = new EdgeTts();
        ProviderConfig cfg = new ProviderConfig();
        cfg.put("keepAlive", keepAlive ? "true" : "false");
        p.configure(cfg);
        return p;
    }

    private static double ms(long a, long b) { return (b - a) / 1e6; }

    private static String med(List<Double> xs) {
        if (xs.isEmpty()) return "n=0";
        List<Double> s = new ArrayList<>(xs);
        Collections.sort(s);
        return String.format(Locale.US, "n=%d med=%.0f min=%.0f max=%.0f", s.size(), s.get(s.size() / 2), s.get(0), s.get(s.size() - 1));
    }

    private static void series(String label, boolean keepAlive, int n, boolean prewarm) throws Exception {
        EdgeTts p = provider(keepAlive);
        if (prewarm) { p.prewarm(); Thread.sleep(6000); }
        List<Double> first = new ArrayList<>(), total = new ArrayList<>(), decodeLag = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            TtsRequest r = new TtsRequest();
            r.text = PHRASES[i % PHRASES.length];
            TimedSink sink = new TimedSink(true);
            long t0 = System.nanoTime();
            try {
                p.synthesize(r, sink, new CancelToken());
            } catch (VoiceException e) {
                System.out.println(label + " #" + i + " ERROR " + e.kind + " " + e.technical);
                continue;
            }
            double f = ms(t0, sink.firstWriteNs), t = ms(t0, sink.endNs);
            first.add(f); total.add(t);
            if (sink.firstPcmNs > 0) decodeLag.add(ms(sink.firstWriteNs, sink.firstPcmNs));
            System.out.println(String.format(Locale.US, "%s #%d first_audio_ms=%.0f total_ms=%.0f decode_lag_ms=%.0f bytes=%d connects=%d reuses=%d",
                label, i, f, t, sink.firstPcmNs > 0 ? ms(sink.firstWriteNs, sink.firstPcmNs) : -1, sink.bytes, p.connectCount(), p.reuseCount()));
        }
        System.out.println("== " + label + " first_audio: " + med(first) + " | total: " + med(total) + " | decode lag (first frames -> first PCM): " + med(decodeLag));
        p.closeIdle();
    }

    public static void main(String[] a) {
        try {
            int n = a.length > 0 ? Integer.parseInt(a[0]) : 6;
            System.out.println("SpeedCheck on " + android.os.Build.MODEL + " SDK " + android.os.Build.VERSION.SDK_INT);
            // what the app now does with the dotted text
            for (String s : new String[] {"...", "…", ". . .", "Подожди...", "Ну… не знаю"})
                System.out.println("clean [" + s + "] -> [" + SpeechText.forSpeech(s) + "]");
            EdgeTts p = provider(true);
            for (String s : new String[] {"...", "…"}) {
                TtsRequest r = new TtsRequest();
                r.text = s;
                long t0 = System.nanoTime();
                try { p.synthesize(r, new TimedSink(false), new CancelToken()); System.out.println("edge [" + s + "] audio ok"); }
                catch (VoiceException e) { System.out.println(String.format(Locale.US, "edge [%s] -> %s after %.0f ms (%s)", s, e.kind, ms(t0, System.nanoTime()), e.technical)); }
            }
            p.closeIdle();
            series("fresh      ", false, n, false);
            series("pooled     ", true, n, false);
            series("prewarmed  ", true, n, true);
            // the trace API on the same path
            TraceStore st = new TraceStore(null, null, TurnTrace.SYSTEM);
            TurnTrace tr = st.publish(st.create("text"));
            tr.mark(TurnTrace.SUBMIT);
            TtsRequest r = new TtsRequest();
            r.text = "Проверка трассировки.";
            tr.mark(TurnTrace.TTS_REQUEST); tr.mark(TurnTrace.TTS_SYNTH_START);
            TimedSink sink = new TimedSink(true);
            EdgeTts q = provider(true);
            q.synthesize(r, sink, new CancelToken());
            tr.mark(TurnTrace.TTS_FIRST_AUDIO);
            st.agentDone(tr);
            System.out.println("trace json: " + st.recent(1).get(0));
        } catch (Throwable t) {
            System.out.println("SPEEDCHECK CRASH: " + t);
            t.printStackTrace(System.out);
        }
        System.out.flush();
        System.exit(0);
    }
}
