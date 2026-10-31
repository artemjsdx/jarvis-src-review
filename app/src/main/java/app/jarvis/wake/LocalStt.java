package app.jarvis.wake;

import app.jarvis.core.log.Journal;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.voice.PcmAudio;
import app.jarvis.core.voice.ProviderConfig;
import app.jarvis.core.voice.ProviderInfo;
import app.jarvis.core.voice.ProviderStatus;
import app.jarvis.core.voice.SttOptions;
import app.jarvis.core.voice.SttProvider;
import app.jarvis.core.voice.SttResult;
import app.jarvis.core.voice.VoiceException;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Recognizer;

/**
 * Offline fallback recogniser for whole commands on the same small model the wake gate uses — full vocabulary,
 * no grammar. Accuracy is visibly below the cloud providers (it is a 45 MB model), but with it phrases like
 * «останови музыку» still work when the network is gone, and audio never leaves the device.
 *
 * <p>{@link ProviderConfig} keys:
 * <ul>
 *   <li>{@code modelDir} — directory with the unpacked {@link WakePack#MODEL_NAME} model (required).</li>
 * </ul></p>
 */
public final class LocalStt implements SttProvider {
    public static final String ID = "vosk-local";
    public static final String SAMPLE_TEXT = "Привет! Я Джарвис, ваш голосовой помощник. Доброе утро.";
    private static final int RATE = LocalWake.RATE;
    /** Close the recognizer and the shared model after this much inactivity. */
    public static final long IDLE_MS = 60_000L;
    private static final int CHUNK_BYTES = 6400; // 200 ms of 16 kHz s16le mono

