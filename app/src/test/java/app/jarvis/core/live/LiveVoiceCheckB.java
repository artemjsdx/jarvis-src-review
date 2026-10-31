package app.jarvis.core.live;

import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.Redactor;
import app.jarvis.core.voice.AudioSink;
import app.jarvis.core.voice.AudioSpec;
import app.jarvis.core.voice.ElevenLabsApi;
import app.jarvis.core.voice.ElevenLabsStt;
import app.jarvis.core.voice.ElevenLabsTts;
import app.jarvis.core.voice.GoogleSpeechStt;
import app.jarvis.core.voice.GroqStt;
import app.jarvis.core.voice.OpenRouterStt;
import app.jarvis.core.voice.OpenRouterTts;
import app.jarvis.core.voice.ProviderConfig;
import app.jarvis.core.voice.PcmAudio;
import app.jarvis.core.voice.SttResult;
import app.jarvis.core.voice.TtsRequest;
import app.jarvis.core.voice.VoiceException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Live check for V2 voice providers (ElevenLabs, OpenRouter, Groq, Google Speech v2) against the REAL
 * services. Keys come from env (JV_ELEVEN_KEY, JV_OPENROUTER_KEY, JV_GROQ_KEY, JV_GOOGLE_KEY — the last
 * is optional, the Chromium key is built in; JV_PROXY optional). Never prints a key.
 * <p>
 * Usage: main [which] [extra]
 *   which = all | eleven,openrouter,groq,google (default all)
 *   extra = "clone" — also one OpenRouter TTS call with a voice reference (free, ~13 s);
 *           "sts" — one ElevenLabs speech-to-speech call (no TTS characters used);
 *           "notts" — skip every TTS/STS synthesis (STT and catalogs only, saves ElevenLabs characters).
 * Writes audio to $TMPDIR/live_<provider>.<ext> (default /data/data/com.termux/files/usr/tmp).
 * ElevenLabs budget: exactly ONE short TTS phrase (&lt; 40 chars) per run — free tier is 10 000 chars/month.
 */
public final class LiveVoiceCheckB {
    private static final String TTS_PHRASE = "Привет! Я Джарвис.";
    private static final String EXPECTED = "Привет! Я Джарвис, ваш голосовой помощник. Доброе утро.";

    private static final List<String> REPORT = new ArrayList<>();

    public static void main(String[] a) {
        try { run(a); } catch (Throwable t) {
            System.out.println("LIVECHECK CRASH: " + t);
            t.printStackTrace(System.out);
            System.out.flush();
            System.exit(1);
        }
    }

    private static void run(String[] a) throws Exception {
        String which = a.length > 0 ? a[0] : "all";
        String extra = a.length > 1 ? a[1] : "";
        boolean clone = extra.contains("clone");
        boolean sts = extra.contains("sts");
        boolean notts = extra.contains("notts");
        String tmp = System.getenv("TMPDIR");
        if (tmp == null || tmp.isEmpty()) tmp = "/data/data/com.termux/files/usr/tmp";

        String elKey = env("JV_ELEVEN_KEY"), orKey = env("JV_OPENROUTER_KEY"), groqKey = env("JV_GROQ_KEY");
        String googleKey = env("JV_GOOGLE_KEY");
        String proxy = System.getenv("JV_PROXY");
        if (elKey != null) Redactor.addSecret(elKey);
        if (orKey != null) Redactor.addSecret(orKey);
        if (groqKey != null) Redactor.addSecret(groqKey);
        if (googleKey != null) Redactor.addSecret(googleKey);

        File pcmFile = new File(tmp, "svet16k.pcm");
        if (!pcmFile.isFile()) { System.out.println("missing sample " + pcmFile); System.exit(2); }
        byte[] samplePcm = Files.readAllBytes(pcmFile.toPath());
        PcmAudio sample = new PcmAudio(samplePcm, 16000);

        boolean all = which.equals("all");
        if (all || which.contains("eleven")) eleven(elKey, proxy, sample, tmp, sts && !notts, notts);
        if (all || which.contains("openrouter")) openrouter(orKey, proxy, sample, tmp, clone && !notts, notts);
        if (all || which.contains("groq")) groq(groqKey, proxy, sample);
        if (all || which.contains("google")) google(googleKey, proxy, sample);

        System.out.println("\n==== REPORT (bench timings; ~7 KB/s uplink, RTT 0.5-2 s) ====");
        for (String line : REPORT) System.out.println(line);
        System.out.flush();
    }

    // ---------------------------------------------------------------- ElevenLabs

