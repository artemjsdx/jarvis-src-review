package app.jarvis.core.voice;

import app.jarvis.core.net.Http;
import app.jarvis.core.net.SseReader;
import app.jarvis.core.util.CancelToken;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * F5-TTS Russian voice-cloning Space (Gradio 5) at jyuviole-e2-f5-tts-rus.hf.space — no account,
 * CPU-only. Studio-only: ~2–4 minutes per phrase (plus slow uploads on a phone), so it is meant
 * for pre-generating audio, not for interactive replies; implements {@link SlowProvider}.
 * Clones a voice from a reference sample: req.refAudioPath + req.refText (or config keys
 * "refAudio"/"refText"). Cancelling is honoured at every step.
 *
 * Gradio 5 protocol: POST /gradio_api/upload (multipart "files") → server paths;
 * POST /gradio_api/queue/join {"data":[FileData, refText, genText, lang, removeSilence,
 * crossFade, nfe, speed], "fn_index":N, "session_hash":…}; GET /gradio_api/queue/data?session_hash=…
 * (SSE) until process_completed; download the output FileData url.
 *
 * ProviderConfig keys:
 *   "refAudio"  — path to the default reference sample;
 *   "refText"   — its exact transcript;
 *   "nfe"       — NFE steps (default 32);
 *   "crossFade" — cross-fade seconds (default 0.15);
 *   "baseUrl"   — override for tests;
 *   "proxy"     — HTTP/SOCKS proxy spec.
 */
public final class F5SpaceTts implements SlowProvider {
    static final String BASE_URL = "https://jyuviole-e2-f5-tts-rus.hf.space";
    /** Any single SSE wait longer than this means the Space is stuck. */
    private static final int IDLE_TIMEOUT_MS = 60_000;
    /** Hard ceiling for one synthesis (CPU Space, can be slow when cold). */
    private static final long TOTAL_TIMEOUT_MS = 10 * 60_000L;
    private static final long MAX_REF_BYTES = 10_000_000L;

    private volatile ProviderConfig cfg = new ProviderConfig();
    private volatile int ttsFnIndex = -1;
    private volatile ProviderStatus status = ProviderStatus.NEVER;

    @Override public ProviderInfo info() {
        return new ProviderInfo("f5-ru", "F5-TTS (русский, клонирование)", "один голос = ваш образец",
            false, false, "публичный Space, без аккаунта",
            "студийный режим: ~2–4 минуты на фразу на CPU — только для предгенерации, не для диалога; "
                + "нужен образец голоса и его точный текст; неофициальный интерфейс",
            true, "по образцу 5–30 с: загрузка + генерация ~2–4 мин, бесплатно", false,
            "jyuviole-e2-f5-tts-rus.hf.space", "https://huggingface.co/spaces/jyuviole/e2-f5-tts-rus");
    }

    @Override public void configure(ProviderConfig cfg) { this.cfg = cfg; }

    @Override public boolean isConfigured() { return true; }

    @Override public ProviderStatus lastStatus() { return status; }

    @Override public String quotaText(CancelToken cancel) { return null; }

    @Override public List<Voice> listVoices(CancelToken cancel) {
        List<Voice> out = new ArrayList<>(1);
        out.add(new Voice("", "F5-TTS / клон по образцу", "ru", "",
            "голос клонируется из файла-образца (refAudio + refText)", true));
        return out;
    }

