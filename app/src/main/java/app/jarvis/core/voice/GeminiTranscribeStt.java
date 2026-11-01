package app.jarvis.core.voice;

import app.jarvis.core.net.Http;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.Redactor;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Dedicated Gemini 3.5 Transcribe STT via official resumable Files API + Interactions API. */
public final class GeminiTranscribeStt implements SttProvider {
    public static final String ID = "gemini-transcribe-stt";
    private static final String API = "https://generativelanguage.googleapis.com";
    private String key = "", proxy = "";
    private volatile ProviderStatus status = ProviderStatus.NEVER;

    @Override public ProviderInfo info() {
        return new ProviderInfo(ID, "Gemini 3.5 Transcribe",
            "русский · 85+ языковых вариантов; умное форматирование, словарь терминов",
            true, true, "ограниченный бесплатный уровень Gemini API",
            "официальный Files API: отдельная загрузка каждого аудиофайла перед распознаванием; не real-time поток",
            false, "", true, "generativelanguage.googleapis.com",
            "https://ai.google.dev/gemini-api/docs/transcribe");
    }

    @Override public void configure(ProviderConfig c) {
        key = c.get("apiKey"); proxy = c.get("proxy"); Redactor.addSecret(key);
    }
    @Override public boolean isConfigured() { return !key.isEmpty(); }
    @Override public ProviderStatus lastStatus() { return status; }

    @Override public SttResult transcribe(PcmAudio audio, SttOptions opts, CancelToken cancel) throws VoiceException {
        if (!isConfigured()) throw new VoiceException(VoiceException.Kind.CONFIG,
            "Задайте Gemini API-ключ для Transcribe.", "missing Gemini key", null);
        final long start = System.nanoTime();
        String fileName = "";
        try {
            cancel.throwIfCancelled();
            byte[] wav = audio.toWav();
            if (wav.length > 20_000_000) throw new VoiceException(VoiceException.Kind.CONFIG,
                "Слишком длинная запись для этого режима Gemini.", ">20 MB WAV", null);
            JSONObject file = upload(wav, cancel);
            String uri = file.optString("uri", "");
            fileName = file.optString("name", "");
            if (!uri.startsWith("https://") || !fileName.startsWith("files/"))
                throw new VoiceException(VoiceException.Kind.PROTOCOL,
                    "Gemini не вернул адрес загруженного аудио.", "missing uri/name", null);

            JSONObject content = new JSONObject().put("type", "audio")
                .put("uri", uri).put("mime_type", "audio/wav");
            JSONArray inputs = new JSONArray().put(content);
            JSONObject transcription = new JSONObject();
            transcription.put("mode", "smart");
            // The vocabulary makes Jarvis, DeepSeek and device commands less error-prone.
            transcription.put("custom_vocabulary", new JSONArray()
                .put("Джарвис").put("DeepSeek").put("Termux").put("GitHub")
                .put("OpenRouter").put("Jarvis").put("MCP"));
            JSONObject generation = new JSONObject().put("transcription_config", transcription);
            JSONObject payload = new JSONObject()
                .put("model", "gemini-3.5-transcribe")
                .put("input", inputs)
                .put("generation_config", generation);
            JSONObject answer = postJson(API + "/v1beta/interactions", payload, cancel);
            String recognized = answer.optString("output_text", "");
            if (recognized.isEmpty()) {
                JSONArray outputs = answer.optJSONArray("outputs");
                if (outputs != null) for (int i = 0; i < outputs.length(); i++) {
                    JSONObject item = outputs.optJSONObject(i);
                    if (item != null && "text".equals(item.optString("type")))
                        recognized += item.optString("text", "");
                }
            }
            if (recognized.isEmpty()) {
                JSONArray steps = answer.optJSONArray("steps");
                if (steps != null) for (int i = 0; i < steps.length(); i++) {
                    JSONObject step = steps.optJSONObject(i);
                    if (step == null || !"model_output".equals(step.optString("type"))) continue;
                    JSONArray parts = step.optJSONArray("content");
                    if (parts == null) continue;
                    for (int j = 0; j < parts.length(); j++) {
                        JSONObject part = parts.optJSONObject(j);
                        if (part != null && "text".equals(part.optString("type")))
                            recognized += part.optString("text", "");
                    }
                }
            }
            if (recognized.trim().isEmpty()) throw new VoiceException(VoiceException.Kind.NO_AUDIO,
                "Gemini Transcribe не вернул текст.", "empty response", null);
            SttResult result = new SttResult();
            result.providerId = ID;
            result.text = recognized.trim();
            result.latencyMs = (System.nanoTime() - start) / 1_000_000L;
            result.uploadedBytes = wav.length;
            result.uploadFormat = AudioPacker.WAV;
            status = new ProviderStatus(System.currentTimeMillis(), true, "",
                result.latencyMs, result.latencyMs, "Gemini 3.5 · Smart");
            return result;
        } catch (CancelToken.CancelledException e) {
            throw VoiceException.cancelled();
        } catch (VoiceException e) {
            throw e;
        } catch (IOException e) {
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw new VoiceException(VoiceException.Kind.NETWORK,
                "Нет связи с Gemini Transcribe.", e.getClass().getSimpleName(), e);
        } catch (org.json.JSONException e) {
            throw new VoiceException(VoiceException.Kind.PROTOCOL,
                "Некорректный ответ Gemini Transcribe.", e.getMessage(), e);
        } finally {
            if (fileName.matches("files/[A-Za-z0-9_-]+")) {
                try { deleteFile(fileName); } catch (Exception ignored) { }
            }
        }
    }

