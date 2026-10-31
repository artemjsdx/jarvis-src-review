package app.jarvis.core.voice;

import app.jarvis.core.net.Http;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.J;
import app.jarvis.core.util.Redactor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;

/**
 * Groq Whisper speech-to-text (official OpenAI-compatible endpoint, free tier with daily limits).
 * Groq accepts only real containers (WAV/FLAC/MP3/OGG-Opus); AAC/ADTS is rejected. The upload format comes from
 * {@link AudioPacker#packFor}: the format with the least time to deliver (WAV on a fast link, FLAC on a middling one, OGG/Opus on a slow one).
 *
 * <p>{@link ProviderConfig} keys:
 * <ul>
 *   <li>{@code apiKey} (required) — from console.groq.com.</li>
 *   <li>{@code model} — default {@code whisper-large-v3-turbo}; {@code whisper-large-v3} for max accuracy.</li>
 *   <li>{@code proxy}, {@code baseUrl}.</li>
 * </ul>
 */
public final class GroqStt implements SttProvider {
    public static final String ID = "groq-stt";
    public static final String BASE = "https://api.groq.com/openai/v1";
    public static final String DEFAULT_MODEL = "whisper-large-v3-turbo";
    /** Containers Groq accepts (checked live: ogg/opus 11 KB transcribes like WAV; AAC/ADTS is rejected). */
    static final List<String> ACCEPTED_FORMATS = Arrays.asList(AudioPacker.OPUS, AudioPacker.FLAC, AudioPacker.WAV);

    private String apiKey = "", proxy = "", baseUrl = BASE;
    private String model = DEFAULT_MODEL;
    private volatile ProviderStatus status = ProviderStatus.NEVER;

    @Override public ProviderInfo info() {
        return new ProviderInfo(ID, "Groq Whisper",
            "whisper-large-v3-turbo (по умолчанию), whisper-large-v3",
            true, true, "бесплатный тариф с дневными лимитами запросов/аудио",
            "принимает WAV, FLAC, MP3, OGG/Opus (AAC отклоняется); по умолчанию OGG/Opus, если телефон умеет кодировать",
            false, "", true, "api.groq.com", "https://console.groq.com");
    }

    @Override public void configure(ProviderConfig cfg) {
        apiKey = cfg.get("apiKey");
        proxy = cfg.get("proxy");
        baseUrl = cfg.get("baseUrl", BASE);
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        model = cfg.get("model", DEFAULT_MODEL);
        Redactor.addSecret(apiKey);
    }

    @Override public boolean isConfigured() { return !apiKey.isEmpty(); }

    @Override public SttResult transcribe(PcmAudio audio, SttOptions opts, CancelToken cancel) throws VoiceException {
        if (!isConfigured()) throw new VoiceException(VoiceException.Kind.CONFIG, "Не задан ключ API Groq.", "no apiKey", null);
        SttOptions so = opts == null ? new SttOptions() : opts;
        String lang = so.language == null || so.language.isEmpty() ? "ru" : so.language;
        AudioPacker.Packed up = so.packer.packFor(audio, so.format, ACCEPTED_FORMATS);
        String boundary = HttpMultipart.newBoundary();
        byte[] body = HttpMultipart.build(boundary,
            new HttpMultipart.Part("file", up.fileName, up.mime, up.bytes),
            new HttpMultipart.Part("model", model),
            new HttpMultipart.Part("language", lang),
            new HttpMultipart.Part("response_format", "json"));
        long t0 = System.nanoTime();
        JSONObject o;
        HttpURLConnection conn = null;
        try {
            cancel.throwIfCancelled();
            conn = Http.open(baseUrl + "/audio/transcriptions", "POST", Http.parseProxy(proxy), 10_000, 120_000);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(body.length);
            conn.setRequestProperty("Content-Type", HttpMultipart.contentType(boundary));
            if (cancel != null) cancel.onCancel(conn::disconnect);
            try (OutputStream out = conn.getOutputStream()) { out.write(body); }
            int code = conn.getResponseCode();
            if (code >= 400) throw httpError(code, readError(conn));
            o = new JSONObject(Http.readText(conn.getInputStream(), 4_000_000));
        } catch (CancelToken.CancelledException e) {
            throw VoiceException.cancelled();
        } catch (VoiceException e) {
            throw e;
        } catch (IOException e) {
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw new VoiceException(VoiceException.Kind.NETWORK, "Нет связи с Groq.", "stt: " + e, e);
        } catch (org.json.JSONException e) {
            throw new VoiceException(VoiceException.Kind.PROTOCOL, "Groq ответил неожиданным образом.", "stt: " + e, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        SttResult r = new SttResult();
        r.text = J.str(o, "text").trim();
        r.providerId = ID;
        r.latencyMs = (System.nanoTime() - t0) / 1_000_000L;
        r.uploadedBytes = body.length;
        r.uploadFormat = up.format;
        status = new ProviderStatus(System.currentTimeMillis(), true, "", r.latencyMs, r.latencyMs, model + ", " + up.label());
        return r;
    }

    @Override public ProviderStatus lastStatus() { return status; }

    @Override public ProviderStatus check(PcmAudio sample, CancelToken cancel) {
        try {
            SttResult r = transcribe(sample, new SttOptions(), cancel);
            double ratio = ElevenLabsStt.wordMatch(r.text, ElevenLabsStt.SAMPLE_TEXT);
            boolean ok = ratio >= 0.6;
            status = new ProviderStatus(System.currentTimeMillis(), ok, ok ? "" : "распознано меньше 60% слов",
                r.latencyMs, r.latencyMs, String.format(java.util.Locale.US, "совпадение %d%%: %s", Math.round(ratio * 100), r.text));
            return status;
        } catch (VoiceException e) {
            status = new ProviderStatus(System.currentTimeMillis(), false, e.userMessage, -1, -1, e.technical);
            return status;
        }
    }

    private static VoiceException httpError(int code, String body) {
        String b = body == null ? "" : body.toLowerCase();
        String detail = "stt HTTP " + code + (body.isEmpty() ? "" : ": " + (body.length() > 200 ? body.substring(0, 200) + "…" : body));
        if (code == 401) return new VoiceException(VoiceException.Kind.AUTH, "Ключ API Groq отклонён: проверьте его в настройках.", detail, null);
        if (code == 413) return new VoiceException(VoiceException.Kind.CONFIG, "Аудио слишком велико для Groq (лимит — 25 МБ).", detail, null);
        if (code == 429) return new VoiceException(VoiceException.Kind.RATE_LIMIT,
            "Достигнут лимит Groq (бесплатный тариф: запросы и минуты звука в день), попробуйте позже.", detail, null);
        if (code == 400 && (b.contains("format") || b.contains("codec") || b.contains("aac") || b.contains("decode")))
            return new VoiceException(VoiceException.Kind.UNSUPPORTED,
                "Groq не понял формат аудио: нужен WAV, FLAC или MP3.", detail, null);
        if (code == 400 || code == 422) return new VoiceException(VoiceException.Kind.CONFIG, "Groq отклонил запрос: проверьте модель и параметры.", detail, null);
        if (code >= 500) return new VoiceException(VoiceException.Kind.SERVER, "Groq временно недоступен.", detail, null);
        return new VoiceException(VoiceException.Kind.PROTOCOL, "Groq ответил ошибкой.", detail, null);
    }

    private static String readError(HttpURLConnection conn) {
        try {
            InputStream e = conn.getErrorStream();
            return e == null ? "" : Redactor.redact(Http.readText(e, 4096)).replace('\n', ' ');
        } catch (Exception ignored) { return ""; }
    }
}