    @Override public void synthesize(TtsRequest req, AudioSink sink, CancelToken cancel) throws VoiceException {
        String genText = req.text.trim();
        if (genText.isEmpty()) {
            throw new VoiceException(VoiceException.Kind.CONFIG, "Пустой текст для озвучки", "text is empty", null);
        }
        String refPath = !req.refAudioPath.isEmpty() ? req.refAudioPath : cfg.get("refAudio");
        String refText = !req.refText.isEmpty() ? req.refText : cfg.get("refText");
        if (refPath.isEmpty()) {
            throw new VoiceException(VoiceException.Kind.CONFIG,
                "Для клонирования нужен образец голоса (файл + его точный текст)",
                "refAudioPath is empty", null);
        }
        byte[] ref = readRef(refPath);
        String lang = pickLanguage(req.language);
        long t0 = System.currentTimeMillis();
        long firstMs = -1;
        try {
            int fnIndex = ensureConfig(cancel);
            String session = sessionHash();
            String serverPath = uploadRef(ref, new File(refPath).getName(), cancel);
            joinQueue(serverPath, refText, genText, lang, req.speed, fnIndex, session, cancel);
            JSONObject file = awaitResult(session, t0, cancel);
            firstMs = System.currentTimeMillis() - t0;
            long bytes = download(file, sink, cancel);
            if (bytes == 0) {
                throw new VoiceException(VoiceException.Kind.NO_AUDIO,
                    "Space не вернул аудио", "empty download", null);
            }
            status = new ProviderStatus(System.currentTimeMillis(), true, "",
                firstMs, System.currentTimeMillis() - t0, bytes + " Б");
        } catch (VoiceException e) {
            sink.abort();
            if (e.kind != VoiceException.Kind.CANCELLED) {
                status = new ProviderStatus(System.currentTimeMillis(), false, e.userMessage,
                    firstMs, System.currentTimeMillis() - t0, "");
            }
            throw e;
        }
    }

    @Override public ProviderStatus check(String phrase, String voiceId, CancelToken cancel) {
        TtsRequest r = new TtsRequest();
        r.text = phrase == null || phrase.isEmpty() ? "Проверка связи." : phrase;
        try {
            synthesize(r, discardSink(), cancel);
        } catch (VoiceException e) {
            // status already updated
        }
        return status;
    }