    private JSONObject upload(byte[] wav, CancelToken cancel) throws IOException, VoiceException, org.json.JSONException {
        HttpURLConnection start = null, body = null;
        try {
            start = Http.open(API + "/upload/v1beta/files", "POST",
                Http.parseProxy(proxy), 10_000, 30_000);
            start.setRequestProperty("x-goog-api-key", key);
            start.setRequestProperty("X-Goog-Upload-Protocol", "resumable");
            start.setRequestProperty("X-Goog-Upload-Command", "start");
            start.setRequestProperty("X-Goog-Upload-Header-Content-Length", String.valueOf(wav.length));
            start.setRequestProperty("X-Goog-Upload-Header-Content-Type", "audio/wav");
            start.setRequestProperty("Content-Type", "application/json");
            start.setDoOutput(true);
            byte[] meta = new JSONObject().put("file",
                new JSONObject().put("display_name", "Jarvis voice command")).toString()
                .getBytes(StandardCharsets.UTF_8);
            start.setFixedLengthStreamingMode(meta.length);
            final HttpURLConnection st = start;
            cancel.onCancel(st::disconnect);
            try (OutputStream out = start.getOutputStream()) { out.write(meta); }
            int code = start.getResponseCode();
            if (code >= 400) throw httpError(code);
            String uploadUrl = start.getHeaderField("X-Goog-Upload-URL");
            // Prevent a returned header from turning this authenticated upload into a request to another host.
            if (uploadUrl == null || !uploadUrl.startsWith(API + "/"))
                throw new VoiceException(VoiceException.Kind.PROTOCOL,
                    "Gemini не дал адрес загрузки аудио.", "invalid upload origin", null);

            body = Http.open(uploadUrl, "POST", Http.parseProxy(proxy), 10_000, 120_000);
            body.setRequestProperty("X-Goog-Upload-Offset", "0");
            body.setRequestProperty("X-Goog-Upload-Command", "upload, finalize");
            body.setRequestProperty("Content-Type", "audio/wav");
            body.setDoOutput(true);
            body.setFixedLengthStreamingMode(wav.length);
            final HttpURLConnection up = body;
            cancel.onCancel(up::disconnect);
            try (OutputStream out = body.getOutputStream()) { out.write(wav); }
            code = body.getResponseCode();
            if (code >= 400) throw httpError(code);
            JSONObject response = new JSONObject(Http.readText(body.getInputStream(), 100_000));
            JSONObject file = response.optJSONObject("file");
            if (file == null) throw new VoiceException(VoiceException.Kind.PROTOCOL,
                "Gemini не сохранил аудиофайл.", "missing file JSON", null);
            return file;
        } finally {
            if (start != null) start.disconnect();
            if (body != null) body.disconnect();
        }
    }

    private JSONObject postJson(String url, JSONObject payload, CancelToken cancel)
        throws IOException, VoiceException, org.json.JSONException {
        HttpURLConnection connection = null;
        try {
            cancel.throwIfCancelled();
            connection = Http.open(url, "POST", Http.parseProxy(proxy), 10_000, 120_000);
            connection.setRequestProperty("x-goog-api-key", key);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            final HttpURLConnection call = connection;
            cancel.onCancel(call::disconnect);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int code = connection.getResponseCode();
            if (code >= 400) throw httpError(code);
            return new JSONObject(Http.readText(connection.getInputStream(), 4_000_000));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void deleteFile(String fileName) throws IOException {
        HttpURLConnection c = null;
        try {
            c = Http.open(API + "/v1beta/" + fileName, "DELETE",
                Http.parseProxy(proxy), 5_000, 5_000);
            c.setRequestProperty("x-goog-api-key", key);
            c.getResponseCode();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    @Override public ProviderStatus check(PcmAudio sample, CancelToken cancel) {
        try {
            SttResult r = transcribe(sample, new SttOptions(), cancel);
            double match = ElevenLabsStt.wordMatch(r.text, ElevenLabsStt.SAMPLE_TEXT);
            boolean ok = match >= 0.6;
            status = new ProviderStatus(System.currentTimeMillis(), ok,
                ok ? "" : "контрольная фраза распознана неточно", r.latencyMs, r.latencyMs,
                "совпадение " + Math.round(match * 100) + "%: " + r.text);
        } catch (VoiceException e) {
            status = new ProviderStatus(System.currentTimeMillis(), false,
                e.userMessage, -1, -1, e.technical);
        }
        return status;
    }

    private static VoiceException httpError(int code) {
        if (code == 401 || code == 403) return new VoiceException(VoiceException.Kind.AUTH,
            "Gemini Transcribe: ключ или регион не разрешён.", "HTTP " + code, null);
        if (code == 429 || code == 402) return new VoiceException(VoiceException.Kind.RATE_LIMIT,
            "Gemini Transcribe: квота бесплатного API исчерпана.", "HTTP " + code, null);
        if (code >= 500) return new VoiceException(VoiceException.Kind.SERVER,
            "Gemini Transcribe временно недоступен.", "HTTP " + code, null);
        return new VoiceException(VoiceException.Kind.PROTOCOL,
            "Gemini Transcribe отклонил запрос.", "HTTP " + code, null);
    }
}
