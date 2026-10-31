package app.jarvis.wake;

import app.jarvis.core.audio.WakeMatcher;
import app.jarvis.core.log.Journal;
import app.jarvis.core.voice.PcmAudio;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Recognizer;

/**
 * Offline wake-word gate: a Vosk {@link Recognizer} constrained to a tiny grammar ("джарвис" and its realistic
 * mishearings from {@link WakeMatcher#defaultRu()} plus {@code [unk]}) so an utterance decodes in tens of
 * milliseconds instead of being uploaded to a cloud STT.
 *
 * <p>{@link #detect} is thread-safe (calls serialize on the recognizer, which is single-threaded by design)
 * and never throws — an unavailable native library or a missing model comes back as {@link Detection#error}
 * with a Russian message, and the caller should fall back to its network path. The model loads on the first
 * call and closes again after {@link #IDLE_MS} of silence or an explicit {@link #unload()}.</p>
 *
 * <p>The found/score decision is made by the very same {@link WakeMatcher} that screens cloud transcripts,
 * so wake mode behaves identically online and offline; Vosk word timings give the position of the wake word
 * inside the utterance.</p>
 */
public final class LocalWake {
    public static final int RATE = 16000;
    /** Close the recognizer and the shared model after this much inactivity. */
    public static final long IDLE_MS = 120_000L;
    static final String MIN_GRAMMAR = "[\"джарвис\", \"[unk]\"]";
    private static final int CHUNK_BYTES = 6400; // 200 ms of 16 kHz s16le mono