    /** GET /config once: the Space must still be Gradio 5.x with the basic_tts function. */
    private int ensureConfig(CancelToken cancel) throws VoiceException {
        int idx = ttsFnIndex;
        if (idx >= 0) return idx;
        HttpURLConnection c = null;
        try {
            cancel.throwIfCancelled();
            c = Http.open(baseUrl() + "/config", "GET", proxy(), 10_000, IDLE_TIMEOUT_MS);
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                throw new VoiceException(VoiceException.Kind.SERVER,
                    "Space F5-TTS недоступен", "GET /config → HTTP " + code, null);
            }
            JSONObject config = new JSONObject(Http.readText(c.getInputStream(), 8_000_000));
            String version = config.optString("version", "");
            if (!version.startsWith("5.") && !version.startsWith("4.")) {
                throw new VoiceException(VoiceException.Kind.PROTOCOL,
                    "Space F5-TTS обновился и стал несовместимым",
                    "gradio version " + version + " (expected 4.x/5.x)", null);
            }
            int found = findBasicTts(config);
            if (found < 0) {
                throw new VoiceException(VoiceException.Kind.PROTOCOL,
                    "Space F5-TTS обновился: функция синтеза не найдена",
                    "no basic_tts dependency in /config", null);
            }
            ttsFnIndex = found;
            return found;
        } catch (VoiceException e) {
            throw e;
        } catch (SocketTimeoutException e) {
            throw new VoiceException(VoiceException.Kind.TIMEOUT,
                "Space F5-TTS не отвечает", e.toString(), e);
        } catch (Exception e) {
            throw new VoiceException(VoiceException.Kind.NETWORK,
                "Нет связи со Space F5-TTS", e.toString(), e);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** The dependency with api_name "basic_tts" (fallback: audio+2 textboxes inputs, audio output). */
    static int findBasicTts(JSONObject config) {
        JSONArray comps = config.optJSONArray("components");
        JSONArray deps = config.optJSONArray("dependencies");
        if (deps == null) return -1;
        java.util.Map<Integer, String> types = new java.util.HashMap<>();
        if (comps != null) {
            for (int i = 0; i < comps.length(); i++) {
                JSONObject comp = comps.optJSONObject(i);
                int id = comp == null ? -1 : comp.optInt("id", -1);
                if (id >= 0) types.put(id, comp.optString("type", ""));
            }
        }
        for (int i = 0; i < deps.length(); i++) {
            JSONObject dep = deps.optJSONObject(i);
            if (dep == null) continue;
            if ("basic_tts".equals(dep.optString("api_name", ""))) return i;
            String[] ins = inputTypes(dep, types);
            JSONArray outputs = dep.optJSONArray("outputs");
            boolean audioOut = false;
            if (outputs != null) {
                for (int j = 0; j < outputs.length(); j++) {
                    if ("audio".equals(types.get(outputs.optInt(j, -1)))) { audioOut = true; break; }
                }
            }
            if (audioOut && ins.length == 8 && "audio".equals(ins[0]) && "textbox".equals(ins[1])
                && "textbox".equals(ins[2]) && "dropdown".equals(ins[3]) && "checkbox".equals(ins[4])
                && "slider".equals(ins[5]) && "slider".equals(ins[6]) && "slider".equals(ins[7])) {
                return i;
            }
        }
        return -1;
    }

    private static String[] inputTypes(JSONObject dep, java.util.Map<Integer, String> types) {
        JSONArray inputs = dep.optJSONArray("inputs");
        if (inputs == null) return new String[0];
        String[] out = new String[inputs.length()];
        for (int i = 0; i < inputs.length(); i++) out[i] = types.get(inputs.optInt(i, -1));
        return out;
    }

    /** multipart/form-data upload of the reference sample; returns the server-side path. */
    private String uploadRef(byte[] ref, String fileName, CancelToken cancel) throws VoiceException {
        HttpURLConnection c = null;
        try {
            cancel.throwIfCancelled();
            String boundary = "----jarvis" + sessionHash();
            c = Http.open(baseUrl() + "/gradio_api/upload", "POST", proxy(), 10_000, 120_000);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(bodyLength(ref, fileName, boundary));
            OutputStream out = c.getOutputStream();
            writeMultipart(out, ref, fileName, boundary);
            inflightCancelHook(c, cancel);
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                throw new VoiceException(VoiceException.Kind.SERVER,
                    "Не удалось загрузить образец голоса", "POST /gradio_api/upload → HTTP " + code, null);
            }
            JSONArray arr = new JSONArray(Http.readText(c.getInputStream(), 1_000_000));
            if (arr.length() == 0) {
                throw new VoiceException(VoiceException.Kind.PROTOCOL,
                    "Не удалось загрузить образец голоса", "empty upload response", null);
            }
            return arr.getString(0);
        } catch (CancelToken.CancelledException e) {
            throw VoiceException.cancelled();
        } catch (SocketTimeoutException e) {
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw new VoiceException(VoiceException.Kind.TIMEOUT,
                "Загрузка образца голоса прервана", e.toString(), e);
        } catch (IOException e) {
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw new VoiceException(VoiceException.Kind.NETWORK,
                "Не удалось загрузить образец голоса", e.toString(), e);
        } catch (org.json.JSONException e) {
            throw new VoiceException(VoiceException.Kind.PROTOCOL,
                "Space F5-TTS отвечает в неожиданном формате", e.toString(), e);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static long bodyLength(byte[] ref, String fileName, String boundary) {
        long n = 0;
        n += ("--" + boundary + "\r\n").length();
        n += ("Content-Disposition: form-data; name=\"files\"; filename=\"" + fileName + "\"\r\n").length();
        n += "Content-Type: application/octet-stream\r\n\r\n".length();
        n += ref.length;
        n += ("\r\n--" + boundary + "--\r\n").length();
        return n;
    }

    private static void writeMultipart(OutputStream out, byte[] ref, String fileName,
                                       String boundary) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"files\"; filename=\""
            + sanitizeName(fileName) + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(ref);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String sanitizeName(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isEmpty() ? "ref.wav" : safe;
    }

    private void joinQueue(String serverPath, String refText, String genText, String lang, float speed,
                           int fnIndex, String session, CancelToken cancel) throws VoiceException {
        HttpURLConnection c = null;
        try {
            cancel.throwIfCancelled();
            JSONObject audioRef = new JSONObject();
            audioRef.put("path", serverPath);
            audioRef.put("meta", new JSONObject().put("_type", "gradio.FileData"));
            JSONArray data = new JSONArray();
            data.put(audioRef).put(refText).put(genText).put(lang)
                .put(false)
                .put(Double.parseDouble(cfg.get("crossFade", "0.15")))
                .put(Integer.parseInt(cfg.get("nfe", "32")))
                .put((double) Math.max(0.5f, Math.min(2f, speed)));
            JSONObject body = new JSONObject();
            body.put("data", data);
            body.put("fn_index", fnIndex);
            body.put("event_data", JSONObject.NULL);
            body.put("trigger_id", JSONObject.NULL);
            body.put("session_hash", session);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            c = Http.open(baseUrl() + "/gradio_api/queue/join", "POST", proxy(), 10_000, 60_000);
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
                    "Space F5-TTS не принял запрос", "POST /queue/join → HTTP " + code, null);
            }
            JSONObject resp = new JSONObject(Http.readText(c.getInputStream(), 100_000));
            if (!resp.has("event_id")) {
                throw new VoiceException(VoiceException.Kind.PROTOCOL,
                    "Space F5-TTS отвечает в неожиданном формате", "join: no event_id", null);
            }
        } catch (CancelToken.CancelledException e) {
            throw VoiceException.cancelled();
        } catch (SocketTimeoutException e) {
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw new VoiceException(VoiceException.Kind.TIMEOUT,
                "Space F5-TTS не принял запрос (таймаут)", e.toString(), e);
        } catch (IOException e) {
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw new VoiceException(VoiceException.Kind.NETWORK,
                "Нет связи со Space F5-TTS", e.toString(), e);
        } catch (org.json.JSONException | NumberFormatException e) {
            throw new VoiceException(VoiceException.Kind.PROTOCOL,
                "Space F5-TTS отвечает в неожиданном формате", e.toString(), e);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Reads the SSE queue stream until process_completed; returns the output audio FileData. */
    private JSONObject awaitResult(String session, final long t0, CancelToken cancel) throws VoiceException {
        HttpURLConnection c = null;
        try {
            cancel.throwIfCancelled();
            c = Http.open(baseUrl() + "/gradio_api/queue/data?session_hash=" + session, "GET",
                proxy(), 10_000, IDLE_TIMEOUT_MS);
            inflightCancelHook(c, cancel);
            final JSONObject[] done = { null };
            final String[] failure = { null };
            InputStream in = c.getInputStream();
            SseReader.read(in, (event, data) -> {
                cancel.throwIfCancelled();
                if (System.currentTimeMillis() - t0 > TOTAL_TIMEOUT_MS) {
                    failure[0] = "timeout";
                    return false;
                }
                JSONObject msg;
                try {
                    msg = new JSONObject(data);
                } catch (org.json.JSONException e) {
                    return true; // keep-alive or unknown payload
                }
                String type = msg.optString("msg", event == null ? "" : event);
                if ("process_completed".equals(type) || msg.has("output") && msg.optBoolean("success", false)) {
                    done[0] = msg;
                    return false;
                }
                if ("process_error".equals(type) || "error".equals(type)) {
                    String m = msg.optString("message", msg.optString("error", "space error"));
                    failure[0] = m;
                    return false;
                }
                return true; // process_starts, process_generating, heartbeat, log, ...
            });
            if (failure[0] != null) {
                if ("timeout".equals(failure[0])) {
                    throw new VoiceException(VoiceException.Kind.TIMEOUT,
                        "Space F5-TTS не успел за 10 минут — попробуйте позже",
                        "total timeout", null);
                }
                throw new VoiceException(VoiceException.Kind.SERVER,
                    "Space F5-TTS вернул ошибку", failure[0], null);
            }
            if (done[0] == null) {
                throw new VoiceException(VoiceException.Kind.PROTOCOL,
                    "Соединение с очередью Space оборвалось", "stream ended before process_completed", null);
            }
            JSONObject output = done[0].optJSONObject("output");
            JSONArray outData = output == null ? null : output.optJSONArray("data");
            if (done[0].has("output") && !done[0].optBoolean("success", false)) {
                String err = output == null ? "success=false" : String.valueOf(output.opt("error"));
                throw new VoiceException(VoiceException.Kind.SERVER,
                    "Space F5-TTS не смог синтезировать", err, null);
            }
            if (outData == null || outData.length() == 0 || outData.optJSONObject(0) == null) {
                throw new VoiceException(VoiceException.Kind.PROTOCOL,
                    "Space F5-TTS не вернул аудио",
                    "output.data[0] missing: " + (outData == null ? "null" : outData.toString()), null);
            }
            return outData.getJSONObject(0);
        } catch (CancelToken.CancelledException e) {
            throw VoiceException.cancelled();
        } catch (SocketTimeoutException e) {
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw new VoiceException(VoiceException.Kind.TIMEOUT,
                "Очередь Space F5-TTS молчит >60 с", e.toString(), e);
        } catch (IOException e) {
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw new VoiceException(VoiceException.Kind.NETWORK,
                "Соединение с очередью Space оборвалось", e.toString(), e);
        } catch (VoiceException e) {
            throw e;
        } catch (Exception e) {
            throw new VoiceException(VoiceException.Kind.PROTOCOL,
                "Space F5-TTS отвечает в неожиданном формате", e.toString(), e);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Downloads the result file (FileData url, or /gradio_api/file=<path>) into the sink. */
    private long download(JSONObject file, AudioSink sink, CancelToken cancel) throws VoiceException {
        String url = file.optString("url", "");
        if (url.isEmpty()) {
            String path = file.optString("path", "");
            if (path.isEmpty()) {
                throw new VoiceException(VoiceException.Kind.PROTOCOL,
                    "Space F5-TTS не вернул файл", "no url/path in FileData", null);
            }
            url = baseUrl() + "/gradio_api/file=" + path;
        }
        HttpURLConnection c = null;
        try {
            cancel.throwIfCancelled();
            c = Http.open(url, "GET", proxy(), 10_000, 120_000);
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                throw new VoiceException(VoiceException.Kind.SERVER,
                    "Не удалось скачать результат Space", "GET result → HTTP " + code, null);
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
                "Загрузка результата Space прервана", e.toString(), e);
        } catch (IOException e) {
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw new VoiceException(VoiceException.Kind.NETWORK,
                "Не удалось скачать результат Space", e.toString(), e);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readRef(String path) throws VoiceException {
        File f = new File(path);
        if (!f.isFile()) {
            throw new VoiceException(VoiceException.Kind.CONFIG,
                "Файл образца голоса не найден", path, null);
        }
        if (f.length() > MAX_REF_BYTES) {
            throw new VoiceException(VoiceException.Kind.CONFIG,
                "Образец голоса слишком большой (нужно ≤10 МБ, лучше 5–30 секунд)",
                path + " is " + f.length() + " bytes", null);
        }
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toByteArray();
            } finally {
                in.close();
            }
        } catch (IOException e) {
            throw new VoiceException(VoiceException.Kind.CONFIG,
                "Не удалось прочитать образец голоса", e.toString(), e);
        }
    }

    /** The Space understands ru/en/zh; anything else falls back to ru. */
    private static String pickLanguage(String language) {
        return "en".equals(language) || "zh".equals(language) ? language : "ru";
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
