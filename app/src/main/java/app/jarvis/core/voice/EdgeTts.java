package app.jarvis.core.voice;

import app.jarvis.core.net.Http;
import app.jarvis.core.util.CancelToken;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Microsoft Edge "Read Aloud" TTS — unofficial, no account/key. WebSocket over TLS to
 * speech.platform.bing.com, auth via the Sec-MS-GEC v2 token ({@link SecMsGec}).
 *
 * ProviderConfig keys:
 *   "voice"     — default voice id ("ru-RU-SvetlanaNeural");
 *   "format"    — output format (default "audio-24khz-48kbitrate-mono-mp3", MP3 24 kHz);
 *   "proxy"     — used for the voice list only (the WebSocket layer has no proxy support);
 *   "baseUrl" / "voicesUrl" — endpoint overrides (tests).
 *
 * Connections are kept alive between phrases (the service accepts several speak requests per socket; a fresh
 * TLS+WebSocket costs 2-5 s on a slow network, a reused one 0.4-0.9 s to the first audio frame). Idle sockets are
 * dropped after {@link #IDLE_MAX_MS}: the service closes silently somewhere between 30 and 60 s of idleness.
 * Only a connection whose request ended cleanly (turn.end seen, not cancelled) goes back to the pool; a stale pooled
 * connection that fails before the first audio byte is retried once on a fresh socket.
 *
 * A 403 on the WebSocket upgrade usually means clock skew: the Date response header is used to
 * correct an offset and the connection is retried once.
 */
public final class EdgeTts implements TtsProvider {
    public static final String DEFAULT_VOICE = "ru-RU-SvetlanaNeural";
    static final String DEFAULT_FORMAT = "audio-24khz-48kbitrate-mono-mp3";
    static final String SEC_MS_GEC_VERSION = "2-143.0.3650.75";
    static final String WSS_URL =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1";
    static final String VOICES_URL =
        "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list";
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";
    private static final String ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold";

    /** A pooled socket older than this is not trusted (service-side idle close was observed between 30 and 60 s). */
    static final long IDLE_MAX_MS = 20_000;
    private static final int POOL_MAX = 2;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int REUSED_FIRST_FRAME_MS = 6_000;
    private static final java.util.concurrent.ScheduledExecutorService REAPER =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jv-edge-reaper");
            t.setDaemon(true);
            return t;
        });

    private static final class Pooled {
        final WebSocketClient ws;
        final String format;
        long idleSince;

        Pooled(WebSocketClient ws, String format) { this.ws = ws; this.format = format; }
    }

    private final java.util.ArrayDeque<Pooled> pool = new java.util.ArrayDeque<>();
    private final java.util.concurrent.atomic.AtomicInteger connects = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger reuses = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean keepAlive = true;
    private final Object prewarmLock = new Object();
    private boolean prewarmRunning;

    private volatile ProviderConfig cfg = new ProviderConfig();
    /** Server-vs-local clock offset in ms; corrected from the Date header after a 403. */
    private volatile long skewMs;
    private volatile List<Voice> voices;
    private volatile ProviderStatus status = ProviderStatus.NEVER;

    @Override public ProviderInfo info() {
        return new ProviderInfo("edge", "Edge Read Aloud", "Svetlana + ~500 голосов Microsoft, вкл. многоязычные",
            false, false, "не публикуется, без аккаунта",
            "неофициальный интерфейс Microsoft Edge; редкие голоса могут рвать соединение; ~1–4 с до первого звука",
            false, "клонирование не поддерживается", false,
            "speech.platform.bing.com", "https://www.microsoft.com/edge");
    }

    @Override public void configure(ProviderConfig cfg) {
        this.cfg = cfg;
        this.keepAlive = !"false".equals(cfg.get("keepAlive", "true"));
        closeIdle();
    }

    /** Sockets opened / pooled sockets reused since creation (for traces and tests). */
    public int connectCount() { return connects.get(); }

    public int reuseCount() { return reuses.get(); }

    /** Closes every pooled socket (settings change, app going to the background). */
    public void closeIdle() {
        List<Pooled> drop;
        synchronized (pool) { drop = new ArrayList<>(pool); pool.clear(); }
        for (Pooled p : drop) p.ws.close();
    }

    /** Opens a socket in the background so the next phrase skips TLS+WebSocket setup. No-op when one is already idle. */
    public void prewarm() {
        if (!keepAlive) return;
        synchronized (pool) { if (!pool.isEmpty()) return; }
        synchronized (prewarmLock) {
            if (prewarmRunning) return;
            prewarmRunning = true;
        }
        Thread t = new Thread(() -> {
            try {
                String format = cfg.get("format", DEFAULT_FORMAT);
                WebSocketClient ws = connect();
                connects.incrementAndGet();
                release(new Pooled(ws, format));
            } catch (Exception ignored) { /* a failed prewarm only costs the normal connect later */
            } finally {
                synchronized (prewarmLock) { prewarmRunning = false; prewarmLock.notifyAll(); }
            }
        }, "jv-edge-prewarm");
        t.setDaemon(true);
        t.start();
    }

    /** A request that arrives while a prewarm is still connecting joins it instead of opening a second socket. */
    private void awaitPrewarm(CancelToken cancel) {
        long end = System.currentTimeMillis() + 10_000;
        synchronized (prewarmLock) {
            while (prewarmRunning && !cancel.isCancelled()) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) return;
                try { prewarmLock.wait(Math.min(left, 100)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private Pooled acquire(String format) {
        long now = System.currentTimeMillis();
        List<Pooled> drop = new ArrayList<>();
        Pooled hit = null;
        synchronized (pool) {
            while (!pool.isEmpty()) {
                Pooled p = pool.pollFirst();
                if (p.ws.isClosed() || now - p.idleSince > IDLE_MAX_MS || !p.format.equals(format)) drop.add(p);
                else { hit = p; break; }
            }
        }
        for (Pooled p : drop) p.ws.close();
        return hit;
    }

    private void release(Pooled p) {
        if (!keepAlive || p.ws.isClosed()) { p.ws.close(); return; }
        p.idleSince = System.currentTimeMillis();
        Pooled evict = null;
        synchronized (pool) {
            pool.addFirst(p);
            if (pool.size() > POOL_MAX) evict = pool.pollLast();
        }
        if (evict != null) evict.ws.close();
        REAPER.schedule(() -> reap(p), IDLE_MAX_MS + 500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void reap(Pooled p) {
        boolean stale;
        synchronized (pool) { stale = pool.remove(p); }
        if (stale) p.ws.close();
    }

    @Override public boolean isConfigured() { return true; }

    @Override public ProviderStatus lastStatus() { return status; }

    @Override public String quotaText(CancelToken cancel) { return null; }

    @Override public List<Voice> listVoices(CancelToken cancel) throws VoiceException {
        List<Voice> cached = voices;
        if (cached != null) return cached;
        HttpURLConnection c = null;
        try {
            cancel.throwIfCancelled();
            c = Http.open(voicesUrl(), "GET", proxy(), 10_000, 30_000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                throw new VoiceException(VoiceException.Kind.SERVER,
                    "Список голосов Edge недоступен", "HTTP " + code + " " + voicesUrl(), null);
            }
            String body = Http.readText(c.getInputStream(), 8_000_000);
            JSONArray arr = new JSONArray(body);
            List<Voice> out = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject v = arr.getJSONObject(i);
                StringBuilder d = new StringBuilder();
                JSONObject tag = v.optJSONObject("VoiceTag");
                if (tag != null) {
                    JSONArray cat = tag.optJSONArray("ContentCategories");
                    if (cat != null) for (int j = 0; j < cat.length(); j++) {
                        if (d.length() > 0) d.append(", ");
                        d.append(cat.getString(j));
                    }
                }
                String gender = v.optString("Gender", "");
                out.add(new Voice(v.optString("ShortName", ""),
                    v.optString("FriendlyName", v.optString("Name", "")),
                    v.optString("Locale", ""), gender.isEmpty() ? "" : gender.toLowerCase(Locale.US),
                    d.toString(), false));
            }
            voices = out;
            return out;
        } catch (CancelToken.CancelledException e) {
            throw VoiceException.cancelled();
        } catch (VoiceException e) {
            throw e;
        } catch (Exception e) {
            throw new VoiceException(VoiceException.Kind.NETWORK,
                "Не удалось получить список голосов Edge", e.toString(), e);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    @Override public void synthesize(TtsRequest req, AudioSink sink, CancelToken cancel) throws VoiceException {
        if (req.text.trim().isEmpty()) {
            throw new VoiceException(VoiceException.Kind.CONFIG, "Пустой текст для озвучки", "text is empty", null);
        }
        String voice = req.voiceId.isEmpty() ? cfg.get("voice", DEFAULT_VOICE) : req.voiceId;
        String format = cfg.get("format", DEFAULT_FORMAT);
        String ssml = buildSsml(voice, req.text, req.speed);
        long t0 = System.currentTimeMillis();
        long firstMs = -1;
        try {
            firstMs = run(ssml, format, voice, sink, cancel);
            status = new ProviderStatus(System.currentTimeMillis(), true, "",
                firstMs, System.currentTimeMillis() - t0, voice);
        } catch (VoiceException e) {
            sink.abort();
            if (e.kind != VoiceException.Kind.CANCELLED) {
                status = new ProviderStatus(System.currentTimeMillis(), false, e.userMessage,
                    firstMs, System.currentTimeMillis() - t0, voice);
            }
            throw e;
        }
    }

    @Override public ProviderStatus check(String phrase, String voiceId, CancelToken cancel) {
        TtsRequest r = new TtsRequest();
        r.text = phrase == null || phrase.isEmpty() ? "Проверка связи." : phrase;
        r.voiceId = voiceId == null ? "" : voiceId;
        try {
            synthesize(r, discardSink(), cancel);
        } catch (VoiceException e) {
            // status already updated by synthesize
        }
        return status;
    }

    /** One synthesis session; returns ms to first audio byte. Retries once after a 403 clock-skew correction. */
    private long run(String ssml, String format, String voice, AudioSink sink, CancelToken cancel) throws VoiceException {
        boolean skewRetried = false, staleRetried = false;
        for (; ; ) {
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            Pooled pooled = keepAlive ? acquire(format) : null;
            if (pooled == null && keepAlive) { awaitPrewarm(cancel); pooled = acquire(format); }
            boolean reused = pooled != null;
            WebSocketClient ws;
            if (reused) {
                ws = pooled.ws;
                reuses.incrementAndGet();
            } else {
                try {
                    ws = connect();
                    connects.incrementAndGet();
                } catch (WebSocketClient.HandshakeFailure e) {
                    if (e.status == 403 && !skewRetried && adjustSkewFrom(e.headers)) { skewRetried = true; continue; }
                    VoiceException.Kind k = e.status == 401 || e.status == 403 ? VoiceException.Kind.AUTH
                        : e.status >= 500 ? VoiceException.Kind.SERVER : VoiceException.Kind.NETWORK;
                    throw new VoiceException(k, "Служба озвучки Edge отклонила подключение",
                        "handshake " + e.statusLine, e);
                } catch (SocketTimeoutException e) {
                    throw new VoiceException(VoiceException.Kind.TIMEOUT,
                        "Служба озвучки Edge не отвечает", e.toString(), e);
                } catch (IOException e) {
                    throw new VoiceException(VoiceException.Kind.NETWORK,
                        "Нет связи со службой озвучки Edge", e.toString(), e);
                }
            }
            Runnable unhook = cancel.onCancel(() -> ws.close());
            long t0 = System.currentTimeMillis(), firstMs = -1;
            boolean began = false, clean = false;
            try {
                // a pooled socket may be dead without anybody noticing: do not wait the full read timeout for its first frame
                if (reused) ws.setReadTimeout(REUSED_FIRST_FRAME_MS);
                ws.sendText(speechConfigMessage(format));
                ws.sendText(ssmlMessage(ssml));
                boolean firstFrame = true;
                while (true) {
                    cancel.throwIfCancelled();
                    WebSocketClient.Frame f = ws.receive();
                    if (firstFrame) { firstFrame = false; if (reused) ws.setReadTimeout(READ_TIMEOUT_MS); }
                    if (f.type == WebSocketClient.Type.TEXT) {
                        if (f.text().contains("Path:turn.end")) break;
                    } else if (f.type == WebSocketClient.Type.BINARY) {
                        byte[] audio = audioChunk(f.data);
                        if (audio != null && audio.length > 0) {
                            if (!began) {
                                sink.begin(AudioSpec.mp3());
                                began = true;
                                firstMs = System.currentTimeMillis() - t0;
                            }
                            sink.write(audio, 0, audio.length);
                        }
                    } else if (f.type == WebSocketClient.Type.CLOSE) {
                        throw new IOException("websocket closed by the service (code " + f.code + ")");
                    }
                }
                if (!began) {
                    clean = true;   // the socket itself is fine: the service just had nothing to say (e.g. punctuation only)
                    throw new VoiceException(VoiceException.Kind.NO_AUDIO,
                        "Служба Edge не вернула звук", "no Path:audio before turn.end; voice=" + voice, null);
                }
                sink.end();
                clean = true;
                return firstMs;
            } catch (CancelToken.CancelledException e) {
                sink.abort();
                throw VoiceException.cancelled();
            } catch (SocketTimeoutException e) {
                if (cancel.isCancelled()) {
                    sink.abort();
                    throw VoiceException.cancelled();
                }
                if (reused && !began && !staleRetried) { staleRetried = true; continue; }
                throw new VoiceException(VoiceException.Kind.TIMEOUT,
                    "Служба озвучки Edge перестала отвечать", e.toString(), e);
            } catch (IOException e) {
                if (cancel.isCancelled()) {
                    sink.abort();
                    throw VoiceException.cancelled();
                }
                if (reused && !began && !staleRetried) { staleRetried = true; continue; }   // pooled socket died while idle
                throw new VoiceException(VoiceException.Kind.NETWORK,
                    "Соединение со службой Edge оборвалось (голос " + voice + "?)", e.toString(), e);
            } finally {
                unhook.run();
                if (clean && !cancel.isCancelled()) release(reused ? pooled : new Pooled(ws, format));
                else ws.close();
            }
        }
    }

    private WebSocketClient connect() throws IOException {
        String url = wssUrl()
            + "?TrustedClientToken=" + SecMsGec.TRUSTED_CLIENT_TOKEN
            + "&ConnectionId=" + UUID.randomUUID().toString().replace("-", "")
            // the token is [0-9A-F-] by construction — always URL-safe
            + "&Sec-MS-GEC=" + SecMsGec.generate(nowSec())
            + "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VERSION;
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Pragma", "no-cache");
        h.put("Cache-Control", "no-cache");
        h.put("Origin", ORIGIN);
        h.put("User-Agent", UA);
        h.put("Accept-Encoding", "gzip, deflate, br, zstd");
        h.put("Accept-Language", "en-US,en;q=0.9");
        h.put("Cookie", "muid=" + randomHexUpper(16) + ";");
        return WebSocketClient.connect(url, h, 10_000, READ_TIMEOUT_MS, null);
    }

    private long nowSec() { return (System.currentTimeMillis() + skewMs) / 1000L; }

    /** Corrects the clock skew from a 403 response's Date header; false when the header is missing/bad. */
    private boolean adjustSkewFrom(Map<String, String> headers) {
        if (headers == null) return false;
        String date = headers.get("date");
        if (date == null || date.isEmpty()) return false;
        try {
            SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            long serverMs = f.parse(date).getTime();
            skewMs = serverMs - System.currentTimeMillis();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String speechConfigMessage(String format) {
        return "X-Timestamp:" + jsDate(System.currentTimeMillis())
            + "\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n"
            + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
            + "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},"
            + "\"outputFormat\":\"" + format + "\"}}}}\r\n";
    }

    private static String ssmlMessage(String ssml) {
        return "X-RequestId:" + UUID.randomUUID().toString().replace("-", "")
            + "\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:" + jsDate(System.currentTimeMillis())
            + "Z\r\nPath:ssml\r\n\r\n" + ssml;
    }

    /** JS-style date: "Thu Oct 28 2026 06:00:00 GMT+0000 (Coordinated Universal Time)". */
    static String jsDate(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms)) + " GMT+0000 (Coordinated Universal Time)";
    }

    /** SSML with the request speed mapped to a prosody rate percentage (speed clamped to 0.5…2.0). */
    static String buildSsml(String voice, String text, float speed) {
        float s = Math.max(0.5f, Math.min(2f, speed));
        int rate = Math.round((s - 1f) * 100f);
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
            + "<voice name='" + xmlEscape(voice) + "'>"
            + "<prosody pitch='+0Hz' rate='" + String.format(Locale.US, "%+d%%", rate) + "' volume='+0%'>"
            + xmlEscape(text)
            + "</prosody></voice></speak>";
    }

    static String xmlEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '\'': sb.append("&apos;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** MP3 payload of a binary frame: 2-byte big-endian header length + header ("Path:audio") + MP3 bytes. */
    static byte[] audioChunk(byte[] frame) {
        if (frame.length < 4) return null; // header length + at least a byte of audio
        int hl = ((frame[0] & 0xff) << 8) | (frame[1] & 0xff);
        if (frame.length <= 2 + hl) return null;
        String header = new String(frame, 2, hl, StandardCharsets.UTF_8);
        // the header line ends with CRLF: "Path:audio\r\n" — must not match "Path:audio.metadata\r\n"
        if (!header.contains("Path:audio\r\n")) return null;
        return Arrays.copyOfRange(frame, 2 + hl, frame.length);
    }

    private static String randomHexUpper(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    private String wssUrl() { return cfg.get("baseUrl", WSS_URL); }

    private String voicesUrl() {
        return cfg.get("voicesUrl", VOICES_URL) + "?trustedclienttoken=" + SecMsGec.TRUSTED_CLIENT_TOKEN
            + "&Sec-MS-GEC=" + SecMsGec.generate(nowSec())
            + "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VERSION;
    }

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