    private static final ScheduledExecutorService IDLER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jv-wake-idle"); t.setDaemon(true); return t;
    });

    public static final class Detection {
        public static final Detection NOT_FOUND = new Detection(false, 0, "", -1f, 0, "");

        public final boolean found;
        /** Milliseconds from the start of the utterance to the onset of the wake word; 0 when absent. */
        public final int positionMs;
        /** What the local recognizer heard (grammar-restricted text, "" on error). */
        public final String localText;
        /** Confidence 0..1 of the wake word as reported by Vosk, -1 when not found. */
        public final float score;
        public final long decodeMs;
        /** User-safe Russian error, "" when detection actually ran. */
        public final String error;

        Detection(boolean found, int positionMs, String localText, float score, long decodeMs, String error) {
            this.found = found; this.positionMs = positionMs; this.localText = localText == null ? "" : localText;
            this.score = score; this.decodeMs = decodeMs; this.error = error == null ? "" : error;
        }

        static Detection error(String message) { return new Detection(false, 0, "", -1f, 0, message); }

        public boolean ok() { return error.isEmpty(); }
    }

    /** Pure result of decoding + matching, split out of {@link #detect} for tests. */
    static final class Decision {
        final boolean found;
        final int positionMs;
        final String text;
        final float score;

        Decision(boolean found, int positionMs, String text, float score) {
            this.found = found; this.positionMs = positionMs; this.text = text; this.score = score;
        }
    }

    private final VoskModel model;
    private final File modelDir;
    private final WakeMatcher matcher;
    private final String grammar;
    private final Journal journal;
    private final Object lock = new Object();
    private Recognizer recognizer;       // guarded by lock
    private long lastUse;                // guarded by lock
    private long fedBytes;               // guarded by lock; audio fed to the current recognizer (vosk timestamps are cumulative)
    private ScheduledFuture<?> idleTask; // guarded by lock

    public LocalWake(File modelDir) { this(modelDir, WakeMatcher.defaultRu().phraseList(), null); }

    public LocalWake(File modelDir, List<String> wakePhrases, Journal journal) {
        this.modelDir = modelDir;
        this.model = VoskModel.get(modelDir);
        this.matcher = new WakeMatcher(wakePhrases);
        this.grammar = buildGrammar(wakePhrases);
        this.journal = journal == null ? Journal.NOP : journal;
    }

    /** JSON array of grammar tokens: every word of every phrase plus [unk]. */
    static String buildGrammar(List<String> phrases) {
        Set<String> words = new LinkedHashSet<>();
        words.add("[unk]");
        for (String p : phrases) for (String w : p.trim().split("\\s+")) if (!w.isEmpty()) words.add(w);
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String w : words) {
            if (!first) sb.append(", ");
            first = false;
            sb.append('"').append(w.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    /**
     * Optional native setup for hosts without an APK classloader (verification via app_process): points JNA
     * at a plain directory holding libjnidispatch.so/libvosk.so and pre-loads both. A normal installed APK
     * needs none of this — JNA loads the libraries through the standard runtime. Must run before the first
     * Vosk/JNA class is touched. Never throws.
     */
    public static void ensureNativeLibrary(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        String p = dir.getAbsolutePath();
        System.setProperty("jna.boot.library.path", p);
        System.setProperty("jna.library.path", p);
        loadQuiet(new File(dir, "libjnidispatch.so"));
        loadQuiet(new File(dir, "libvosk.so"));
    }

    private static void loadQuiet(File so) {
        if (!so.isFile()) return;
        try { System.load(so.getAbsolutePath()); } catch (Throwable ignored) { }
    }

    public boolean isLoaded() { synchronized (lock) { return recognizer != null; } }

    /** Availability problem in Russian ("" when detection can run); loads nothing. */
    public String availabilityProblem() {
        if (!WakePack.looksInstalled(modelDir)) return "Модель офлайн-распознавания не установлена.";
        return "";
    }

    /**
     * Screens one utterance for the wake word. Never throws; on a missing pack or a broken native library
     * the returned {@link Detection} carries a Russian {@link Detection#error}.
     */
    public Detection detect(PcmAudio utterance) {
        if (utterance == null || utterance.sampleRate != RATE)
            return Detection.error("Локальное распознавание ожидает PCM 16 кГц");
        if (utterance.pcm.length == 0) return Detection.NOT_FOUND;
        long t0 = System.nanoTime();
        Recognizer r;
        long offsetMs;
        synchronized (lock) {
            if (recognizer == null) {
                try {
                    recognizer = createRecognizer();
                    fedBytes = 0;   // a fresh recognizer starts its clock at zero
                } catch (RuntimeException e) {
                    String msg = e instanceof IllegalStateException ? e.getMessage() : "Офлайн-распознавание недоступно: " + model.loadError();
                    return Detection.error(msg);
                } catch (LinkageError e) {
                    return Detection.error("Офлайн-распознавание недоступно: " + model.loadError());
                }
            }
            r = recognizer;
            offsetMs = fedBytes / 32;   // 32000 bytes per second of s16le mono
        }
        List<String> chunks = new ArrayList<>();
        try {
            byte[] pcm = utterance.pcm;
            for (int off = 0; off < pcm.length; off += CHUNK_BYTES) {
                int n = Math.min(CHUNK_BYTES, pcm.length - off);
                byte[] piece = n == pcm.length ? pcm : Arrays.copyOfRange(pcm, off, off + n);
                boolean endpoint = r.acceptWaveForm(piece, n);
                synchronized (lock) { fedBytes += n; }
                if (endpoint) chunks.add(r.getResult());
            }
            chunks.add(r.getFinalResult());
            r.reset();
        } catch (RuntimeException e) {
            return Detection.error("Ошибка офлайн-распознавания: " + e.getMessage());
        }
        long decodeMs = (System.nanoTime() - t0) / 1_000_000L;
        int utteranceMs = (int) Math.min(Integer.MAX_VALUE, utterance.pcm.length / 32L);
        Decision d = decide(chunks, matcher, offsetMs, utteranceMs);
        synchronized (lock) {
            lastUse = System.currentTimeMillis();
            scheduleIdleLocked();
        }
        journal.log(Journal.Cat.WAKE, Journal.Level.DEBUG,
            "Офлайн-проверка обращения: " + (d.found ? "найдено" : "не найдено") + " («" + d.text + "»)",
            null, null, decodeMs);
        return new Detection(d.found, d.positionMs, d.text, d.score, decodeMs, "");
    }

    private Recognizer createRecognizer() {
        org.vosk.Model m = model.acquire();   // IllegalStateException with a Russian message on failure
        boolean ok = false;
        try {
            Recognizer r;
            try {
                r = new Recognizer(m, (float) RATE, grammar);
            } catch (IOException e) {
                // a future model pack may lack some variant tokens; the bare wake word must still work
                r = new Recognizer(m, (float) RATE, MIN_GRAMMAR);
            }
            r.setWords(true);
            ok = true;
            return r;
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось создать распознаватель обращения: " + e.getMessage(), e);
        } finally {
            if (!ok) model.release();
        }
    }

    /** Releases the recognizer and the model immediately; the next detect reloads them. */
    public void unload() {
        synchronized (lock) { closeLocked(); }
    }

    private void closeLocked() {
        if (idleTask != null) { idleTask.cancel(false); idleTask = null; }
        if (recognizer != null) {
            try { recognizer.close(); } catch (RuntimeException ignored) { }
            recognizer = null;
            model.release();
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

    // ---- decoding + matching over vosk result chunks -------------------------------------------------------------------

    /**
     * Aggregates vosk result JSONs and applies the shared {@link WakeMatcher} rules; pure, no native state.
     * Vosk word timestamps are cumulative over the recognizer's lifetime, so {@code offsetMs} (audio fed before
     * this utterance) is subtracted and the position clamped to the utterance; vosk's endpointer lookahead can
     * push a tail timestamp slightly past the utterance end, hence the clamp rather than an exact bound.
     */
    static Decision decide(List<String> resultJsons, WakeMatcher matcher, long offsetMs, int utteranceMs) {
        StringBuilder text = new StringBuilder();
        List<String> words = new ArrayList<>();
        List<float[]> times = new ArrayList<>();   // {startSec, conf}
        for (String json : resultJsons) {
            if (json == null || json.isEmpty()) continue;
            JSONObject o = parse(json);
            if (o == null) continue;
            String t = o.optString("text", "").trim();
            if (!t.isEmpty()) { if (text.length() > 0) text.append(' '); text.append(t); }
            JSONArray arr = o.optJSONArray("result");
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject w = arr.optJSONObject(i);
                if (w == null) continue;
                words.add(w.optString("word", ""));
                times.add(new float[]{(float) w.optDouble("start", 0), (float) w.optDouble("conf", -1)});
            }
        }
        String full = text.toString().trim();
        WakeMatcher.Match m = matcher.match(full);
        if (!m.found) return new Decision(false, 0, full, -1f);
        // position: onset of the last word of the matched span, searched backwards through the timings
        String lastWord = WakeMatcher.normalize(spanTail(m.heard));
        int positionMs = 0;
        float conf = -1f;
        for (int i = words.size() - 1; i >= 0; i--) {
            if (lastWord.isEmpty() || WakeMatcher.normalize(words.get(i)).equals(lastWord)) {
                positionMs = Math.round(times.get(i)[0] * 1000f) - (int) offsetMs;
                if (positionMs < 0) positionMs = 0;
                if (positionMs > utteranceMs) positionMs = utteranceMs;
                conf = times.get(i)[1];
                break;
            }
        }
        return new Decision(true, positionMs, full, conf >= 0 ? conf : m.score);
    }

    private static String spanTail(String heard) {
        if (heard == null || heard.trim().isEmpty()) return "";
        String[] w = heard.trim().split("\\s+");
        return w[w.length - 1];
    }

    private static JSONObject parse(String s) {
        try { return new JSONObject(s); } catch (Exception e) { return null; }
    }
}
