package app.jarvis.core.voice;

import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.J;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONObject;

/**
 * ElevenLabs Scribe speech-to-text (official API; free tier ok, does not consume TTS characters).
 * Input PCM is wrapped into a WAV container. Config keys: {@code apiKey} (required),
 * {@code model} (default {@code scribe_v1}), {@code proxy}, {@code baseUrl}.
 */
public final class ElevenLabsStt implements SttProvider {
    public static final String ID = "elevenlabs-stt";
    /** The bundled Russian sample every STT provider is checked against. */
    public static final String SAMPLE_TEXT = "Привет! Я Джарвис, ваш голосовой помощник. Доброе утро.";
    /** Containers that were verified against the API; OGG/Opus is added only after a live check. */
    static final List<String> ACCEPTED_FORMATS = Arrays.asList(AudioPacker.FLAC, AudioPacker.WAV);

    private final ElevenLabsApi api = new ElevenLabsApi();
    private volatile ProviderStatus status = ProviderStatus.NEVER;

    @Override public ProviderInfo info() {
        return new ProviderInfo(ID, "ElevenLabs Scribe",
            "scribe_v1 — 99 языков, уверенно держит русский",
            true, true, "не расходует символы TTS; лимит обработки звука на free",
            "принимает WAV/FLAC/MP3 (не AAC); до ~17 минут звука на файл",
            false, "", true, "api.elevenlabs.io", "https://elevenlabs.io");
    }

    @Override public void configure(ProviderConfig cfg) { api.configure(cfg); }

    @Override public boolean isConfigured() { return api.isConfigured(); }

    @Override public SttResult transcribe(PcmAudio audio, SttOptions opts, CancelToken cancel) throws VoiceException {
        if (!api.isConfigured()) throw new VoiceException(VoiceException.Kind.CONFIG, "Не задан ключ API ElevenLabs.", "no apiKey", null);
        SttOptions o = opts == null ? new SttOptions() : opts;
        AudioPacker.Packed up = o.packer.packFor(audio, o.format, ACCEPTED_FORMATS);
        long t0 = System.nanoTime();
        JSONObject json = api.speechToText(up, languageCode(o.language), ElevenLabsApi.DEFAULT_STT_MODEL, cancel);
        SttResult r = new SttResult();
        r.text = J.str(json, "text").trim();
        r.providerId = ID;
        r.latencyMs = (System.nanoTime() - t0) / 1_000_000L;
        r.uploadedBytes = up.bytes.length;
        r.uploadFormat = up.format;
        status = new ProviderStatus(System.currentTimeMillis(), true, "", r.latencyMs, r.latencyMs,
            audio.durationMs() + " мс звука, " + up.label());
        return r;
    }

    @Override public ProviderStatus lastStatus() { return status; }

    @Override public ProviderStatus check(PcmAudio sample, CancelToken cancel) {
        try {
            SttResult r = transcribe(sample, new SttOptions(), cancel);
            double ratio = wordMatch(r.text, SAMPLE_TEXT);
            boolean ok = ratio >= 0.6;
            status = new ProviderStatus(System.currentTimeMillis(), ok, ok ? "" : "распознано меньше 60% слов",
                r.latencyMs, r.latencyMs, String.format(Locale.US, "совпадение %d%%: %s", Math.round(ratio * 100), r.text));
            return status;
        } catch (VoiceException e) {
            status = new ProviderStatus(System.currentTimeMillis(), false, e.userMessage, -1, -1, e.technical);
            return status;
        }
    }

    /** Scribe wants ISO-639-3 ("rus"); SttOptions carries "ru". */
    static String languageCode(String lang) {
        if (lang == null || lang.isEmpty()) return "rus";
        if (lang.equalsIgnoreCase("ru")) return "rus";
        if (lang.equalsIgnoreCase("en")) return "eng";
        return lang;
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
        for (String w : (s == null ? "" : s).toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{L}\\p{Nd}]+")) {
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }
}
