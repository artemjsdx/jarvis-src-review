package app.jarvis.core.voice;

import app.jarvis.core.net.Http;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.Redactor;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Official Gemini 3.8 TTS unary WAV generation through Interactions API. */
public final class GeminiTts implements TtsProvider {
    private static final String ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions";
    private final String id, name, model;
    private String apiKey = "", proxy = "";
    private volatile ProviderStatus status = ProviderStatus.NEVER;

    public GeminiTts(String id, String name, String model) {
        this.id = id; this.name = name; this.model = model;
    }

    @Override public ProviderInfo info() {
        return new ProviderInfo(id, name,
            "Kore, Puck, Aoede, Charon, Fenrir, Zephyr и другие голоса Gemini",
            true, true, "Gemini API Free Tier с лимитами по запросам и региону",
            "генерация WAV после обработки фразы, не потоковая; нужен ключ Google AI Studio",
            false, "", true, "generativelanguage.googleapis.com",
            "https://ai.google.dev/gemini-api/docs/speech-generation");
    }

    @Override public void configure(ProviderConfig c) {
        apiKey = c.get("apiKey");
        proxy = c.get("proxy");
        Redactor.addSecret(apiKey);
    }

    @Override public boolean isConfigured() { return !apiKey.isEmpty(); }
    @Override public ProviderStatus lastStatus() { return status; }
    @Override public String quotaText(CancelToken cancel) { return "Бесплатный Gemini API-tier, лимиты в AI Studio"; }

    @Override public List<Voice> listVoices(CancelToken cancel) {
        List<Voice> voices = new ArrayList<>();
        for (String name : new String[]{"Kore", "Puck", "Aoede", "Charon", "Fenrir", "Zephyr",
            "Orus", "Leda", "Callirrhoe", "Algenib", "Iapetus", "Umbriel"}) {
            voices.add(new Voice(name, name, "multi", "", "многоязычный голос Gemini", false));
        }
        return voices;
    }

    @Override public void synthesize(TtsRequest req, AudioSink sink, CancelToken cancel) throws VoiceException {
        if (!isConfigured()) throw new VoiceException(VoiceException.Kind.CONFIG,
            "Gemini TTS: задайте ключ Google AI Studio.", "no key", null);
        if (req.text == null || req.text.trim().isEmpty())
            throw new VoiceException(VoiceException.Kind.CONFIG, "Пустой текст для Gemini TTS.");
        if (req.text.length() > 12_000)
            throw new VoiceException(VoiceException.Kind.CONFIG, "Слишком длинный текст для одного фрагмента Gemini TTS.");
        long start = System.nanoTime();
        HttpURLConnection connection = null;
        try {
            cancel.throwIfCancelled();
            JSONObject spoken = new JSONObject();
            spoken.put("type", "text");
            spoken.put("text", req.text);
            JSONObject inputTurn = new JSONObject();
            inputTurn.put("type", "user_input");
            inputTurn.put("content", new JSONArray().put(spoken));
            JSONObject config = new JSONObject();
            config.put("speech_config", new JSONArray().put(
                new JSONObject().put("voice", req.voiceId == null || req.voiceId.isEmpty() ? "Kore" : req.voiceId)));
            JSONObject request = new JSONObject();
            request.put("model", model);
            request.put("input", new JSONArray().put(inputTurn));
            request.put("response_format", new JSONObject().put("type", "audio"));
            request.put("generation_config", config);
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            connection = Http.open(ENDPOINT, "POST", Http.parseProxy(proxy), 12_000, 120_000);
            connection.setRequestProperty("x-goog-api-key", apiKey);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            final HttpURLConnection active = connection;
            cancel.onCancel(active::disconnect);
            try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            int code = connection.getResponseCode();
            if (code >= 400) throw httpError(code);
            JSONObject json = new JSONObject(Http.readText(connection.getInputStream(), 16_000_000));
            String audioBase64 = "";
            JSONArray steps = json.optJSONArray("steps");
            if (steps != null) for (int i = 0; i < steps.length(); i++) {
                JSONObject step = steps.optJSONObject(i);
                if (step == null || !"model_output".equals(step.optString("type"))) continue;
                JSONArray parts = step.optJSONArray("content");
                if (parts == null) continue;
                for (int j = 0; j < parts.length(); j++) {
                    JSONObject part = parts.optJSONObject(j);
                    if (part != null && "audio".equals(part.optString("type")))
                        audioBase64 = part.optString("data", "");
                }
            }
            if (audioBase64.isEmpty()) throw new VoiceException(VoiceException.Kind.NO_AUDIO,
                "Gemini TTS не вернул звук.", "no steps[].content[].data", null);
            byte[] wav;
            try { wav = Base64.getDecoder().decode(audioBase64); }
            catch (IllegalArgumentException e) { throw new VoiceException(VoiceException.Kind.PROTOCOL,
                "Gemini TTS вернул повреждённое аудио.", "invalid base64", e); }
            if (wav.length < 44 || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F')
                throw new VoiceException(VoiceException.Kind.PROTOCOL,
                    "Gemini TTS вернул неподдерживаемый аудиоформат.", "not WAV", null);
            cancel.throwIfCancelled();
            sink.begin(AudioSpec.wav());
            sink.write(wav, 0, wav.length);
            sink.end();
            long ms = (System.nanoTime() - start) / 1_000_000L;
            status = new ProviderStatus(System.currentTimeMillis(), true, "", ms, ms, model);
        } catch (CancelToken.CancelledException e) {
            sink.abort();
            throw VoiceException.cancelled();
        } catch (VoiceException e) {
            sink.abort();
            status = new ProviderStatus(System.currentTimeMillis(), false, e.userMessage, -1, -1, e.technical);
            throw e;
        } catch (IOException e) {
            sink.abort();
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw new VoiceException(VoiceException.Kind.NETWORK,
                "Нет связи с Gemini TTS.", e.getClass().getSimpleName(), e);
        } catch (org.json.JSONException e) {
            sink.abort();
            throw new VoiceException(VoiceException.Kind.PROTOCOL,
                "Некорректный ответ Gemini TTS.", e.getMessage(), e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Override public ProviderStatus check(String phrase, String voiceId, CancelToken cancel) {
        TtsRequest request = new TtsRequest();
        request.text = phrase == null || phrase.isEmpty() ? "Привет! Я Джарвис." : phrase;
        request.voiceId = voiceId == null ? "" : voiceId;
        try {
            synthesize(request, new AudioSink() {
                @Override public void begin(AudioSpec s) { }
                @Override public void write(byte[] b, int o, int n) { }
                @Override public void end() { }
                @Override public void abort() { }
            }, cancel);
        } catch (VoiceException ignored) { }
        return status;
    }

    private static VoiceException httpError(int code) {
        if (code == 401 || code == 403) return new VoiceException(VoiceException.Kind.AUTH,
            "Gemini TTS: проверьте ключ и доступность в регионе.", "HTTP " + code, null);
        if (code == 429 || code == 402) return new VoiceException(VoiceException.Kind.RATE_LIMIT,
            "Gemini TTS: квота бесплатного API исчерпана.", "HTTP " + code, null);
        if (code >= 500) return new VoiceException(VoiceException.Kind.SERVER,
            "Gemini TTS временно недоступен.", "HTTP " + code, null);
        return new VoiceException(VoiceException.Kind.PROTOCOL,
            "Gemini TTS отклонил запрос.", "HTTP " + code, null);
    }
}