    private static void eleven(String key, String proxy, PcmAudio sample, String tmp, boolean sts, boolean notts) {
        System.out.println("\n== elevenlabs");
        if (key == null || key.isEmpty()) { System.out.println("   SKIP: no JV_ELEVEN_KEY"); REPORT.add("elevenlabs       SKIP (no key)"); return; }
        ElevenLabsTts tts = new ElevenLabsTts();
        tts.configure(cfg(key, proxy));
        ElevenLabsStt stt = new ElevenLabsStt();
        stt.configure(cfg(key, proxy));
        CancelToken cancel = new CancelToken();
        try {
            System.out.println("   quota: " + tts.quotaText(cancel));
            System.out.println("   voices: " + tts.listVoices(cancel).size());
            System.out.println("   models: " + tts.api().listModels(cancel).size() + " (supportsTags(" + tts.api().model() + ")=" + ElevenLabsApi.supportsTags(tts.api().model()) + ")");
        } catch (VoiceException e) {
            System.out.println("   catalog FAILED: " + e.kind + " — " + Redactor.redact(e.userMessage + " | " + e.technical));
        }
        FilePcmSink sink = new FilePcmSink(new File(tmp, "live_elevenlabs.pcm"));
        TtsRequest req = new TtsRequest();
        req.text = TTS_PHRASE;
        if (notts) {
            System.out.println("   TTS skipped (notts)");
        } else try {
            long t0 = System.currentTimeMillis();
            tts.synthesize(req, sink, cancel);
            long total = System.currentTimeMillis() - t0;
            System.out.println("   TTS ok: " + sink.spec + ", " + sink.bytes + " B, firstAudio=" + sink.firstMs + " ms, total=" + total + " ms -> live_elevenlabs.pcm");
            REPORT.add(String.format("elevenlabs TTS   ok  %-12s %6d B  first=%5d ms total=%5d ms", sink.spec, sink.bytes, sink.firstMs, total));
        } catch (VoiceException e) {
            System.out.println("   TTS FAILED: " + e.kind + " — " + Redactor.redact(e.userMessage) + " | " + Redactor.redact(e.technical));
            REPORT.add("elevenlabs TTS   FAIL " + e.kind);
        }
        try {
            SttResult r = stt.transcribe(sample, new app.jarvis.core.voice.SttOptions(), cancel);
            System.out.println("   STT ok (" + r.latencyMs + " ms): " + r.text);
            REPORT.add(String.format("elevenlabs STT   ok  latency=%6d ms  text=%s", r.latencyMs, r.text));
        } catch (VoiceException e) {
            System.out.println("   STT FAILED: " + e.kind + " — " + Redactor.redact(e.userMessage + " | " + e.technical));
            REPORT.add("elevenlabs STT   FAIL " + e.kind);
        }
        if (sts) {
            try {
                File ref = new File(tmp, "ref_small.mp3");
                byte[] mp3 = tts.api().speechToSpeech(tts.api().voice(), Files.readAllBytes(ref.toPath()), "audio/mpeg", "ref.mp3",
                    ElevenLabsApi.DEFAULT_STS_MODEL, cancel);
                File out = new File(tmp, "live_elevenlabs_sts.mp3");
                Files.write(out.toPath(), mp3);
                System.out.println("   STS ok: " + mp3.length + " B -> live_elevenlabs_sts.mp3");
                REPORT.add("elevenlabs STS   ok  " + mp3.length + " B");
            } catch (Exception e) {
                VoiceException ve = e instanceof VoiceException ? (VoiceException) e
                    : new VoiceException(VoiceException.Kind.NETWORK, "sts", String.valueOf(e), e);
                System.out.println("   STS FAILED: " + ve.kind + " — " + Redactor.redact(ve.userMessage + " | " + ve.technical));
                REPORT.add("elevenlabs STS   FAIL " + ve.kind);
            }
        }
    }

    // ---------------------------------------------------------------- OpenRouter