    private static final ScheduledExecutorService IDLER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jv-stt-local-idle"); t.setDaemon(true); return t;
    });

    private final Object lock = new Object();
    private File modelDir;
    private Journal journal = Journal.NOP;
    private ProviderStatus status = ProviderStatus.NEVER;
    private org.vosk.Model model;        // guarded by lock; shared instance from VoskModel
    private Recognizer recognizer;       // guarded by lock
    private long lastUse;                // guarded by lock
    private ScheduledFuture<?> idleTask; // guarded by lock

    @Override public ProviderInfo info() {
        return new ProviderInfo(ID, "Vosk локально (офлайн)",
            "vosk-model-small-ru-0.22, только русский",
            false, false, "без ограничений: работает на устройстве",
            "точность заметно ниже облачных сервисов (маленькая модель 45 МБ); пока загружена, держит ~100–250 МБ ОЗУ; аудио никуда не отправляется",
            false, "", true, "ничего (распознавание полностью локальное)", "https://alphacephei.com/vosk/");
    }

    @Override public void configure(ProviderConfig cfg) {
        String dir = cfg.get("modelDir");
        modelDir = dir.isEmpty() ? null : new File(dir);
        if (cfg.journal != null) journal = cfg.journal;
    }

    @Override public boolean isConfigured() { return modelDir != null && WakePack.looksInstalled(modelDir); }

    @Override public SttResult transcribe(PcmAudio audio, SttOptions opts, CancelToken cancel) throws VoiceException {
        if (audio.sampleRate != RATE)
            throw new VoiceException(VoiceException.Kind.UNSUPPORTED, "Офлайн-распознавание ожидает PCM 16 кГц.", "rate=" + audio.sampleRate, null);
        if (!isConfigured())
            throw new VoiceException(VoiceException.Kind.CONFIG, "Офлайн-модель не установлена. Установите её в настройках обращения.", "modelDir=" + modelDir, null);
        Recognizer r;
        synchronized (lock) {
            r = recognizer != null ? recognizer : ensureRecognizer();
        }
        long t0 = System.nanoTime();
        List<String> chunks = new ArrayList<>();
        try {
            byte[] pcm = audio.pcm;
            for (int off = 0; off < pcm.length; off += CHUNK_BYTES) {
                cancel.throwIfCancelled();
                int n = Math.min(CHUNK_BYTES, pcm.length - off);
                byte[] piece = n == pcm.length ? pcm : Arrays.copyOfRange(pcm, off, off + n);
                if (r.acceptWaveForm(piece, n)) chunks.add(r.getResult());
            }
            chunks.add(r.getFinalResult());
            r.reset();
        } catch (CancelToken.CancelledException e) {
            throw VoiceException.cancelled();
        } catch (RuntimeException e) {
            throw new VoiceException(VoiceException.Kind.PROTOCOL, "Ошибка локального распознавания.", "vosk: " + e, e);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        SttResult res = new SttResult();
        res.text = assembleText(chunks);
        res.confidence = averageConfidence(chunks);
        res.providerId = ID;
        res.latencyMs = ms;
        synchronized (lock) {
            lastUse = System.currentTimeMillis();
            scheduleIdleLocked();
        }
        status = new ProviderStatus(System.currentTimeMillis(), true, "", ms, ms,
            audio.durationMs() + " мс звука, офлайн (vosk-small)");
        journal.log(Journal.Cat.STT, Journal.Level.INFO, "Распознано локально: " + res.text.length() + " симв., " + audio.durationMs() + " мс звука",
            null, null, ms);
        return res;
    }

    private Recognizer ensureRecognizer() throws VoiceException {
        try {
            if (model == null) model = VoskModel.get(modelDir).acquire();
            Recognizer r = new Recognizer(model, (float) RATE);
            r.setWords(true);
            recognizer = r;
            return r;
        } catch (IllegalStateException e) {
            throw new VoiceException(VoiceException.Kind.CONFIG, String.valueOf(e.getMessage()), "vosk model unavailable", e);
        } catch (java.io.IOException e) {
            throw new VoiceException(VoiceException.Kind.PROTOCOL, "Не удалось запустить офлайн-распознавание.", "vosk: " + e, e);
        } catch (RuntimeException e) {
            throw new VoiceException(VoiceException.Kind.PROTOCOL, "Не удалось запустить офлайн-распознавание.", "vosk: " + e, e);
        }
    }

    @Override public ProviderStatus lastStatus() { return status; }

    @Override public ProviderStatus check(PcmAudio sample, CancelToken cancel) {
        try {
            SttResult r = transcribe(sample, new SttOptions(), cancel);
            double ratio = wordMatch(r.text, SAMPLE_TEXT);
            // the small model gets a lower bar than the cloud checks (60%)
            boolean ok = ratio >= 0.5;
            status = new ProviderStatus(System.currentTimeMillis(), ok, ok ? "" : "распознано меньше 50% слов",
                r.latencyMs, r.latencyMs, String.format(Locale.US, "совпадение %d%%: %s", Math.round(ratio * 100), r.text));
            return status;
        } catch (VoiceException e) {
            status = new ProviderStatus(System.currentTimeMillis(), false, e.userMessage, -1, -1, e.technical);
            return status;
        }
    }

    /** True while the recognizer (and therefore the shared native model) is loaded. */
    public boolean isLoaded() { synchronized (lock) { return recognizer != null; } }

    /** Releases the recognizer and the shared model immediately; the next transcribe reloads them. */
    public void unload() {
        synchronized (lock) { closeLocked(); }
    }

    private void closeLocked() {
        if (idleTask != null) { idleTask.cancel(false); idleTask = null; }
        if (recognizer != null) {
            try { recognizer.close(); } catch (RuntimeException ignored) { }
            recognizer = null;
            model = null;
            VoskModel.get(modelDir).release();
        }
    }

    private void scheduleIdleLocked() {
        if (idleTask != null) idleTask.cancel(false);
        idleTask = IDLER.schedule(this::idleCheck, IDLE_MS + 1000L, TimeUnit.MILLISECONDS);
    }

    private void idleCheck() {
        synchronized (lock) {
            if (recognizer != null && System.currentTimeMillis() - lastUse >= IDLE_MS) closeLocked();
        }
    }

    // ---- vosk result parsing (mirrors LocalWake.decide, without wake matching) -------------------------------------------

    static String assembleText(List<String> resultJsons) {
        StringBuilder text = new StringBuilder();
        for (String json : resultJsons) {
            if (json == null || json.isEmpty()) continue;
            JSONObject o = parse(json);
            if (o == null) continue;
            String t = o.optString("text", "").trim();
            if (!t.isEmpty()) { if (text.length() > 0) text.append(' '); text.append(t); }
        }
        return text.toString().trim();
    }

    static float averageConfidence(List<String> resultJsons) {
        float sum = 0;
        int n = 0;
        for (String json : resultJsons) {
            if (json == null || json.isEmpty()) continue;
            JSONObject o = parse(json);
            if (o == null) continue;
            JSONArray arr = o.optJSONArray("result");
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject w = arr.optJSONObject(i);
                if (w == null) continue;
                double c = w.optDouble("conf", -1);
                if (c >= 0) { sum += c; n++; }
            }
        }
        return n == 0 ? -1f : sum / n;
    }

    private static JSONObject parse(String s) {
        try { return new JSONObject(s); } catch (Exception e) { return null; }
    }

    /** Fraction of expected words present in the recognized text (0..1), tolerant to punctuation/ё/case. */
    static double wordMatch(String recognized, String expected) {
        Set<String> got = words(recognized);
        if (got.isEmpty()) return 0;
        String[] want = words(expected).toArray(new String[0]);
        int hit = 0;
        for (String w : want) if (got.contains(w)) hit++;
        return want.length == 0 ? 0 : (double) hit / want.length;
    }

    private static Set<String> words(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        for (String t : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) if (!t.isEmpty()) out.add(t);
        return out;
    }
}
