package app.jarvis.core.voice;

import app.jarvis.core.net.Http;
import app.jarvis.core.util.CancelToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Public Silero TTS demo Space (Gradio 3.39) at neurosenko-tts-silero.hf.space — no account.
 * Non-queue protocol: POST /run/predict {"data":[text, mode, speaker], "fn_index":N, ...} →
 * {"data":[{"name":"/tmp/gradio/…/x.wav","is_file":true}]} → GET /file=<name> (WAV PCM16 48 kHz).
 * The Space may be asleep; any single operation taking over 60 s fails with a clear message.
 *
 * ProviderConfig keys:
 *   "speaker" — aidar | baya | kseniya | xenia | eugene | random (default kseniya);
 *   "mode"    — "Common" | "SSML" (default "Common"; SSML allows Silero "+" stress marks);
 *   "baseUrl" — override for tests;
 *   "proxy"   — HTTP/SOCKS proxy spec.
 */
public final class SileroSpaceTts implements TtsProvider {
    public static final String DEFAULT_SPEAKER = "kseniya";
    public static final List<String> SPEAKERS =
        Arrays.asList("aidar", "baya", "kseniya", "xenia", "eugene", "random");
    static final String BASE_URL = "https://neurosenko-tts-silero.hf.space";
    private static final int TIMEOUT_MS = 60_000;

    private volatile ProviderConfig cfg = new ProviderConfig();
    /** Verified once per instance: [versionOk checked, fn_index]. */
    private volatile int ttsFnIndex = -1;
    private volatile ProviderStatus status = ProviderStatus.NEVER;

    @Override public ProviderInfo info() {
        return new ProviderInfo("silero", "Silero (демо Space)", "aidar, baya, kseniya, xenia, eugene",
            false, false, "публичное демо, без аккаунта",
            "неофициальный доступ к демо-стенду Silero; общедоступный Space бывает занят или «холодным» (>60 с = ошибка); "
                + "ударение можно ставить плюсом перед гласной (режим SSML)",
            false, "клонирование не поддерживается", false,
            "neurosenko-tts-silero.hf.space", "https://huggingface.co/spaces/neurosenko/tts-silero");
    }

    @Override public void configure(ProviderConfig cfg) { this.cfg = cfg; }

    @Override public boolean isConfigured() { return true; }

    @Override public ProviderStatus lastStatus() { return status; }

    @Override public String quotaText(CancelToken cancel) { return null; }

    @Override public List<Voice> listVoices(CancelToken cancel) {
        List<Voice> out = new ArrayList<>();
        for (String s : SPEAKERS) {
            String gender = s.equals("aidar") || s.equals("eugene") ? "male"
                : s.equals("random") ? "" : "female";
            out.add(new Voice(s, "Silero / " + s, "ru", gender, "голос демо-модели Silero", false));
        }
        return out;
    }

    @Override public void synthesize(TtsRequest req, AudioSink sink, CancelToken cancel) throws VoiceException {
        String text = req.text.trim();
        if (text.isEmpty()) {
            throw new VoiceException(VoiceException.Kind.CONFIG, "Пустой текст для озвучки", "text is empty", null);
        }
        String speaker = SPEAKERS.contains(req.voiceId) ? req.voiceId
            : (req.voiceId.isEmpty() ? cfg.get("speaker", DEFAULT_SPEAKER) : req.voiceId);
        String mode = cfg.get("mode", "Common");
        long t0 = System.currentTimeMillis();
        long firstMs = -1;
        try {
            int fnIndex = ensureConfig(cancel);
            JSONObject resp = postPredict(text, mode, speaker, fnIndex, cancel);
            if (resp.has("error")) {
                String err = String.valueOf(resp.opt("error"));
                throw new VoiceException(VoiceException.Kind.SERVER,
                    "Демо Silero вернуло ошибку", err, null);
            }
            JSONArray data = resp.optJSONArray("data");
            if (data == null || data.length() == 0 || !data.getJSONObject(0).optBoolean("is_file", false)) {
                throw new VoiceException(VoiceException.Kind.PROTOCOL,
                    "Демо Silero отвечает в неожиданном формате",
                    "data[0] is not a file: " + (data == null ? "null" : data), null);
            }
            String name = data.getJSONObject(0).optString("name", "");
            if (name.isEmpty()) {
                throw new VoiceException(VoiceException.Kind.PROTOCOL,
                    "Демо Silero не вернуло файл", "empty name", null);
            }
            firstMs = System.currentTimeMillis() - t0;
            long bytes = download(name, sink, cancel);
            if (bytes == 0) {
                throw new VoiceException(VoiceException.Kind.NO_AUDIO,
                    "Демо Silero вернуло пустой файл", name, null);
            }
            status = new ProviderStatus(System.currentTimeMillis(), true, "",
                firstMs, System.currentTimeMillis() - t0, speaker + ", " + bytes + " Б");
        } catch (VoiceException e) {
            sink.abort();
            if (e.kind != VoiceException.Kind.CANCELLED) {
                status = new ProviderStatus(System.currentTimeMillis(), false, e.userMessage,
                    firstMs, System.currentTimeMillis() - t0, speaker);
            }
            throw e;
        } catch (org.json.JSONException e) {
            sink.abort();
            throw new VoiceException(VoiceException.Kind.PROTOCOL,
                "Демо Silero отвечает в неожиданном формате", e.toString(), e);
        }
    }

