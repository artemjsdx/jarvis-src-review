package app.jarvis.core.voice;

import app.jarvis.core.log.Journal;
import app.jarvis.core.net.Http;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.J;
import app.jarvis.core.util.Redactor;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Low-level ElevenLabs REST client shared by {@link ElevenLabsTts} and {@link ElevenLabsStt}.
 * Everything user-facing here must run on the free tier except where noted.
 *
 * <p>{@link ProviderConfig} keys:
 * <ul>
 *   <li>{@code apiKey} (required) — from elevenlabs.io profile; sent as {@code xi-api-key}.</li>
 *   <li>{@code model} — default {@code eleven_flash_v2_5} (fast, Russian ok; multilingual_v2/turbo_v2_5/v3/v4 also work).</li>
 *   <li>{@code voice} — default voice id, {@code EXAVITQu4vr4xnSDxMaL} (Sarah, premade).</li>
 *   <li>{@code outputFormat} — {@code pcm_24000} (default), {@code pcm_22050}, {@code pcm_16000},
 *       {@code mp3_22050_32}, {@code mp3_44100_64}, {@code opus_48000_32}; {@code pcm_44100} is Pro-only (403).</li>
 *   <li>{@code stability} (0.5), {@code similarity} (0.75), {@code style} (0) — voice settings for non-v3/v4 models.</li>
 *   <li>{@code proxy} — {@code http://host:port} or {@code socks5://user:pass@host:port}.</li>
 *   <li>{@code baseUrl} — override for tests / API gateway.</li>
 * </ul>
 */
public final class ElevenLabsApi {
    public static final String BASE = "https://api.elevenlabs.io";
    public static final String DEFAULT_MODEL = "eleven_flash_v2_5";
    public static final String DEFAULT_VOICE = "EXAVITQu4vr4xnSDxMaL";
    public static final String DEFAULT_OUTPUT_FORMAT = "pcm_24000";
    public static final String DEFAULT_STT_MODEL = "scribe_v1";
    public static final String DEFAULT_STS_MODEL = "eleven_multilingual_sts_v2";

    private String apiKey = "", proxy = "", baseUrl = BASE;
    private String model = DEFAULT_MODEL, voice = DEFAULT_VOICE, outputFormat = DEFAULT_OUTPUT_FORMAT;
    private double stability = 0.5, similarity = 0.75, style = 0.0;
    private Journal journal = Journal.NOP;
    private File tmpDir;

    public void configure(ProviderConfig cfg) {
        apiKey = cfg.get("apiKey");
        proxy = cfg.get("proxy");
        baseUrl = cfg.get("baseUrl", BASE);
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        model = cfg.get("model", DEFAULT_MODEL);
        voice = cfg.get("voice", DEFAULT_VOICE);
        outputFormat = cfg.get("outputFormat", DEFAULT_OUTPUT_FORMAT);
        stability = cfg.has("stability") ? Double.parseDouble(cfg.get("stability")) : 0.5;
        similarity = cfg.has("similarity") ? Double.parseDouble(cfg.get("similarity")) : 0.75;
        style = cfg.has("style") ? Double.parseDouble(cfg.get("style")) : 0.0;
        journal = cfg.journal;
        tmpDir = cfg.tmpDir;
        Redactor.addSecret(apiKey);
    }

    public boolean isConfigured() { return !apiKey.isEmpty(); }
    public String model() { return model; }
    public String voice() { return voice; }
    public String outputFormat() { return outputFormat; }
    public Journal journal() { return journal; }

    public static final class Subscription {
        public final String tier;
        public final long characterCount, characterLimit, nextResetUnix;
        Subscription(String tier, long used, long limit, long reset) { this.tier = tier; this.characterCount = used; this.characterLimit = limit; this.nextResetUnix = reset; }
    }

    /** Models whose text may contain English emotion tags like {@code [whispers]}, {@code [sighs]}. */
    public static boolean supportsTags(String model) {
        return model != null && (model.startsWith("eleven_v3") || model.startsWith("eleven_v4"));
    }

    public static AudioSpec specFor(String outputFormat) {
        if (outputFormat.startsWith("pcm_")) {
            try { return AudioSpec.pcm(Integer.parseInt(outputFormat.substring(4))); } catch (NumberFormatException ignored) { }
            return AudioSpec.pcm(24000);
        }
        if (outputFormat.startsWith("opus_")) return new AudioSpec(AudioSpec.Codec.OGG_OPUS, 48000, 1);
        return AudioSpec.mp3();
    }

    // ---------------------------------------------------------------- endpoints

    public Subscription subscription(CancelToken cancel) throws VoiceException {
        JSONObject o = getJson("/v1/user/subscription", cancel);
        return new Subscription(J.str(o, "tier"), J.lng(o, "character_count", -1), J.lng(o, "character_limit", -1),
            J.lng(o, "next_character_count_reset_unix", 0));
    }

    public List<Voice> listVoices(CancelToken cancel) throws VoiceException {
        JSONObject o = getJson("/v2/voices?page_size=100", cancel);
        List<Voice> out = new ArrayList<>();
        JSONArray vs = J.arr(o, "voices");
        for (int i = 0; vs != null && i < vs.length(); i++) {
            JSONObject v = vs.optJSONObject(i);
            if (v == null) continue;
            String category = J.str(v, "category", "premade");
            JSONObject labels = J.obj(v, "labels");
            String desc = category;
            if (labels != null) {
                String g = J.str(labels, "gender"), acc = J.str(labels, "accent"), age = J.str(labels, "age");
                if (!g.isEmpty() || !acc.isEmpty()) desc = category + ", " + joinNonEmpty(", ", g, age, acc);
            }
            String lang = "multi";
            JSONArray verified = v.optJSONArray("verified_languages");
            if (verified != null && verified.length() == 1) lang = J.str(verified.optJSONObject(0), "language", "multi");
            out.add(new Voice(J.str(v, "voice_id"), J.str(v, "name"), lang,
                labels != null ? J.str(labels, "gender") : "", desc, !"premade".equals(category)));
        }
        return out;
    }

    public List<String> listModels(CancelToken cancel) throws VoiceException {
        JSONArray arr = getJsonArray("/v1/models", cancel);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            String id = J.str(arr.optJSONObject(i), "model_id");
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    /**
     * Streams synthesis audio into {@code sink}; returns time to first audio byte in ms.
     * Retries once without {@code language_code} when the API rejects it (not all models accept it).
     */
    public long ttsStream(String voiceId, String text, String model, String outputFormat, String languageCode,
                          AudioSink sink, CancelToken cancel) throws VoiceException {
        return ttsStream(voiceId, text, model, outputFormat, languageCode, TtsCapabilities.DEFAULT_SPEED, sink, cancel);
    }

    /** speed is mapped to the voice setting {@code speed} (0.7–1.2); models with tags ignore voice settings. */
    public long ttsStream(String voiceId, String text, String model, String outputFormat, String languageCode, float speed,
                          AudioSink sink, CancelToken cancel) throws VoiceException {
        try {
            return ttsStreamOnce(voiceId, text, model, outputFormat, languageCode, speed, sink, cancel);
        } catch (VoiceException e) {
            if (!languageCode.isEmpty() && e.technical.contains("language_code")) {
                journal.d(Journal.Cat.TTS, "elevenlabs: retry without language_code");
                return ttsStreamOnce(voiceId, text, model, outputFormat, "", speed, sink, cancel);
            }
            throw e;
        }
    }

    private long ttsStreamOnce(String voiceId, String text, String model, String outputFormat, String languageCode, float speed,
                               AudioSink sink, CancelToken cancel) throws VoiceException {
        JSONObject body = new JSONObject();
        J.put(body, "text", text);
        J.put(body, "model_id", model);
        if (!supportsTags(model)) J.put(body, "voice_settings", voiceSettings(speed));
        if (!languageCode.isEmpty()) J.put(body, "language_code", languageCode);
        cancel.throwIfCancelled();
        long t0 = System.nanoTime();
        HttpURLConnection conn = null;
        boolean began = false;
        try {
            conn = open("POST", "/v1/text-to-speech/" + voiceId + "/stream?output_format=" + outputFormat,
                body.toString().getBytes("UTF-8"), "application/json", cancel);
            int code = conn.getResponseCode();
            if (code >= 400) throw httpError(code, readError(conn), "tts " + model);
            InputStream in = conn.getInputStream();
            sink.begin(specFor(outputFormat));
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
            return first;
        } catch (CancelToken.CancelledException c) {
            if (began) sink.abort();
            throw VoiceException.cancelled();
        } catch (VoiceException e) {
            if (began) sink.abort();
            throw e;
        } catch (IOException e) {
            if (began) sink.abort();
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw network(e, "tts");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Voice changer: input audio (wav/mp3 bytes) → cloned speech as MP3. Works on free. */
    public byte[] speechToSpeech(String voiceId, byte[] audio, String mime, String filename, String model, CancelToken cancel) throws VoiceException {
        JSONObject settings = new JSONObject();
        J.put(settings, "stability", stability);
        J.put(settings, "similarity_boost", similarity);
        String boundary = HttpMultipart.newBoundary();
        byte[] mp3 = requestBytes("POST",
            "/v1/speech-to-speech/" + voiceId + "?output_format=mp3_22050_32",
            HttpMultipart.build(boundary,
                new HttpMultipart.Part("model_id", model),
                new HttpMultipart.Part("voice_settings", settings.toString()),
                new HttpMultipart.Part("audio", filename, mime, audio)),
            HttpMultipart.contentType(boundary), cancel, "sts");
        return mp3;
    }

    /**
     * Voice Design: text description → previews (generated_voice_id + sample audio). Requires a PAID plan;
     * on free the API answers 403 which is classified as {@link VoiceException.Kind#UNSUPPORTED}.
     */
    public JSONObject designVoice(String voiceDescription, CancelToken cancel) throws VoiceException {
        JSONObject body = new JSONObject();
        J.put(body, "voice_description", voiceDescription);
        J.put(body, "auto_generate_text", true);
        return postJson("/v1/text-to-voice/design", body, cancel, "design");
    }

    /**
     * Instant Voice Cloning: sample audio + name → new voice_id. Requires a PAID plan (free → 403,
     * classified as {@link VoiceException.Kind#UNSUPPORTED}).
     */
    public String cloneVoice(String name, byte[] audio, String mime, CancelToken cancel) throws VoiceException {
        String boundary = HttpMultipart.newBoundary();
        JSONObject o = requestJson("POST", "/v1/voices/add",
            HttpMultipart.build(boundary,
                new HttpMultipart.Part("name", name),
                new HttpMultipart.Part("files", "sample." + extOf(mime), mime, audio)),
            HttpMultipart.contentType(boundary), cancel, "clone");
        return J.str(o, "voice_id");
    }

    /** Scribe STT. The upload is an encoded container (see {@link AudioPacker}); the caller picks the format.
     *  Idempotent upload: up to 2 retries on NETWORK failures (this TLS path stalls often on slow links). */
    public JSONObject speechToText(AudioPacker.Packed upload, String languageCode, String model, CancelToken cancel) throws VoiceException {
        String boundary = HttpMultipart.newBoundary();
        VoiceException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return requestJson("POST", "/v1/speech-to-text",
                    HttpMultipart.build(boundary,
                        new HttpMultipart.Part("model_id", model),
                        new HttpMultipart.Part("language_code", languageCode),
                        new HttpMultipart.Part("file", upload.fileName, upload.mime, upload.bytes)),
                    HttpMultipart.contentType(boundary), cancel, "stt");
            } catch (VoiceException e) {
                if (e.kind != VoiceException.Kind.NETWORK || cancel.isCancelled()) throw e;
                last = e;
                journal.d(Journal.Cat.STT, "elevenlabs: stt network retry " + (attempt + 1));
            }
        }
        throw last;
    }

    /** "осталось 9 120 из 10 000 символов (до 27.11.2026)" or null when the API answer is unusable. */
    public String quotaText(CancelToken cancel) throws VoiceException {
        Subscription s = subscription(cancel);
        if (s.characterLimit <= 0) return null;
        StringBuilder sb = new StringBuilder("осталось ").append(group(s.characterLimit - s.characterCount))
            .append(" из ").append(group(s.characterLimit)).append(" символов");
        if (s.nextResetUnix > 0) {
            java.time.ZonedDateTime d = java.time.Instant.ofEpochSecond(s.nextResetUnix).atZone(java.time.ZoneOffset.UTC);
            sb.append(" (до ").append(d.getDayOfMonth()).append('.').append(d.getMonthValue()).append('.').append(d.getYear()).append(')');
        }
        return sb.toString();
    }

    static String group(long n) {
        String s = Long.toString(n);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) sb.append(' ');
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }

    /** The API accepts a speed of 0.7–1.2 (1.0 = normal); faster requests are capped there. */
    static double apiSpeed(float speed) {
        return Math.max(0.7, Math.min(1.2, TtsCapabilities.clampSpeed(speed)));
    }

    private JSONObject voiceSettings(float speed) {
        JSONObject o = new JSONObject();
        J.put(o, "stability", stability);
        J.put(o, "similarity_boost", similarity);
        J.put(o, "style", style);
        J.put(o, "use_speaker_boost", true);
        J.put(o, "speed", apiSpeed(speed));
        return o;
    }

    // ---------------------------------------------------------------- transport

    private HttpURLConnection open(String method, String pathWithQuery, byte[] body, String contentType, CancelToken cancel) throws IOException {
        HttpURLConnection c = Http.open(baseUrl + pathWithQuery, method, Http.parseProxy(proxy), 10_000, 120_000);
        c.setRequestProperty("xi-api-key", apiKey);
        c.setRequestProperty("Accept", "application/json");
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

    /** One retry on PROTOCOL (truncated/corrupt body) — catalog GETs are idempotent and this TLS path glitches. */
    private JSONObject getJson(String path, CancelToken cancel) throws VoiceException {
        try {
            return getJsonOnce(path, cancel);
        } catch (VoiceException e) {
            if (e.kind != VoiceException.Kind.PROTOCOL) throw e;
            return getJsonOnce(path, cancel);
        }
    }

    private JSONArray getJsonArray(String path, CancelToken cancel) throws VoiceException {
        try {
            return getJsonArrayOnce(path, cancel);
        } catch (VoiceException e) {
            if (e.kind != VoiceException.Kind.PROTOCOL) throw e;
            return getJsonArrayOnce(path, cancel);
        }
    }

    private JSONObject getJsonOnce(String path, CancelToken cancel) throws VoiceException {
        HttpURLConnection conn = null;
        try {
            conn = open("GET", path, null, null, cancel);
            int code = conn.getResponseCode();
            if (code >= 400) throw httpError(code, readError(conn), "GET " + path);
            return new JSONObject(Http.readText(conn.getInputStream(), 4_000_000));
        } catch (CancelToken.CancelledException e) {
            throw VoiceException.cancelled();
        } catch (VoiceException e) {
            throw e;
        } catch (IOException e) {
            throw network(e, "GET " + path);
        } catch (org.json.JSONException e) {
            throw new VoiceException(VoiceException.Kind.PROTOCOL, "Сервис ElevenLabs ответил неожиданным образом.", "GET " + path + ": " + e, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private JSONArray getJsonArrayOnce(String path, CancelToken cancel) throws VoiceException {
        HttpURLConnection conn = null;
        try {
            conn = open("GET", path, null, null, cancel);
            int code = conn.getResponseCode();
            if (code >= 400) throw httpError(code, readError(conn), "GET " + path);
            return new JSONArray(Http.readText(conn.getInputStream(), 4_000_000));
        } catch (CancelToken.CancelledException e) {
            throw VoiceException.cancelled();
        } catch (VoiceException e) {
            throw e;
        } catch (IOException e) {
            throw network(e, "GET " + path);
        } catch (org.json.JSONException e) {
            throw new VoiceException(VoiceException.Kind.PROTOCOL, "Сервис ElevenLabs ответил неожиданным образом.", "GET " + path + ": " + e, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private JSONObject postJson(String path, JSONObject body, CancelToken cancel, String what) throws VoiceException {
        return requestJson("POST", path, body.toString().getBytes(), "application/json", cancel, what);
    }

    private JSONObject requestJson(String method, String path, byte[] body, String contentType, CancelToken cancel, String what) throws VoiceException {
        HttpURLConnection conn = null;
        try {
            conn = open(method, path, body, contentType, cancel);
            int code = conn.getResponseCode();
            if (code >= 400) throw httpError(code, readError(conn), what);
            return new JSONObject(Http.readText(conn.getInputStream(), 4_000_000));
        } catch (CancelToken.CancelledException e) {
            throw VoiceException.cancelled();
        } catch (VoiceException e) {
            throw e;
        } catch (IOException e) {
            throw network(e, what);
        } catch (org.json.JSONException e) {
            throw new VoiceException(VoiceException.Kind.PROTOCOL, "Сервис ElevenLabs ответил неожиданным образом.", what + ": " + e, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private byte[] requestBytes(String method, String path, byte[] body, String contentType, CancelToken cancel, String what) throws VoiceException {
        HttpURLConnection conn = null;
        try {
            conn = open(method, path, body, contentType, cancel);
            int code = conn.getResponseCode();
            if (code >= 400) throw httpError(code, readError(conn), what);
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                cancel.throwIfCancelled();
                bo.write(buf, 0, n);
            }
            if (bo.size() == 0) throw new VoiceException(VoiceException.Kind.NO_AUDIO, "Сервис не вернул аудио.", what + ": 0 bytes", null);
            return bo.toByteArray();
        } catch (CancelToken.CancelledException e) {
            throw VoiceException.cancelled();
        } catch (VoiceException e) {
            throw e;
        } catch (IOException e) {
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw network(e, what);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readError(HttpURLConnection conn) {
        try {
            InputStream e = conn.getErrorStream();
            return e == null ? "" : Redactor.redact(Http.readText(e, 4096)).replace('\n', ' ');
        } catch (Exception ignored) { return ""; }
    }

    /** Maps an HTTP status + response body to a VoiceException with a user-safe Russian message. 4xx is never retried. */
    static VoiceException httpError(int code, String body, String what) {
        String b = body == null ? "" : body.toLowerCase();
        String detail = what + " HTTP " + code + (body.isEmpty() ? "" : ": " + snippet(body));
        switch (code) {
            case 401:
            case 403:
                if (code == 403 && (b.contains("subscription_required") || b.contains("feature_not_available") || b.contains("professionals")
                    || b.contains("not available on your") || b.contains("plan"))) {
                    return new VoiceException(VoiceException.Kind.UNSUPPORTED,
                        "Эта функция доступна только на платном тарифе ElevenLabs.", detail, null);
                }
                if (b.contains("country") || b.contains("region") || b.contains("geo") || b.contains("not available in your")) {
                    return new VoiceException(VoiceException.Kind.GEO_BLOCK,
                        "ElevenLabs недоступен из вашего региона.", detail, null);
                }
                return new VoiceException(VoiceException.Kind.AUTH,
                    code == 401 ? "Ключ API ElevenLabs отклонён: проверьте его в настройках." : "Доступ запрещён ElevenLabs: проверьте ключ и его права.",
                    detail, null);
            case 402:
                return new VoiceException(VoiceException.Kind.QUOTA,
                    "Закончились символы или средства на ElevenLabs.", detail, null);
            case 429:
                return new VoiceException(VoiceException.Kind.RATE_LIMIT,
                    "Слишком много запросов к ElevenLabs, подождите минуту.", detail, null);
            case 400:
            case 422:
                return new VoiceException(VoiceException.Kind.CONFIG,
                    "ElevenLabs отклонил запрос: проверьте модель и настройки голоса.", detail, null);
            default:
                if (code >= 500) return new VoiceException(VoiceException.Kind.SERVER,
                    "Сервис ElevenLabs временно недоступен.", detail, null);
                return new VoiceException(VoiceException.Kind.PROTOCOL, "Сервис ElevenLabs ответил ошибкой.", detail, null);
        }
    }

    private static VoiceException network(IOException e, String what) {
        return new VoiceException(VoiceException.Kind.NETWORK, "Нет связи с ElevenLabs.", what + ": " + e, e);
    }

    private static String snippet(String s) {
        s = Redactor.redact(s);
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static String extOf(String mime) {
        if (mime == null) return "wav";
        if (mime.contains("mpeg") || mime.contains("mp3")) return "mp3";
        if (mime.contains("flac")) return "flac";
        return "wav";
    }

    private static String joinNonEmpty(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) if (p != null && !p.isEmpty()) { if (sb.length() > 0) sb.append(sep); sb.append(p); }
        return sb.toString();
    }
}
