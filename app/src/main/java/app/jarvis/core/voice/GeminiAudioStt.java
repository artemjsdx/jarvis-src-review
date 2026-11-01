package app.jarvis.core.voice;

import app.jarvis.core.net.Http;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.Redactor;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/** Gemini audio understanding as optional STT; uses Google's official Interactions API with inline WAV. */
public final class GeminiAudioStt implements SttProvider {
    public static final String ID = "gemini-audio-stt";
    private static final String ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions";
    private String apiKey = "", proxy = "", model = "gemini-3.8-flash";
    private volatile ProviderStatus status = ProviderStatus.NEVER;

    @Override public ProviderInfo info() {
        return new ProviderInfo(ID, "Gemini Audio",
            "Gemini Flash · многоязычное распознавание, включая русский",
            true, true, "бесплатный API-tier с ограничениями модели и региона",
            "распознавание через понимание аудио, не потоковый STT; ответ может содержать ошибки и лишний текст",
            false, "", true, "generativelanguage.googleapis.com", "https://ai.google.dev/gemini-api/docs/audio");
    }

    @Override public void configure(ProviderConfig config) {
        apiKey = config.get("apiKey");
        proxy = config.get("proxy");
        model = config.get("model", "gemini-3.8-flash");
        Redactor.addSecret(apiKey);
    }

    @Override public boolean isConfigured() { return !apiKey.isEmpty(); }

    @Override public SttResult transcribe(PcmAudio audio, SttOptions opts, CancelToken cancel) throws VoiceException {
        if (!isConfigured()) throw new VoiceException(VoiceException.Kind.CONFIG,
            "Gemini: задайте ключ Google AI Studio.", "missing key", null);
        byte[] wav = audio.toWav();
        if (wav.length > 14_000_000) throw new VoiceException(VoiceException.Kind.CONFIG,
            "Слишком длинная запись для Gemini Audio.", "inline audio limit", null);
        final long start = System.nanoTime();
        HttpURLConnection connection = null;
        try {
            cancel.throwIfCancelled();
            JSONObject prompt = new JSONObject();
            prompt.put("type", "text");
            prompt.put("text", "Распознай речь на аудио. Верни ТОЛЬКО дословную транскрипцию. "
                + "Не добавляй пояснений, перевода, кавычек и домыслов. Если речи нет, верни пустую строку.");
            JSONObject sound = new JSONObject();
            sound.put("type", "audio");
            sound.put("mime_type", "audio/wav");
            sound.put("data", Base64.getEncoder().encodeToString(wav));
            JSONObject payload = new JSONObject();
            payload.put("model", model);
            JSONArray input = new JSONArray();
            input.put(prompt);
            input.put(sound);
            payload.put("input", input);
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection = Http.open(ENDPOINT, "POST", Http.parseProxy(proxy), 12_000, 120_000);
            connection.setRequestProperty("x-goog-api-key", apiKey);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            final HttpURLConnection active = connection;
            cancel.onCancel(active::disconnect);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int code = connection.getResponseCode();
            if (code >= 400) throw httpError(code);
            JSONObject response = new JSONObject(Http.readText(connection.getInputStream(), 4_000_000));
            String text = response.optString("output_text", "");
            if (text.isEmpty()) {
                JSONArray items = response.optJSONArray("output");
                if (items != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);
                        if (item == null) continue;
                        String part = item.optString("text", "");
                        if (!part.isEmpty()) {
                            if (sb.length() > 0) sb.append(' ');
                            sb.append(part);
                        }
                    }
                    text = sb.toString();
                }
            }
            if (text.isEmpty()) throw new VoiceException(VoiceException.Kind.PROTOCOL,
                "Gemini не вернул распознанный текст.", "empty output_text", null);
            SttResult result = new SttResult();
            result.providerId = ID;
            result.text = text.trim();
            result.latencyMs = (System.nanoTime() - start) / 1_000_000L;
            result.uploadedBytes = bytes.length;
            result.uploadFormat = AudioPacker.WAV;
            status = new ProviderStatus(System.currentTimeMillis(), true, "",
                result.latencyMs, result.latencyMs, model);
            return result;
        } catch (CancelToken.CancelledException e) {
            throw VoiceException.cancelled();
        } catch (VoiceException e) {
            throw e;
        } catch (IOException e) {
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw new VoiceException(VoiceException.Kind.NETWORK, "Нет связи с Gemini Audio.",
                e.getClass().getSimpleName(), e);
        } catch (org.json.JSONException e) {
            throw new VoiceException(VoiceException.Kind.PROTOCOL,
                "Gemini вернул неожиданный ответ.", e.getMessage(), e);
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
                ok ? "" : "контрольная фраза распознана неточно", r.latencyMs, r.latencyMs,
                "совпадение " + Math.round(ratio * 100) + "%: " + r.text);
        } catch (VoiceException e) {
            status = new ProviderStatus(System.currentTimeMillis(), false,
                e.userMessage, -1, -1, e.technical);
        }
        return status;
    }

    @Override public ProviderStatus lastStatus() { return status; }

    private static VoiceException httpError(int code) {
        if (code == 401 || code == 403) return new VoiceException(VoiceException.Kind.AUTH,
            "Gemini: ключ или регион не поддерживается.", "HTTP " + code, null);
        if (code == 429 || code == 402) return new VoiceException(VoiceException.Kind.RATE_LIMIT,
            "Gemini: лимит бесплатного API превышен.", "HTTP " + code, null);
        if (code >= 500) return new VoiceException(VoiceException.Kind.SERVER,
            "Gemini временно недоступен.", "HTTP " + code, null);
        return new VoiceException(VoiceException.Kind.PROTOCOL,
            "Gemini отклонил аудио (HTTP " + code + ").", "HTTP " + code, null);
    }
}