    @Override public ProviderStatus check(String phrase, String voiceId, CancelToken cancel) {
        TtsRequest r = new TtsRequest();
        r.text = phrase == null || phrase.isEmpty() ? "Проверка связи." : phrase;
        r.voiceId = voiceId == null ? "" : voiceId;
        try {
            synthesize(r, discardSink(), cancel);
        } catch (VoiceException e) {
            // status already updated
        }
        return status;
    }

    /** GET /config once: the Space must still be Gradio 3.x with an audio-output TTS function. */
    private int ensureConfig(CancelToken cancel) throws VoiceException {
        int idx = ttsFnIndex;
        if (idx >= 0) return idx;
        HttpURLConnection c = null;
        try {
            cancel.throwIfCancelled();
            c = Http.open(baseUrl() + "/config", "GET", proxy(), 10_000, TIMEOUT_MS);
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                throw new VoiceException(VoiceException.Kind.SERVER,
                    "Демо Silero недоступно", "GET /config → HTTP " + code, null);
            }
            JSONObject config = new JSONObject(Http.readText(c.getInputStream(), 2_000_000));
            String version = config.optString("version", "");
            if (!version.startsWith("3.")) {
                throw new VoiceException(VoiceException.Kind.PROTOCOL,
                    "Демо Silero обновилось и стало несовместимым",
                    "gradio version " + version + " (expected 3.x)", null);
            }
            int found = findAudioDep(config);
            if (found < 0) {
                throw new VoiceException(VoiceException.Kind.PROTOCOL,
                    "Демо Silero обновилось: функция синтеза не найдена",
                    "no dependency with an audio output in /config", null);
            }
            ttsFnIndex = found;
            return found;
        } catch (VoiceException e) {
            throw e;
        } catch (SocketTimeoutException e) {
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw new VoiceException(VoiceException.Kind.TIMEOUT,
                "Демо Silero не отвечает (>60 с) — Space «холодный» или занят, попробуйте ещё раз",
                e.toString(), e);
        } catch (Exception e) {
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw new VoiceException(VoiceException.Kind.NETWORK,
                "Нет связи с демо Silero", e.toString(), e);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Index of the dependency whose outputs include an audio component (the TTS function). */
    static int findAudioDep(JSONObject config) {
        JSONArray comps = config.optJSONArray("components");
        JSONArray deps = config.optJSONArray("dependencies");
        if (comps == null || deps == null) return -1;
        java.util.Set<Integer> audioIds = new java.util.HashSet<>();
        for (int i = 0; i < comps.length(); i++) {
            JSONObject comp = comps.optJSONObject(i);
            int id = comp == null ? -1 : comp.optInt("id", -1);
            if (comp != null && id >= 0 && "audio".equals(comp.optString("type"))) {
                audioIds.add(id);
            }
        }
        for (int i = 0; i < deps.length(); i++) {
            JSONObject dep = deps.optJSONObject(i);
            if (dep == null) continue;
            JSONArray outputs = dep.optJSONArray("outputs");
            if (outputs == null) continue;
            for (int j = 0; j < outputs.length(); j++) {
                if (audioIds.contains(outputs.optInt(j, -1))) return i;
            }
        }
        return -1;
    }

    private JSONObject postPredict(String text, String mode, String speaker, int fnIndex,
                                   CancelToken cancel) throws VoiceException {
        HttpURLConnection c = null;
        try {
            cancel.throwIfCancelled();
            JSONObject body = new JSONObject();
            JSONArray data = new JSONArray();
            data.put(text).put(mode).put(speaker);
            body.put("data", data);
            body.put("fn_index", fnIndex);
            body.put("session_hash", sessionHash());
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            c = Http.open(baseUrl() + "/run/predict", "POST", proxy(), 10_000, TIMEOUT_MS);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Origin", baseUrl());
            c.setDoOutput(true);
            OutputStream out = c.getOutputStream();
            out.write(payload);
            out.flush();
            inflightCancelHook(c, cancel);
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                throw new VoiceException(VoiceException.Kind.SERVER,
                    "Демо Silero недоступно", "POST /run/predict → HTTP " + code, null);
            }
            return new JSONObject(Http.readText(c.getInputStream(), 2_000_000));
        } catch (CancelToken.CancelledException e) {
            throw VoiceException.cancelled();
        } catch (SocketTimeoutException e) {
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw new VoiceException(VoiceException.Kind.TIMEOUT,
                "Демо Silero не отвечает (>60 с) — Space «холодный» или занят, попробуйте ещё раз",
                e.toString(), e);
        } catch (IOException e) {
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw new VoiceException(VoiceException.Kind.NETWORK,
                "Нет связи с демо Silero", e.toString(), e);
        } catch (org.json.JSONException e) {
            throw new VoiceException(VoiceException.Kind.PROTOCOL,
                "Демо Silero отвечает в неожиданном формате", e.toString(), e);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Streams GET /file=<name> into the sink; returns the number of audio bytes written. */
    private long download(String name, AudioSink sink, CancelToken cancel) throws VoiceException {
        HttpURLConnection c = null;
        try {
            cancel.throwIfCancelled();
            c = Http.open(baseUrl() + "/file=" + name, "GET", proxy(), 10_000, TIMEOUT_MS);
            c.setRequestProperty("Referer", baseUrl() + "/");
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                throw new VoiceException(VoiceException.Kind.SERVER,
                    "Не удалось скачать аудио демо Silero", "GET /file → HTTP " + code, null);
            }
            inflightCancelHook(c, cancel);
            InputStream in = c.getInputStream();
            sink.begin(AudioSpec.wav());
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                cancel.throwIfCancelled();
                sink.write(buf, 0, n);
                total += n;
            }
            sink.end();
            return total;
        } catch (CancelToken.CancelledException e) {
            throw VoiceException.cancelled();
        } catch (SocketTimeoutException e) {
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw new VoiceException(VoiceException.Kind.TIMEOUT,
                "Загрузка аудио из демо Silero прервана (>60 с)", e.toString(), e);
        } catch (IOException e) {
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw new VoiceException(VoiceException.Kind.NETWORK,
                "Не удалось скачать аудио демо Silero", e.toString(), e);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void inflightCancelHook(final HttpURLConnection c, CancelToken cancel) {
        cancel.onCancel(() -> c.disconnect());
    }

    private static String sessionHash() {
        String abc = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(11);
        for (int i = 0; i < 11; i++) sb.append(abc.charAt(r.nextInt(abc.length())));
        return sb.toString();
    }

    private String baseUrl() { return cfg.get("baseUrl", BASE_URL); }

    private Proxy proxy() {
        try {
            return Http.parseProxy(cfg.get("proxy"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static AudioSink discardSink() {
        return new AudioSink() {
            @Override public void begin(AudioSpec spec) { }
            @Override public void write(byte[] data, int off, int len) { }
            @Override public void end() { }
            @Override public void abort() { }
        };
    }
}
