package app.jarvis.core.voice;

import app.jarvis.core.net.Http;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.J;
import app.jarvis.core.util.Redactor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Google Speech API v2 — the unofficial endpoint behind Chrome's web speech recognition. No account:
 * the public Chromium browser key is built in. Raw PCM in, several JSON lines out (the first is an
 * empty {@code {"result":[]}}, the useful ones follow). Unofficial: may stop working without notice.
 *
 * <p>{@link ProviderConfig} keys:
 * <ul>
 *   <li>{@code key} — override for the built-in Chromium web-speech key.</li>
 *   <li>{@code maxResults} — default 3.</li>
 *   <li>{@code proxy}, {@code baseUrl}.</li>
 * </ul>
 */
public final class GoogleSpeechStt implements SttProvider {
    public static final String ID = "google-stt";
    public static final String BASE = "https://www.google.com/speech-api/v2";
    /** Public key shipped with Chromium for web speech; unofficial use. */
    public static final String CHROMIUM_KEY = "REDACTED";

    private String key = CHROMIUM_KEY, proxy = "", baseUrl = BASE;
    private int maxResults = 3;
    private volatile ProviderStatus status = ProviderStatus.NEVER;

    @Override public ProviderInfo info() {
        return new ProviderInfo(ID, "Google Speech (веб-ключ)",
            "движок распознавания Google, ru-RU и десятки языков",
            false, false, "квота не публикуется",
            "неофициальный интерфейс с ключом Chromium — может перестать работать без предупреждения; на входе только PCM (14–16 кГц); распознанный текст уходит на серверы Google",
            false, "", false, "www.google.com", "");
    }

    @Override public void configure(ProviderConfig cfg) {
        key = cfg.get("key", CHROMIUM_KEY);
        proxy = cfg.get("proxy");
        baseUrl = cfg.get("baseUrl", BASE);
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        maxResults = cfg.has("maxResults") ? Integer.parseInt(cfg.get("maxResults")) : 3;
        Redactor.addSecret(key);
    }

    @Override public boolean isConfigured() {
        // Public review builds replace the unofficial Chromium credential with
        // REDACTED. That marker must not be interpreted as a real API key:
        // otherwise every wake attempt results in HTTP 403.
        return !key.isEmpty() && !"REDACTED".equalsIgnoreCase(key.trim());
    }

    @Override public SttResult transcribe(PcmAudio audio, SttOptions opts, CancelToken cancel) throws VoiceException {
        if (!isConfigured())
            throw new VoiceException(VoiceException.Kind.CONFIG,
                "Ключ Google Speech не настроен. Используйте офлайн Vosk или другой STT.",
                "google-stt: missing or redacted credential", null);
        String lang = bcp47(opts == null || opts.language == null || opts.language.isEmpty() ? "ru" : opts.language);
        String url = baseUrl + "/recognize?output=json&lang=" + URLEncoder.encode(lang) + "&key=" + URLEncoder.encode(key)
            + "&maxresults=" + maxResults;
        long t0 = System.nanoTime();
        String payload;
        HttpURLConnection conn = null;
        try {
            cancel.throwIfCancelled();
            conn = Http.open(url, "POST", Http.parseProxy(proxy), 10_000, 120_000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(audio.pcm.length);
            conn.setRequestProperty("Content-Type", "audio/l16; rate=" + audio.sampleRate);
            if (cancel != null) cancel.onCancel(conn::disconnect);
            try (OutputStream out = conn.getOutputStream()) { out.write(audio.pcm); }
            int code = conn.getResponseCode();
            if (code >= 400) throw httpError(code, readError(conn));
            payload = Http.readText(conn.getInputStream(), 2_000_000);
        } catch (CancelToken.CancelledException e) {
            throw VoiceException.cancelled();
        } catch (VoiceException e) {
            throw e;
        } catch (IOException e) {
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw new VoiceException(VoiceException.Kind.NETWORK, "Нет связи с сервисом распознавания Google.", "stt: " + e, e);
        } finally {
            if (conn != null) conn.disconnect();
        }

        SttResult r = new SttResult();
        // The body is several JSON lines on purpose: {"result":[]} first, then results with alternatives.
        for (String line : payload.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            JSONObject o = J.parseOrNull(line);
            if (o == null) continue;
            JSONArray results = J.arr(o, "result");
            if (results == null || results.length() == 0) continue;
            JSONArray alts = results.optJSONObject(0) == null ? null : results.optJSONObject(0).optJSONArray("alternative");
            if (alts == null || alts.length() == 0) continue;
            String text = J.str(alts.optJSONObject(0), "transcript").trim();
            if (text.isEmpty()) continue;
            r.text = text;
            r.alternatives.clear();
            for (int i = 1; i < alts.length(); i++) {
                String alt = J.str(alts.optJSONObject(i), "transcript").trim();
                if (!alt.isEmpty()) r.alternatives.add(alt);
            }
            r.confidence = (float) alts.optJSONObject(0).optDouble("confidence", -1);
            if (results.optJSONObject(0).optBoolean("final", false)) break;
        }
        r.providerId = ID;
        r.latencyMs = (System.nanoTime() - t0) / 1_000_000L;
        status = new ProviderStatus(System.currentTimeMillis(), true, "", r.latencyMs, r.latencyMs,
            audio.durationMs() + " мс звука, rate " + audio.sampleRate);
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

    /** The v2 API wants BCP-47 ("ru-RU"); SttOptions carries ISO 639-1 ("ru"). */
    static String bcp47(String lang) {
        if (lang.indexOf('-') > 0) return lang;
        String region = "ru".equalsIgnoreCase(lang) ? "RU" : "en".equalsIgnoreCase(lang) ? "US" : "de".equalsIgnoreCase(lang) ? "DE"
            : "fr".equalsIgnoreCase(lang) ? "FR" : "es".equalsIgnoreCase(lang) ? "ES" : "it".equalsIgnoreCase(lang) ? "IT"
            : "pl".equalsIgnoreCase(lang) ? "PL" : "uk".equalsIgnoreCase(lang) ? "UA" : "be".equalsIgnoreCase(lang) ? "BY"
            : "kk".equalsIgnoreCase(lang) ? "KZ" : lang.toUpperCase(java.util.Locale.US);
        return lang + "-" + region;
    }

    private static VoiceException httpError(int code, String body) {
        String b = body == null ? "" : body.toLowerCase();
        String detail = "stt HTTP " + code + (body.isEmpty() ? "" : ": " + (body.length() > 200 ? body.substring(0, 200) + "…" : body));
        if (code == 400 && (b.contains("key") || b.contains("quota") || b.contains("limit")))
            return new VoiceException(VoiceException.Kind.QUOTA, "Квота веб-ключа распознавания Google исчерпана, попробуйте позже.", detail, null);
        if (code == 400) return new VoiceException(VoiceException.Kind.CONFIG,
            "Google отклонил аудио: нужен PCM 14–16 кГц.", detail, null);
        if (code == 403) return new VoiceException(VoiceException.Kind.QUOTA,
            "Доступ к распознаванию Google закрыт (ключ или квота).", detail, null);
        if (code >= 500) return new VoiceException(VoiceException.Kind.SERVER, "Распознавание Google временно недоступно.", detail, null);
        return new VoiceException(VoiceException.Kind.PROTOCOL, "Сервис распознавания ответил ошибкой.", detail, null);
    }

    private static String readError(HttpURLConnection conn) {
        try {
            InputStream e = conn.getErrorStream();
            return e == null ? "" : Redactor.redact(Http.readText(e, 4096)).replace('\n', ' ');
        } catch (Exception ignored) { return ""; }
    }
}
