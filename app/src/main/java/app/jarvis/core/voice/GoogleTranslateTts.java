package app.jarvis.core.voice;

import app.jarvis.core.net.Http;
import app.jarvis.core.util.CancelToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unofficial Google Translate TTS ("gtx" client): one fixed voice per language, MP3 24 kHz.
 * The service rejects texts longer than ~190 characters, so longer texts are split into chunks
 * of at most {@link #MAX_CHUNK} characters at sentence/clause/space boundaries; chunks are
 * fetched sequentially and chunk N+1 is prefetched while N is being written to the sink.
 *
 * ProviderConfig keys:
 *   "lang"    — tl parameter (default "ru");
 *   "baseUrl" — override for tests;
 *   "proxy"   — HTTP/SOCKS proxy spec for Http.parseProxy.
 *
 * Speed-up is not supported by the service: speed &lt; 1 maps to ttsspeed, speed &gt; 1 is ignored.
 */
public final class GoogleTranslateTts implements TtsProvider {
    public static final String DEFAULT_LANG = "ru";
    static final int MAX_CHUNK = 180;
    static final String BASE_URL = "https://translate.google.com";
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";

    private volatile ProviderConfig cfg = new ProviderConfig();
    private volatile ProviderStatus status = ProviderStatus.NEVER;

    @Override public ProviderInfo info() {
        return new ProviderInfo("gtts", "Google Translate (озвучка)", "по одному фиксированному голосу на язык",
            false, false, "не публикуется, без аккаунта",
            "неофициальный интерфейс Google Translate; один голос, нет ускорения, до 180 символов за запрос",
            false, "клонирование не поддерживается", false,
            "translate.google.com", "https://translate.google.com");
    }

    @Override public void configure(ProviderConfig cfg) { this.cfg = cfg; }

    @Override public boolean isConfigured() { return true; }

    @Override public ProviderStatus lastStatus() { return status; }

    @Override public String quotaText(CancelToken cancel) { return null; }

    @Override public List<Voice> listVoices(CancelToken cancel) {
        List<Voice> out = new ArrayList<>(1);
        String lang = lang();
        out.add(new Voice(lang, "Google Translate / " + lang, lang, "", "единственный встроенный голос", false));
        return out;
    }

    @Override public void synthesize(TtsRequest req, AudioSink sink, CancelToken cancel) throws VoiceException {
        String text = req.text.trim();
        if (text.isEmpty()) {
            throw new VoiceException(VoiceException.Kind.CONFIG, "Пустой текст для озвучки", "text is empty", null);
        }
        final String lang = !req.language.isEmpty() ? req.language : lang();
        List<String> chunks = splitChunks(text, MAX_CHUNK);
        long t0 = System.currentTimeMillis();
        long firstMs = -1;
        final AtomicReference<HttpURLConnection> inflight = new AtomicReference<>();
        Runnable unhook = cancel.onCancel(() -> {
            HttpURLConnection c = inflight.get();
            if (c != null) c.disconnect();
        });
        // one-chunk-ahead prefetch: chunk 0 is fetched here, the worker fetches 1..n-1
        final int workerCount = chunks.size() - 1;
        final ArrayBlockingQueue<Object> queue = workerCount > 0 ? new ArrayBlockingQueue<Object>(1) : null;
        final AtomicBoolean workerStop = new AtomicBoolean(false);
        Thread worker = null;
        try {
            byte[] cur = fetch(chunks.get(0), lang, req.speed, inflight, cancel);
            if (workerCount > 0) {
                worker = new Thread(() -> {
                    try {
                        for (int i = 1; i < chunks.size() && !workerStop.get(); i++) {
                            byte[] b = fetch(chunks.get(i), lang, req.speed, inflight, cancel);
                            // capacity-1 queue: offer blocks until the consumer takes the previous chunk,
                            // keeping exactly one chunk prefetched
                            while (!workerStop.get()) {
                                if (queue.offer(b, 500, TimeUnit.MILLISECONDS)) break;
                            }
                        }
                    } catch (VoiceException e) {
                        offerQuietly(queue, e);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable t) {
                        offerQuietly(queue, new VoiceException(VoiceException.Kind.NETWORK,
                            "Сеть недоступна", t.toString(), t));
                    }
                }, "gtts-prefetch");
                worker.setDaemon(true);
                worker.start();
            }
            sink.begin(AudioSpec.mp3());
            firstMs = System.currentTimeMillis() - t0;
            sink.write(cur, 0, cur.length);
            for (int i = 1; i < chunks.size(); i++) {
                Object o;
                while (true) {
                    cancel.throwIfCancelled();
                    o = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (o != null) break;
                }
                if (o instanceof VoiceException) throw (VoiceException) o;
                if (o instanceof Throwable) {
                    Throwable t = (Throwable) o;
                    throw new VoiceException(VoiceException.Kind.NETWORK, "Сеть недоступна", t.toString(), t);
                }
                byte[] b = (byte[]) o;
                sink.write(b, 0, b.length);
            }
            sink.end();
            status = new ProviderStatus(System.currentTimeMillis(), true, "",
                firstMs, System.currentTimeMillis() - t0, lang + ", " + chunks.size() + " фрагм.");
        } catch (CancelToken.CancelledException e) {
            status = ProviderStatus.NEVER;
            throw VoiceException.cancelled();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw VoiceException.cancelled();
        } catch (VoiceException e) {
            status = new ProviderStatus(System.currentTimeMillis(), false, e.userMessage,
                firstMs, System.currentTimeMillis() - t0, lang);
            throw e;
        } catch (IOException e) {
            if (cancel.isCancelled()) {
                status = ProviderStatus.NEVER;
                throw VoiceException.cancelled();
            }
            status = new ProviderStatus(System.currentTimeMillis(), false, "Сеть недоступна",
                firstMs, System.currentTimeMillis() - t0, lang);
            throw new VoiceException(VoiceException.Kind.NETWORK, "Сеть недоступна", e.toString(), e);
        } finally {
            workerStop.set(true);
            if (worker != null) worker.interrupt();
            unhook.run();
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

    /** The service only slows down (its ttsspeed; 0.24 is the "slow" voice): faster speeds are not sent. */
    static String ttsSpeedParam(float speed) {
        if (speed >= 0.9f) return null;
        return String.format(Locale.US, "%.2f", Math.max(0.24f, speed));
    }

    private byte[] fetch(String chunk, String lang, float speed, AtomicReference<HttpURLConnection> inflight,
                         CancelToken cancel) throws VoiceException {
        HttpURLConnection c = null;
        try {
            cancel.throwIfCancelled();
            StringBuilder url = new StringBuilder(256);
            url.append(baseUrl()).append("/translate_tts?ie=UTF-8&q=")
                .append(URLEncoder.encode(chunk, "UTF-8"))
                .append("&tl=").append(URLEncoder.encode(lang, "UTF-8"))
                .append("&client=gtx");
            String slow = ttsSpeedParam(speed);
            if (slow != null) url.append("&ttsspeed=").append(slow);
            c = Http.open(url.toString(), "GET", proxy(), 10_000, 30_000);
            c.setRequestProperty("User-Agent", UA);
            inflight.set(c);
            int code = c.getResponseCode();
            if (code == 400) {
                throw new VoiceException(VoiceException.Kind.UNSUPPORTED,
                    "Служба не приняла текст", "HTTP 400 for a " + chunk.length() + "-char chunk", null);
            }
            if (code == 429) {
                throw new VoiceException(VoiceException.Kind.RATE_LIMIT,
                    "Служба просит подождать (слишком много запросов)", "HTTP 429", null);
            }
            if (code / 100 != 2) {
                throw new VoiceException(VoiceException.Kind.SERVER,
                    "Служба озвучки недоступна", "HTTP " + code, null);
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                cancel.throwIfCancelled();
                bo.write(buf, 0, n);
                if (bo.size() > 10_000_000) {
                    throw new VoiceException(VoiceException.Kind.PROTOCOL,
                        "Служба вернула слишком много данных", ">10 MB audio", null);
                }
            }
            byte[] out = bo.toByteArray();
            if (out.length == 0) {
                throw new VoiceException(VoiceException.Kind.NO_AUDIO,
                    "Служба не вернула звук", "empty body", null);
            }
            return out;
        } catch (CancelToken.CancelledException e) {
            throw VoiceException.cancelled();
        } catch (SocketTimeoutException e) {
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw new VoiceException(VoiceException.Kind.TIMEOUT,
                "Служба озвучки не отвечает", e.toString(), e);
        } catch (IOException e) {
            if (cancel.isCancelled()) throw VoiceException.cancelled();
            throw new VoiceException(VoiceException.Kind.NETWORK,
                "Нет связи со службой озвучки", e.toString(), e);
        } finally {
            if (c != null) c.disconnect();
            inflight.compareAndSet(c, null);
        }
    }

    /** Greedy split into chunks of at most max chars; prefers sentence ends, then commas, then spaces. */
    static List<String> splitChunks(String text, int max) {
        List<String> out = new ArrayList<>();
        String rest = text.trim();
        while (rest.length() > max) {
            int end = bestCut(rest, max);
            out.add(rest.substring(0, end).trim());
            rest = rest.substring(end).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    /** Exclusive end index of the first chunk (at most max chars), keeping punctuation in the chunk. */
    private static int bestCut(String s, int max) {
        int hard = Math.min(max, s.length());
        int cut = lastAfter(s, hard, ".!?…");
        if (cut < 0) cut = lastAfter(s, hard, ",;:");
        if (cut >= 0) return cut;
        int space = s.lastIndexOf(' ', hard - 1);
        if (space > 0) return space;
        return hard;
    }

    /** Last position in s[0..limit) where a char from chars occurs; returns index just after it, or -1. */
    private static int lastAfter(String s, int limit, String chars) {
        for (int i = limit - 1; i > 0; i--) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                int end = i + 1;
                // a sentence dot followed by a closing quote/bracket still belongs to the chunk
                while (end < s.length() && "\"»)]".indexOf(s.charAt(end)) >= 0) end++;
                return end <= limit ? end : i + 1;
            }
        }
        return -1;
    }

    private String lang() { return cfg.get("lang", DEFAULT_LANG); }

    private String baseUrl() { return cfg.get("baseUrl", BASE_URL); }

    private Proxy proxy() {
        try {
            return Http.parseProxy(cfg.get("proxy"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void offerQuietly(ArrayBlockingQueue<Object> q, Object v) {
        if (q == null) return;
        try {
            while (!q.offer(v, 200, TimeUnit.MILLISECONDS)) { /* wait for the consumer */ }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