    private static void openrouter(String key, String proxy, PcmAudio sample, String tmp, boolean clone, boolean notts) {
        System.out.println("\n== openrouter");
        if (key == null || key.isEmpty()) { System.out.println("   SKIP: no JV_OPENROUTER_KEY"); REPORT.add("openrouter       SKIP (no key)"); return; }
        OpenRouterTts tts = new OpenRouterTts();
        tts.configure(cfg(key, proxy));
        OpenRouterStt stt = new OpenRouterStt();
        stt.configure(cfg(key, proxy));
        CancelToken cancel = new CancelToken();
        try {
            List<String> models = tts.listSpeechModels(cancel);
            long free = models.stream().filter(m -> m.contains(":free")).count();
            System.out.println("   speech models: " + models.size() + " (free: " + free + ")");
        } catch (VoiceException e) {
            System.out.println("   models FAILED: " + e.kind + " — " + Redactor.redact(e.userMessage + " | " + e.technical));
        }
        FilePcmSink sink = new FilePcmSink(new File(tmp, "live_openrouter.mp3"));
        TtsRequest req = new TtsRequest();
        req.text = TTS_PHRASE;
        if (notts) {
            System.out.println("   TTS skipped (notts)");
        } else try {
            long t0 = System.currentTimeMillis();
            tts.synthesize(req, sink, cancel);
            long total = System.currentTimeMillis() - t0;
            System.out.println("   TTS ok: " + sink.spec + ", " + sink.bytes + " B, firstAudio=" + sink.firstMs + " ms, total=" + total + " ms -> live_openrouter.mp3");
            REPORT.add(String.format("openrouter TTS   ok  %-12s %6d B  first=%5d ms total=%5d ms", sink.spec, sink.bytes, sink.firstMs, total));
        } catch (VoiceException e) {
            System.out.println("   TTS FAILED: " + e.kind + " — " + Redactor.redact(e.userMessage + " | " + e.technical));
            REPORT.add("openrouter TTS   FAIL " + e.kind);
        }
        if (clone) {
            try {
                File ref = new File(tmp, "edge_ru-RU-SvetlanaNeural.mp3");
                TtsRequest creq = new TtsRequest();
                creq.text = "Это проверка клонирования голоса по короткому образцу.";
                creq.refAudioPath = ref.getAbsolutePath();
                creq.refText = EXPECTED;
                FilePcmSink csink = new FilePcmSink(new File(tmp, "live_openrouter_clone.mp3"));
                long t0 = System.currentTimeMillis();
                tts.synthesize(creq, csink, cancel);
                long total = System.currentTimeMillis() - t0;
                System.out.println("   CLONE ok: " + csink.bytes + " B, total=" + total + " ms -> live_openrouter_clone.mp3");
                REPORT.add(String.format("openrouter CLONE ok  %-12s %6d B  total=%5d ms", csink.spec, csink.bytes, total));
            } catch (VoiceException e) {
                System.out.println("   CLONE FAILED: " + e.kind + " — " + Redactor.redact(e.userMessage + " | " + e.technical));
                REPORT.add("openrouter CLONE FAIL " + e.kind);
            }
        }
        try {
            SttResult r = stt.transcribe(sample, new app.jarvis.core.voice.SttOptions(), cancel);
            System.out.println("   STT ok (" + r.latencyMs + " ms): " + r.text);
            REPORT.add(String.format("openrouter STT   ok  latency=%6d ms  text=%s", r.latencyMs, r.text));
        } catch (VoiceException e) {
            System.out.println("   STT FAILED: " + e.kind + " — " + Redactor.redact(e.userMessage + " | " + e.technical));
            REPORT.add("openrouter STT   FAIL " + e.kind);
        }
    }

    // ---------------------------------------------------------------- Groq / Google

    private static void groq(String key, String proxy, PcmAudio sample) {
        System.out.println("\n== groq-stt");
        if (key == null || key.isEmpty()) { System.out.println("   SKIP: no JV_GROQ_KEY"); REPORT.add("groq-stt         SKIP (no key)"); return; }
        GroqStt stt = new GroqStt();
        stt.configure(cfg(key, proxy));
        try {
            SttResult r = stt.transcribe(sample, new app.jarvis.core.voice.SttOptions(), new CancelToken());
            System.out.println("   STT ok (" + r.latencyMs + " ms): " + r.text);
            REPORT.add(String.format("groq-stt         ok  latency=%6d ms  text=%s", r.latencyMs, r.text));
        } catch (VoiceException e) {
            System.out.println("   STT FAILED: " + e.kind + " — " + Redactor.redact(e.userMessage + " | " + e.technical));
            REPORT.add("groq-stt         FAIL " + e.kind);
        }
    }

    private static void google(String key, String proxy, PcmAudio sample) {
        System.out.println("\n== google-stt");
        ProviderConfig c = cfg(key == null ? GoogleSpeechStt.CHROMIUM_KEY : key, proxy);
        GoogleSpeechStt stt = new GoogleSpeechStt();
        stt.configure(c);
        try {
            SttResult r = stt.transcribe(sample, new app.jarvis.core.voice.SttOptions(), new CancelToken());
            System.out.println("   STT ok (" + r.latencyMs + " ms): " + r.text + (r.alternatives.isEmpty() ? "" : " | alts: " + r.alternatives));
            REPORT.add(String.format("google-stt       ok  latency=%6d ms  text=%s", r.latencyMs, r.text));
        } catch (VoiceException e) {
            System.out.println("   STT FAILED: " + e.kind + " — " + Redactor.redact(e.userMessage + " | " + e.technical));
            REPORT.add("google-stt       FAIL " + e.kind);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static ProviderConfig cfg(String key, String proxy) {
        ProviderConfig c = new ProviderConfig();
        c.put("apiKey", key);
        if (proxy != null && !proxy.isEmpty()) c.put("proxy", proxy);
        c.journal = app.jarvis.core.log.Journal.NOP;
        return c;
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    /** File sink that records first-audio time and codec. */
    static final class FilePcmSink implements AudioSink {
        final File file;
        OutputStream out;
        AudioSpec spec;
        long bytes, firstMs;
        private long t0;

        FilePcmSink(File f) { file = f; }
        @Override public void begin(AudioSpec s) throws IOException {
            spec = s;
            t0 = System.currentTimeMillis();
            out = new FileOutputStream(file);
        }
        @Override public void write(byte[] data, int off, int len) throws IOException {
            if (firstMs == 0) firstMs = System.currentTimeMillis() - t0;
            out.write(data, off, len);
            bytes += len;
        }
        @Override public void end() throws IOException { out.close(); }
        @Override public void abort() { try { if (out != null) out.close(); } catch (IOException ignored) { } }
    }
}
