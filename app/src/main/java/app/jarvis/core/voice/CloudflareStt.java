package app.jarvis.core.voice;

import app.jarvis.core.net.Http;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.Redactor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONObject;

/** Official Cloudflare Workers AI speech recognition; WAV input, no paid balance required within the daily free quota. */
public final class CloudflareStt implements SttProvider {
    public static final String ID = "cloudflare-stt";
    private static final String API = "https://api.cloudflare.com/client/v4/accounts/";
    private static final String MODEL = "@cf/openai/whisper-large-v3-turbo";

    private String token = "", accountId = "", proxy = "";
    private volatile ProviderStatus status = ProviderStatus.NEVER;

    @Override public ProviderInfo info() {
        return new ProviderInfo(ID, "Cloudflare Whisper",
            "Whisper Large v3 Turbo · русский и другие языки",
            true, true, "общая бесплатная квота Workers AI: 10 000 Neurons/сутки",
            "нужны Account ID и API Token с правом Workers AI; квота делится между моделями",
            false, "", true, "api.cloudflare.com", "https://developers.cloudflare.com/workers-ai/");
    }

    @Override public void configure(ProviderConfig cfg) {
        token = cfg.get("apiKey");
        accountId = cfg.get("accountId");
        proxy = cfg.get("proxy");
        Redactor.addSecret(token);
    }

    @Override public boolean isConfigured() {
        return !token.isEmpty() && accountId.matches("[A-Za-z0-9_-]{8,128}");
    }

    @Override public SttResult transcribe(PcmAudio audio, SttOptions opts, CancelToken cancel) throws VoiceException {
        if (!isConfigured()) throw new VoiceException(VoiceException.Kind.CONFIG,
            "Cloudflare: заполните API Token и Account ID.", "missing credentials", null);
        final long t0 = System.nanoTime();
        final byte[] wav = audio.toWav();
        if (wav.length > 20_000_000) throw new VoiceException(VoiceException.Kind.CONFIG,
            "Аудиофайл слишком большой для Cloudflare.", "wav > 20MB", null);
        final SttOptions options = opts == null ? new SttOptions() : opts;
        HttpURLConnection connection = null;
        try {
            cancel.throwIfCancelled();
            JSONObject request = new JSONObject();
            request.put("audio", Base64.getEncoder().encodeToString(wav));
            request.put("task", "transcribe");
            if (options.language != null && !options.language.isEmpty())
                request.put("language", options.language);
            if (options.hint != null && !options.hint.isEmpty())
                request.put("initial_prompt", options.hint);
            byte[] bytes = request.toString().getBytes(StandardCharsets.UTF_8);
            connection = Http.open(API + accountId + "/ai/run/" + MODEL, "POST",
                Http.parseProxy(proxy), 12_000, 120_000);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            final HttpURLConnection active = connection;
            cancel.onCancel(active::disconnect);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int code = connection.getResponseCode();
            if (code >= 400) throw httpError(code);
            JSONObject response = new JSONObject(Http.readText(connection.getInputStream(), 4_000_000));
            JSONObject result = response.optJSONObject("result");
            String text = result != null ? result.optString("text", "") : response.optString("text", "");
            if (!response.optBoolean("success", true)) throw new VoiceException(VoiceException.Kind.SERVER,
                "Cloudflare не удалось распознать запись.", "success=false", null);
            SttResult out = new SttResult();
            out.providerId = ID;
            out.text = text.trim();
            out.latencyMs = (System.nanoTime() - t0) / 1_000_000L;
            out.uploadedBytes = bytes.length;
            out.uploadFormat = AudioPacker.WAV;
            status = new ProviderStatus(System.currentTimeMillis(), true, "",
                out.latencyMs, out.latencyMs, "Workers AI Whisper Turbo");
            return out;
        } catch (CancelToken.CancelledException e) {
            throw VoiceException.cancelled();
        } catch (VoiceException e) {
            throw e;
        } catch (IOException e) {
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw new VoiceException(VoiceException.Kind.NETWORK, "Нет связи с Cloudflare.",
                e.getClass().getSimpleName(), e);
        } catch (org.json.JSONException e) {
            throw new VoiceException(VoiceException.Kind.PROTOCOL,
                "Не удалось обработать ответ Cloudflare.", e.getMessage(), e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Override public ProviderStatus check(PcmAudio sample, CancelToken cancel) {
        try {
            SttResult r = transcribe(sample, new SttOptions(), cancel);
            double ratio = ElevenLabsStt.wordMatch(r.text, ElevenLabsStt.SAMPLE_TEXT);
            boolean ok = ratio >= 0.6;
            status = new ProviderStatus(System.currentTimeMillis(), ok,
                ok ? "" : "не совпал текст контрольной фразы", r.latencyMs, r.latencyMs,
                "совпадение " + Math.round(100 * ratio) + "%: " + r.text);
        } catch (VoiceException e) {
            status = new ProviderStatus(System.currentTimeMillis(), false,
                e.userMessage, -1, -1, e.technical);
        }
        return status;
    }

    @Override public ProviderStatus lastStatus() { return status; }

    private static VoiceException httpError(int code) {
        if (code == 401 || code == 403) return new VoiceException(VoiceException.Kind.AUTH,
            "Cloudflare: неправильный токен или нет доступа к Workers AI.", "HTTP " + code, null);
        if (code == 402 || code == 429) return new VoiceException(VoiceException.Kind.RATE_LIMIT,
            "Cloudflare: дневная бесплатная квота исчерпана или слишком много запросов.", "HTTP " + code, null);
        if (code >= 500) return new VoiceException(VoiceException.Kind.SERVER,
            "Cloudflare временно недоступен.", "HTTP " + code, null);
        return new VoiceException(VoiceException.Kind.PROTOCOL,
            "Cloudflare отклонил запрос (HTTP " + code + ").", "HTTP " + code, null);
    }
}
