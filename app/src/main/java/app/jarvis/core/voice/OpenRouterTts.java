package app.jarvis.core.voice;

import app.jarvis.core.net.Http;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.J;
import app.jarvis.core.util.Redactor;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * OpenRouter audio speech endpoint (official, OpenAI-compatible).
 * Free models exist ({@code fish-audio/s2.1-pro-free:free}); voice cloning from a short reference
 * is free ({@code input_references}: audio + its exact transcript, reference ≤ ~15 s / ≤ 200 KB).
 *
 * <p>{@link ProviderConfig} keys:
 * <ul>
 *   <li>{@code apiKey} (required) — OpenRouter key, sent as {@code Authorization: Bearer}.</li>
 *   <li>{@code model} — default {@code fish-audio/s2.1-pro-free:free}; {@code heygen/heygen-voice-1-turbo:free}
 *       requires a voice or a reference (400 otherwise).</li>
 *   <li>{@code responseFormat} — {@code mp3} (default), {@code wav}, {@code pcm}, {@code opus}.</li>
 *   <li>{@code proxy}, {@code baseUrl}.</li>
 * </ul>
 * A transient connection reset is retried once (seen with OpenRouter's TLS stack); 4xx is never retried.
 */
public final class OpenRouterTts implements TtsProvider {
    public static final String ID = "openrouter-tts";
    public static final String BASE = "https://openrouter.ai/api/v1";
    public static final String DEFAULT_MODEL = "fish-audio/s2.1-pro-free:free";
    public static final String DEFAULT_FORMAT = "mp3";

    private String apiKey = "", proxy = "", baseUrl = BASE;
    private String model = DEFAULT_MODEL, responseFormat = DEFAULT_FORMAT;
    private app.jarvis.core.log.Journal journal = app.jarvis.core.log.Journal.NOP;
    private volatile ProviderStatus status = ProviderStatus.NEVER;

    @Override public ProviderInfo info() {
        return new ProviderInfo(ID, "OpenRouter TTS",
            "fish-audio s2.1 pro (free); heygen voice-1 turbo (free) — нужен образец голоса",
            false, true, "есть бесплатные модели (:free); платные — по кредитам OpenRouter",
            "голос по умолчанию у fish фиксированный; свой голос — клонированием по образцу (~13 с на фразу)",
            true, "по образцу ≤15 с MP3/WAV + точная расшифровка текста образца; бесплатно; transcription берётся отдельно (STT-провайдером)",
            true, "openrouter.ai", "https://openrouter.ai");
    }

    @Override public void configure(ProviderConfig cfg) {
        apiKey = cfg.get("apiKey");
        proxy = cfg.get("proxy");
        baseUrl = cfg.get("baseUrl", BASE);
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        model = cfg.get("model", DEFAULT_MODEL);
        responseFormat = cfg.get("responseFormat", DEFAULT_FORMAT);
        journal = cfg.journal;
        Redactor.addSecret(apiKey);
    }

    @Override public boolean isConfigured() { return !apiKey.isEmpty(); }

    @Override public List<Voice> listVoices(CancelToken cancel) {
        // No named voices: model is fixed by config, custom voice arrives via a cloning reference.
        return Collections.singletonList(new Voice("", "По умолчанию (" + model + ")", "multi", "",
            "модель задаётся ключом model; свой голос — образцом в студии", false));
    }

    /** TTS model ids that currently accept speech output (includes :free ones); for settings/UI. */
    public List<String> listSpeechModels(CancelToken cancel) throws VoiceException {
        HttpURLConnection conn = null;
        try {
            conn = open("GET", "/models?output_modalities=speech", null, null, cancel);
            int code = conn.getResponseCode();
            if (code >= 400) throw httpError(code, readError(conn), "models");
            JSONObject o = new JSONObject(Http.readText(conn.getInputStream(), 4_000_000));
            JSONArray arr = J.arr(o, "data");
            List<String> out = new ArrayList<>();
            for (int i = 0; arr != null && i < arr.length(); i++) {
                String id = J.str(arr.optJSONObject(i), "id");
                if (!id.isEmpty()) out.add(id);
            }
            return out;
        } catch (CancelToken.CancelledException e) {
            throw VoiceException.cancelled();
        } catch (VoiceException e) {
            throw e;
        } catch (IOException e) {
            throw network(e, "models");
        } catch (org.json.JSONException e) {
            throw new VoiceException(VoiceException.Kind.PROTOCOL, "OpenRouter ответил неожиданным образом.", "models: " + e, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override public void synthesize(TtsRequest req, AudioSink sink, CancelToken cancel) throws VoiceException {
        if (!isConfigured()) throw new VoiceException(VoiceException.Kind.CONFIG, "Не задан ключ API OpenRouter.", "no apiKey", null);
        String model = req.options.containsKey("model") ? req.options.get("model") : this.model;
        String format = req.options.containsKey("response_format") ? req.options.get("response_format") : responseFormat;
        JSONObject body = new JSONObject();
        J.put(body, "model", model);
        J.put(body, "input", req.text);
        J.put(body, "response_format", format);
        if (!req.refAudioPath.isEmpty()) {
            try {
                byte[] ref = Files.readAllBytes(new File(req.refAudioPath).toPath());
                JSONObject audioRef = new JSONObject();
                J.put(audioRef, "type", "input_audio");
                JSONObject ia = new JSONObject();
                J.put(ia, "data", "data:" + mimeOf(req.refAudioPath) + ";base64," + Base64.getEncoder().encodeToString(ref));
                J.put(audioRef, "input_audio", ia);
                if (req.refText.isEmpty()) {
                    throw new VoiceException(VoiceException.Kind.CONFIG,
                        "Для клонирования нужна точная расшифровка текста образца.", "refText empty", null);
                }
                JSONObject textRef = new JSONObject();
                J.put(textRef, "type", "text");
                J.put(textRef, "text", req.refText);
                J.put(body, "input_references", new JSONArray(java.util.Arrays.asList(audioRef, textRef)));
            } catch (IOException e) {
                throw new VoiceException(VoiceException.Kind.CONFIG, "Не удалось прочитать образец голоса.",
                    req.refAudioPath + ": " + e, e);
            }
        }
        long t0 = System.nanoTime();
        for (int attempt = 1; ; attempt++) {
            boolean began = false;
            HttpURLConnection conn = null;
            try {
                cancel.throwIfCancelled();
                conn = open("POST", "/audio/speech", body.toString().getBytes("UTF-8"), "application/json", cancel);
                int code = conn.getResponseCode();
                if (code >= 400) throw httpError(code, readError(conn), "tts " + model);
                InputStream in = conn.getInputStream();
                sink.begin(specFor(format));
                began = true;
                long first = -1;
                byte[] buf = new byte[8192];
                int total = 0, n;
                while ((n = in.read(buf)) > 0) {
                    cancel.throwIfCancelled();
                    if (first < 0) first = (System.nanoTime() - t0) / 1_000_000L;
                    sink.write(buf, 0, n);
                    total += n;
                }
                if (total == 0) throw new VoiceException(VoiceException.Kind.NO_AUDIO, "Сервис не вернул аудио.", "tts: 0 bytes", null);
                sink.end();
                status = new ProviderStatus(System.currentTimeMillis(), true, "", first,
                    (System.nanoTime() - t0) / 1_000_000L, model + ", " + format + ", " + total / 1024 + " КБ");
                return;
            } catch (CancelToken.CancelledException c) {
                if (began) sink.abort();
                throw VoiceException.cancelled();
            } catch (VoiceException e) {
                sink.abort();
                status = new ProviderStatus(System.currentTimeMillis(), false, e.userMessage, -1,
                    (System.nanoTime() - t0) / 1_000_000L, e.technical);
                throw e;
            } catch (IOException e) {
                if (cancel.isCancelled()) {
                    if (began) sink.abort();
                    throw VoiceException.cancelled();
                }
                if (began) {
                    sink.abort();
                    status = new ProviderStatus(System.currentTimeMillis(), false, "Соединение с OpenRouter оборвалось.",
                        -1, (System.nanoTime() - t0) / 1_000_000L, model + ": " + e);
                    throw new VoiceException(VoiceException.Kind.NETWORK, "Соединение с OpenRouter оборвалось.", model + ": " + e, e);
                }
                // transient TLS/reset before any audio: retry once
                if (attempt >= 2 || cancel.isCancelled()) {
                    status = new ProviderStatus(System.currentTimeMillis(), false, "Нет связи с OpenRouter.",
                        -1, (System.nanoTime() - t0) / 1_000_000L, model + ": " + e);
                    throw network(e, "tts " + model);
                }
                journal.d(app.jarvis.core.log.Journal.Cat.TTS, "openrouter: connection reset, retrying once");
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }

    static AudioSpec specFor(String format) {
        if ("wav".equalsIgnoreCase(format)) return AudioSpec.wav();
        if ("pcm".equalsIgnoreCase(format)) return AudioSpec.pcm(24000);
        if ("opus".equalsIgnoreCase(format)) return new AudioSpec(AudioSpec.Codec.OGG_OPUS, 48000, 1);
        return AudioSpec.mp3();
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

    @Override public ProviderStatus lastStatus() { return status; }

    @Override public ProviderStatus check(String phrase, String voiceId, CancelToken cancel) {
        TtsRequest req = new TtsRequest();
        req.text = phrase;
        req.voiceId = voiceId == null ? "" : voiceId;
        try {
            synthesize(req, new DiscardSink(), cancel);
            return status;
        } catch (VoiceException e) {
            return status;
        }
    }

    @Override public String quotaText(CancelToken cancel) { return null; }

    static VoiceException httpError(int code, String body, String what) {
        String b = body == null ? "" : body.toLowerCase();
        String detail = what + " HTTP " + code + (body.isEmpty() ? "" : ": " + snippet(body));
        if (code == 401) return new VoiceException(VoiceException.Kind.AUTH, "Ключ API OpenRouter отклонён: проверьте его в настройках.", detail, null);
        if (code == 402 || b.contains("insufficient credits") || b.contains("credit"))
            return new VoiceException(VoiceException.Kind.QUOTA, "Закончились кредиты OpenRouter: пополните баланс или выберите бесплатную модель.", detail, null);
        if (code == 403 && (b.contains("country") || b.contains("region") || b.contains("geo")))
            return new VoiceException(VoiceException.Kind.GEO_BLOCK, "Эта модель OpenRouter недоступна из вашего региона.", detail, null);
        if (code == 403) return new VoiceException(VoiceException.Kind.AUTH, "OpenRouter запретил запрос: проверьте ключ и его права.", detail, null);
        if (code == 429) return new VoiceException(VoiceException.Kind.RATE_LIMIT,
            "Лимит модели OpenRouter исчерпан, попробуйте позже (у бесплатных моделей — 20 запросов в минуту).", detail, null);
        if (code == 400 || code == 404) {
            String hint = b.contains("voice") || b.contains("reference") || b.contains("input_ref")
                ? " Этой модели нужен готовый голос или образец записи." : "";
            return new VoiceException(VoiceException.Kind.CONFIG, "OpenRouter отклонил запрос: проверьте модель и параметры." + hint, detail, null);
        }
        if (code >= 500) return new VoiceException(VoiceException.Kind.SERVER, "OpenRouter временно недоступен.", detail, null);
        return new VoiceException(VoiceException.Kind.PROTOCOL, "OpenRouter ответил ошибкой.", detail, null);
    }

    private static VoiceException network(IOException e, String what) {
        return new VoiceException(VoiceException.Kind.NETWORK, "Нет связи с OpenRouter.", what + ": " + e, e);
    }

    static String readError(HttpURLConnection conn) {
        try {
            InputStream e = conn.getErrorStream();
            return e == null ? "" : Redactor.redact(Http.readText(e, 4096)).replace('\n', ' ');
        } catch (Exception ignored) { return ""; }
    }

    private static String snippet(String s) { return s.length() > 200 ? s.substring(0, 200) + "…" : s; }

    static String mimeOf(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".mp3")) return "audio/mpeg";
        if (p.endsWith(".wav")) return "audio/wav";
        if (p.endsWith(".flac")) return "audio/flac";
        if (p.endsWith(".ogg") || p.endsWith(".opus")) return "audio/ogg";
        return "audio/mpeg";
    }

    /** Throw-away sink for checks. */
    static final class DiscardSink implements AudioSink {
        long bytes;
        @Override public void begin(AudioSpec spec) throws IOException { }
        @Override public void write(byte[] data, int off, int len) throws IOException { bytes += len; }
        @Override public void end() throws IOException { }
        @Override public void abort() { }
    }
}
