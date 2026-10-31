package app.jarvis.core.voice;

import app.jarvis.core.net.Http;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.J;
import app.jarvis.core.util.Redactor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.Base64;
import org.json.JSONObject;

/**
 * OpenRouter audio transcriptions endpoint (official, OpenAI-compatible-ish JSON body with base64 audio).
 *
 * <p>{@link ProviderConfig} keys:
 * <ul>
 *   <li>{@code apiKey} (required) — OpenRouter key, sent as {@code Authorization: Bearer}.</li>
 *   <li>{@code model} — default {@code openai/whisper-large-v3-turbo}; also
 *       {@code mistralai/voxtral-mini-transcribe-3-0}, {@code qwen/qwen3-asr-0.6b}.</li>
 *   <li>{@code proxy}, {@code baseUrl}.</li>
 * </ul>
 * A transient connection reset is retried once; 4xx is never retried.
 */
public final class OpenRouterStt implements SttProvider {
    public static final String ID = "openrouter-stt";
    public static final String BASE = "https://openrouter.ai/api/v1";
    public static final String DEFAULT_MODEL = "openai/whisper-large-v3-turbo";

    private String apiKey = "", proxy = "", baseUrl = BASE;
    private String model = DEFAULT_MODEL;
    private volatile ProviderStatus status = ProviderStatus.NEVER;

    @Override public ProviderInfo info() {
        return new ProviderInfo(ID, "OpenRouter STT",
            "whisper-large-v3-turbo (точный, быстрый); voxtral-mini, qwen3-asr",
            false, true, "зависит от модели; whisper-large-v3-turbo — платный, ~0,04 $/час звука",
            "аudio передаётся base64 в JSON (≈ +33% к размеру); формат wav/mp3/flac",
            false, "", true, "openrouter.ai", "https://openrouter.ai");
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
        if (!isConfigured()) throw new VoiceException(VoiceException.Kind.CONFIG, "Не задан ключ API OpenRouter.", "no apiKey", null);
        String lang = opts == null || opts.language == null || opts.language.isEmpty() ? "ru" : opts.language;
        JSONObject body = new JSONObject();
        J.put(body, "model", model);
        JSONObject ia = new JSONObject();
        J.put(ia, "data", Base64.getEncoder().encodeToString(audio.toWav()));
        J.put(ia, "format", "wav");
        J.put(body, "input_audio", ia);
        J.put(body, "language", lang);
        long t0 = System.nanoTime();
        JSONObject o = null;
        for (int attempt = 1; ; attempt++) {
            HttpURLConnection conn = null;
            try {
                cancel.throwIfCancelled();
                conn = open("POST", "/audio/transcriptions", body.toString().getBytes("UTF-8"), "application/json", cancel);
                int code = conn.getResponseCode();
                if (code >= 400) throw OpenRouterTts.httpError(code, OpenRouterTts.readError(conn), "stt " + model);
                o = new JSONObject(Http.readText(conn.getInputStream(), 4_000_000));
                break;
            } catch (CancelToken.CancelledException e) {
                throw VoiceException.cancelled();
            } catch (VoiceException e) {
                throw e;
            } catch (IOException e) {
                if (cancel.isCancelled()) throw VoiceException.cancelled();
                if (attempt >= 2) throw network(e);
            } catch (org.json.JSONException e) {
                throw new VoiceException(VoiceException.Kind.PROTOCOL, "OpenRouter ответил неожиданным образом.", "stt: " + e, e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        SttResult r = new SttResult();
        r.text = J.str(o, "text").trim();
        r.providerId = ID;
        r.latencyMs = (System.nanoTime() - t0) / 1_000_000L;
        status = new ProviderStatus(System.currentTimeMillis(), true, "", r.latencyMs, r.latencyMs, model);
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

    private HttpURLConnection open(String method, String path, byte[] body, String contentType, CancelToken cancel) throws IOException {
        HttpURLConnection c = Http.open(baseUrl + path, method, Http.parseProxy(proxy), 10_000, 120_000);
        c.setRequestProperty("Authorization", "Bearer " + apiKey);
        if (body != null) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            c.setRequestProperty("Content-Type", contentType);
        }
        if (cancel != null) cancel.onCancel(c::disconnect);
        if (body != null) {
            try (OutputStream out = c.getOutputStream()) { out.write(body); }
        }
        return c;
    }

    private static VoiceException network(IOException e) {
        return new VoiceException(VoiceException.Kind.NETWORK, "Нет связи с OpenRouter.", "stt: " + e, e);
    }
}
