package app.jarvis.rt;

import android.os.Handler;
import android.os.Looper;
import app.jarvis.core.log.Journal;
import app.jarvis.core.trace.TraceStore;
import app.jarvis.core.trace.TurnTrace;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.J;
import app.jarvis.core.audio.PcmConvert;
import app.jarvis.core.audio.WavIo;
import app.jarvis.core.voice.AudioSink;
import app.jarvis.core.voice.AudioSpec;
import app.jarvis.core.agent.AckPolicy;
import app.jarvis.core.agent.FastPhrases;
import app.jarvis.core.voice.EdgeTts;
import app.jarvis.core.voice.Lookahead;
import app.jarvis.core.voice.SynthSpool;
import app.jarvis.core.voice.ElevenLabsApi;
import app.jarvis.core.voice.PronunciationDict;
import app.jarvis.core.voice.ProviderChain;
import app.jarvis.core.voice.SlowProvider;
import app.jarvis.core.voice.SpeechText;
import app.jarvis.core.voice.SttProvider;
import app.jarvis.core.voice.TtsCache;
import app.jarvis.core.voice.TtsProvider;
import app.jarvis.core.voice.TtsRequest;
import app.jarvis.core.voice.Voice;
import app.jarvis.core.voice.VoiceException;
import app.jarvis.data.Settings;
import app.jarvis.media.SpeechOut;
import app.jarvis.media.StreamDecoder;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Speech output orchestration: text preparation (numbers, markdown, pronunciation dictionary, emotion tags), sentence
 * chunking for a fast first sound, the provider chain with fallback, an on-disk audio cache, voice profiles (incl. cloned
 * voices) and honest measurements. Audio itself is played by {@link SpeechOut}.
 */
public final class SpeechService implements SpeechApi {
    private static final String[] WARM_PHRASES = {"Слушаю.", "Хорошо.", "Готово.", "Не получилось.", "Доброе утро!"};

    private final Jarvis j;
    private final SpeechOut out;
    private final VoiceHub hub;
    private final TtsCache cache;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger seq = new AtomicInteger();
    private final Set<CancelToken> inflight = Collections.synchronizedSet(new HashSet<CancelToken>());
    private final ExecutorService workers = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "jv-speech"); t.setDaemon(true); return t; }
    });
    private final Lookahead ahead = new Lookahead(workers);
    private final Map<String, Test> tests = new HashMap<>();
    private final Map<String, String[]> testErrors = new HashMap<>();
    private final Map<String, Long> testStart = new HashMap<>();
    private final File profilesFile;
    private final List<Profile> profiles = new ArrayList<>();
    private String activeProfileId = "";
    private volatile PronunciationDict dict = new PronunciationDict();
    private final Runnable rewarm = new Runnable() { @Override public void run() { prewarm(); } };
    private volatile long lastErrorToast;
    private volatile long lastEndNs;
    private volatile long ackFirstAudioNs;
    private volatile String ackPhrase;
    private volatile String ackId = "";
    private volatile boolean ackActive;
    private final Map<String, Long> ackDurMs = new java.util.concurrent.ConcurrentHashMap<>();
    /** Providers that just failed in a way retrying immediately would not fix (exhausted quota, bad key, rate limit, outage). */
    private final Map<String, Long> cooldown = new java.util.concurrent.ConcurrentHashMap<>();

    public SpeechService(Jarvis j, SpeechOut out, VoiceHub hub) {
        this.j = j;
        this.out = out;
        this.hub = hub;
        long mb = Math.max(8, j.settings.getInt(Settings.TTS_CACHE_MB, 64));
        this.cache = new TtsCache(new File(j.home, "tts-cache"), mb * 1024 * 1024);
        this.profilesFile = new File(j.home, "voice-profiles.json");
        loadProfiles();
        reloadDict();
        j.settings.addListener(new Settings.Listener() {
            @Override public void onSettingChanged(String key) {
                if (key.startsWith("tts.") || key.startsWith("key.")) hub.reconfigure();
                if (key.equals(Settings.TTS_PRONUNCIATION)) reloadDict();
                if (key.startsWith("tts.voice.") || key.equals("tts.chain") || key.equals(Settings.TTS_SPEED) || key.equals("ack.phrases")) {
                    main.removeCallbacks(rewarm);               // another voice = other cache keys: warm the fixed phrases again once the user stops fiddling
                    main.postDelayed(rewarm, 10_000);
                }
            }
        });
        out.setListener(new SpeechOut.Listener() {
            @Override public void onUtteranceStart(String id) { }
            @Override public void onFirstAudio(String id, long ms) {
                if (id.startsWith("u")) {
                    TurnTrace tr = TraceStore.global().current();
                    if (tr != null) {
                        tr.mark(TurnTrace.PLAY_START);
                        long prevEnd = lastEndNs;
                        if (prevEnd != 0) {   // silence between two sentences of one reply: end of the previous one -> first audible sample of this one
                            long gap = (System.nanoTime() - prevEnd) / 1_000_000L;
                            if (gap < 6000) { tr.add("tts", "gap_ms_total", gap); tr.add("tts", "gaps", 1); tr.max("tts", "gap_ms_max", gap); }
                        }
                    }
                } else if (id.startsWith("a")) {
                    TurnTrace tr = TraceStore.global().current();
                    if (tr != null) tr.mark(TurnTrace.ACK_START);
                    ackFirstAudioNs = System.nanoTime();
                }
                j.journal.log(Journal.Cat.PERFORMANCE, Journal.Level.INFO, "Озвучка: до первого звука " + ms + " мс", null, null, ms);
                final Test t; synchronized (tests) { t = tests.get(id); }
                if (t != null) { final long m = ms; main.post(new Runnable() { @Override public void run() { t.onFirstAudio(m); } }); }
            }
            @Override public void onUtteranceEnd(String id, boolean completed) {
                if (id.startsWith("u")) lastEndNs = System.nanoTime();
                if (id.startsWith("a")) {
                    if (completed && ackFirstAudioNs != 0 && ackPhrase != null) ackDurMs.put(ackPhrase, (System.nanoTime() - ackFirstAudioNs) / 1_000_000L);
                    if (id.equals(ackId)) ackActive = false;
                }
                final Test t; final String[] err; final Long t0;
                synchronized (tests) { t = tests.remove(id); err = testErrors.remove(id); t0 = testStart.remove(id); }
                if (t == null) return;
                final long total = t0 == null ? -1 : System.currentTimeMillis() - t0;
                main.post(new Runnable() {
                    @Override public void run() { if (err != null) t.onError(err[0], err[1]); else if (completed) t.onDone(total); else t.onError("Озвучка остановлена", ""); }
                });
            }
            @Override public void onError(String id, String message) {
                j.journal.log(Journal.Cat.TTS, Journal.Level.ERROR, "Воспроизведение: " + message, null, null, -1);
            }
            @Override public void onLevel(float level) { j.level(level); }
            @Override public void onSpeakingChanged(boolean speaking) {
                if (!speaking) lastEndNs = 0;
                TraceStore.global().speakingChanged(speaking);
                j.setSpeaking(speaking);
                j.duckMusic("speech", speaking);
            }
        });
    }

    // ---- Speaker ----------------------------------------------------------------------------------------------------------
    @Override public void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;
        List<TtsProvider> chain = chainFor(null);
        if (chain.isEmpty()) { complain("Озвучка не настроена: включите провайдера в настройках голоса."); return; }
        String prepared = SpeechText.forSpeech(text, opts(chain.get(0)));
        if (prepared.trim().isEmpty()) return;
        final TurnTrace trace = TraceStore.global().current();
        for (final String piece : SpeechText.splitSentences(prepared, 180)) {
            if (!SpeechText.hasSpeakable(piece)) continue;      // punctuation only: providers answer with silence or an error
            if (trace != null) { trace.mark(TurnTrace.TTS_REQUEST); trace.add("tts", "chars", piece.length()); trace.add("tts", "pieces", 1); }
            final String id = "u" + seq.incrementAndGet();
            if (j.settings.getBool("tts.lookahead", true)) {
                // synthesis of the NEXT sentence runs while this one is audible (at most one ahead); the player just replays the spool
                final Lookahead.Item item = ahead.add(new Lookahead.Synth() {
                    @Override public void run(SynthSpool spool) throws Exception { synthesizeWithChain(piece, spool, trace); }
                });
                out.speak(id, new SpeechOut.Producer() {
                    @Override public void produce(AudioSink sink) throws Exception { item.produce(sink); }
                });
            } else {
                out.speak(id, new SpeechOut.Producer() {
                    @Override public void produce(AudioSink sink) throws Exception { synthesizeWithChain(piece, sink, trace); }
                });
            }
        }
    }

    /** Opens the first provider's connection ahead of the first phrase (Edge keeps one socket; TLS+WebSocket setup costs seconds). */
    public void warmUp() {
        try {
            List<TtsProvider> chain = chainFor(null);
            if (!chain.isEmpty() && chain.get(0) instanceof EdgeTts) ((EdgeTts) chain.get(0)).prewarm();
        } catch (RuntimeException ignored) { }
    }

    @Override public void stop() {
        ahead.cancelAll();
        synchronized (inflight) { for (CancelToken t : inflight) t.cancel("stop"); inflight.clear(); }
        out.stopAll();
    }

    @Override public boolean isSpeaking() { return out.isSpeaking(); }

    private SpeechText.Opts opts(TtsProvider first) {
        SpeechText.Opts o = new SpeechText.Opts().dict(dict).codeMarker(j.settings.get("tts.codeMarker", ""));
        if (first != null && first.info().id.equals("elevenlabs")) o.keepEmotionTags(ElevenLabsApi.supportsTags(j.settings.get("tts.elevenlabs.model", ElevenLabsApi.DEFAULT_MODEL)));
        return o;
    }

    private void complain(String msg) {
        long now = System.currentTimeMillis();
        j.journal.log(Journal.Cat.TTS, Journal.Level.ERROR, msg, null, null, -1);
        if (now - lastErrorToast > 8000) { lastErrorToast = now; j.toast(msg, 2); }
    }

    /** Providers for a normal reply: the active voice profile's provider first (unless it is slow), then the user's chain. */
    private List<TtsProvider> chainFor(Profile p) {
        List<TtsProvider> all = new ArrayList<>(hub.ttsChain());
        long now = System.currentTimeMillis();
        List<TtsProvider> chain = new ArrayList<>();
        for (TtsProvider q : all) { Long until = cooldown.get(q.info().id); if (until == null || until <= now) chain.add(q); }
        if (chain.isEmpty()) chain.addAll(all);          // everybody is cooling down: try anyway rather than stay silent
        Profile prof = p != null ? p : activeProfile();
        if (prof != null) {
            TtsProvider pp = hub.ttsById(prof.providerId);
            if (pp != null && pp.isConfigured() && !(pp instanceof SlowProvider)) { chain.remove(pp); chain.add(0, pp); }
        }
        return chain;
    }

    private TtsRequest requestFor(String text, TtsProvider provider) {
        TtsRequest r = new TtsRequest();
        r.text = text;
        r.language = j.settings.get(Settings.STT_LANGUAGE, "ru");
        String pid = provider.info().id;
        r.voiceId = j.settings.get("tts.voice." + pid);
        r.speed = j.settings.getFloat(Settings.TTS_SPEED, 1f);
        if (pid.equals("elevenlabs")) {
            r.options.put("model", j.settings.get("tts.elevenlabs.model", ElevenLabsApi.DEFAULT_MODEL));
        }
        Profile prof = activeProfile();
        if (prof != null && prof.providerId.equals(pid)) {
            if (!prof.voiceId.isEmpty()) r.voiceId = prof.voiceId;
            r.speed = prof.speed > 0 ? prof.speed : r.speed;
            r.options.putAll(prof.options);
            if (!prof.refAudioPath.isEmpty()) {
                try { r.refAudioPath = j.sandbox.resolve(prof.refAudioPath).getPath(); } catch (IOException ignored) { }
                r.refText = prof.refText;
            }
        }
        return r;
    }

    private void synthesizeWithChain(final String text, final AudioSink realSink, final TurnTrace trace) throws IOException {
        synthesizeChain(chainFor(null), text, realSink, trace, null);
    }

    /** The same chain, cache and guard for any provider list; {@code tune} (nullable) overrides voice and speed of every provider in it. */
    private ProviderChain.Result synthesizeChain(final List<TtsProvider> chain, final String text, final AudioSink realSink, final TurnTrace trace,
                                                 final TtsRequest tune) throws IOException {
        if (chain.isEmpty()) throw new IOException("Озвучка не настроена");
        final CancelToken cancel = new CancelToken();
        inflight.add(cancel);
        final List<TtsProvider> wrapped = new ArrayList<>();
        if (trace != null) trace.mark(TurnTrace.TTS_SYNTH_START);
        for (final TtsProvider p : chain) wrapped.add(new CachedProvider(p, tuned(requestFor(text, p), tune), trace));
        final boolean[] wrote = {false};
        ProviderChain pc = new ProviderChain(wrapped);
        pc.setListener(new ProviderChain.Listener() {
            @Override public void onAttempt(TtsProvider p, VoiceException e) {
                long wait = e.kind == VoiceException.Kind.QUOTA ? 2 * 3600_000L : e.kind == VoiceException.Kind.AUTH ? 30 * 60_000L
                    : e.kind == VoiceException.Kind.RATE_LIMIT ? 60_000L : e.kind == VoiceException.Kind.GEO_BLOCK ? 30 * 60_000L : 30_000L;
                cooldown.put(p.info().id, System.currentTimeMillis() + wait);
                j.journal.log(Journal.Cat.TTS, Journal.Level.WARN, "Озвучка: " + p.info().name + " не сработал — " + e.userMessage, null, J.put(new JSONObject(), "technical", e.technical), -1);
            }
            @Override public void onSkipSlow(TtsProvider p) { }
        });
        long t0 = System.currentTimeMillis();
        try {
            ProviderChain.Result res = pc.synthesize(tuned(requestFor(text, chain.get(0)), tune), new ProviderChain.SinkFactory() {
                @Override public AudioSink create() throws IOException {
                    if (wrote[0]) throw new IOException("Озвучка оборвалась после начала воспроизведения");
                    return new GuardSink(realSink, wrote, trace);
                }
            }, cancel);
            if (trace != null) trace.putIfAbsent("tts", "provider", res.providerId);
            j.journal.log(Journal.Cat.TTS, Journal.Level.INFO, "Озвучено через " + res.providerId + ": " + text.length() + " симв.", null,
                J.put(J.put(new JSONObject(), "first_audio_ms", res.firstAudioMs), "provider", res.providerId), res.totalMs);
            return res;
        } catch (VoiceException e) {
            if (e.kind == VoiceException.Kind.CANCELLED) throw new IOException("stopped");
            complain("Не удалось озвучить: " + e.userMessage);
            throw new IOException(e.userMessage);
        } finally {
            inflight.remove(cancel);
        }
    }

    private static TtsRequest tuned(TtsRequest r, TtsRequest tune) {
        if (tune != null) {
            if (!tune.voiceId.isEmpty()) r.voiceId = tune.voiceId;
            if (tune.speed > 0) r.speed = tune.speed;
        }
        return r;
    }

    /** What {@link #synthesizeToFile} produced and how long each stage took (real time, measured on this call). */
    public static final class FileResult {
        public String providerId = "", cleanedText = "", path = "";
        public int chars, sampleRate, channels;
        public long bytes, durationMs, firstByteMs, totalMs;
        public boolean cacheHit;
    }

    /** Synthesis through the real chain (or one named provider, no fallback) into a WAV file; blocking, for the MCP voice tools. */
    public FileResult synthesizeToFile(String text, String providerId, File out) throws IOException {
        return synthesizeToFile(text, providerId, "", 0f, out);
    }

    /** voice/speed are applied only when a provider is named (voice ids belong to one provider); speed applies to any. */
    public FileResult synthesizeToFile(String text, String providerId, String voice, float speed, File out) throws IOException {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) throw new IOException("Пустой текст");
        boolean named = providerId != null && !providerId.isEmpty();
        TtsProvider only = named ? hub.ttsById(providerId) : null;
        if (named && only == null) throw new IOException("Провайдер не найден: " + providerId);
        if (named && !only.isConfigured()) throw new IOException("Провайдер не настроен: добавьте ключ в настройках голоса (" + providerId + ")");
        List<TtsProvider> chain = named ? Collections.singletonList(only) : chainFor(null);
        if (chain.isEmpty()) throw new IOException("Озвучка не настроена: включите провайдера в настройках голоса.");
        String cleaned = SpeechText.forSpeech(t, opts(chain.get(0)));
        TtsRequest tune = new TtsRequest();
        tune.voiceId = named && voice != null ? voice : "";
        tune.speed = speed > 0 ? speed : 0f;
        TurnTrace trace = TraceStore.global().create("mcp");
        WavFileSink sink = new WavFileSink(out);
        long t0 = System.nanoTime();
        ProviderChain.Result res;
        try {
            res = synthesizeChain(chain, cleaned, sink, trace, tune);
        } catch (IOException e) {
            sink.abort();
            throw e;
        }
        JSONObject tts = trace.toJson().optJSONObject("tts");
        FileResult r = new FileResult();
        r.providerId = res.providerId;
        r.cleanedText = cleaned;
        r.chars = cleaned.length();
        r.path = out.getPath();
        r.bytes = sink.bytes;
        r.sampleRate = sink.rate;
        r.channels = sink.channels;
        r.durationMs = sink.bytes == 0 || sink.rate == 0 ? 0 : sink.bytes / (2L * sink.channels) * 1000 / sink.rate;
        r.firstByteMs = sink.firstNs == 0 ? -1 : (sink.firstNs - sink.created) / 1_000_000L;
        r.totalMs = (System.nanoTime() - t0) / 1_000_000L;
        r.cacheHit = tts != null && tts.optBoolean("cache_hit", false);
        return r;
    }

    /** Writes one utterance as a WAV file. Compressed streams go through the player's StreamDecoder, so the file holds the PCM the speaker would play. */
    private static final class WavFileSink implements AudioSink, StreamDecoder.PcmOut {
        final File file;
        final long created = System.nanoTime();
        RandomAccessFile raf;
        StreamDecoder dec;
        int rate, channels = 1;
        long bytes, firstNs;
        private boolean done;

        WavFileSink(File file) { this.file = file; }

        @Override public void begin(AudioSpec spec) throws IOException {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Не удалось создать каталог " + parent);
            raf = new RandomAccessFile(file, "rw");
            raf.setLength(0);
            raf.write(new byte[WavIo.HEADER_BYTES]);
            if (spec.codec == AudioSpec.Codec.PCM_S16LE) { rate = spec.sampleRate; channels = Math.max(1, spec.channels); }
            else { dec = new StreamDecoder(this); dec.begin(spec); }
        }

        @Override public void write(byte[] d, int o, int l) throws IOException {
            if (firstNs == 0) firstNs = System.nanoTime();
            if (dec != null) dec.feed(d, o, l); else pcm(d, o, l);
        }

        @Override public void format(int r, int c) { rate = r; channels = Math.max(1, c); }

        @Override public void pcm(byte[] b, int off, int len) throws IOException { raf.write(b, off, len); bytes += len; }

        @Override public void end() throws IOException {
            if (raf == null) throw new IOException("Озвучка не вернула звук");
            if (dec != null) dec.end();
            raf.seek(0);
            raf.write(WavIo.header(rate > 0 ? rate : PcmConvert.STT_RATE, channels, bytes));
            close();
            done = true;
        }

        @Override public void abort() {
            if (dec != null) dec.abort();
            close();
            if (!done && file.isFile()) file.delete();
        }

        private void close() {
            if (raf != null) try { raf.close(); } catch (IOException ignored) { }
            raf = null;
        }
    }

    /** Providers that failed recently and are skipped until their time runs out: id to remaining ms. */
    public Map<String, Long> cooldownLeftMs() {
        Map<String, Long> out = new HashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : cooldown.entrySet()) if (e.getValue() > now) out.put(e.getKey(), e.getValue() - now);
        return out;
    }

    /** The on-disk audio cache, for listing and stats by the MCP tools. */
    public TtsCache ttsCache() { return cache; }

    /** Forwards to the real player sink and remembers that audio started (no provider switch after that). */
    private static final class GuardSink implements AudioSink {
        final AudioSink t; final boolean[] wrote; final TurnTrace trace;
        GuardSink(AudioSink t, boolean[] wrote, TurnTrace trace) { this.t = t; this.wrote = wrote; this.trace = trace; }
        @Override public void begin(AudioSpec s) throws IOException { t.begin(s); }
        @Override public void write(byte[] d, int o, int l) throws IOException {
            if (!wrote[0] && trace != null) trace.mark(TurnTrace.TTS_FIRST_AUDIO);
            wrote[0] = true;
            t.write(d, o, l);
        }
        @Override public void end() throws IOException { t.end(); }
        @Override public void abort() { if (!wrote[0]) return; t.abort(); }   // an attempt that produced nothing leaves the real sink reusable
    }

    /** Cache layer per provider: replay on hit, record on miss (committed only when the stream ended normally). */
    private final class CachedProvider implements TtsProvider {
        final TtsProvider inner; final TtsRequest req; final String key; final TurnTrace trace;
        CachedProvider(TtsProvider inner, TtsRequest req, TurnTrace trace) { this.inner = inner; this.req = req; this.trace = trace; this.key = TtsCache.keyFor(req, inner.info().id); }
        @Override public app.jarvis.core.voice.ProviderInfo info() { return inner.info(); }
        @Override public void configure(app.jarvis.core.voice.ProviderConfig cfg) { }
        @Override public boolean isConfigured() { return true; }
        @Override public List<Voice> listVoices(CancelToken c) throws VoiceException { return inner.listVoices(c); }
        @Override public app.jarvis.core.voice.ProviderStatus lastStatus() { return inner.lastStatus(); }
        @Override public app.jarvis.core.voice.ProviderStatus check(String p, String v, CancelToken c) { return inner.check(p, v, c); }
        @Override public String quotaText(CancelToken c) { return inner.quotaText(c); }
        @Override public void synthesize(TtsRequest r, AudioSink sink, CancelToken cancel) throws VoiceException {
            try {
                if (req.allowCache && cache.replay(key, sink)) {
                    if (trace != null) trace.putIfAbsent("tts", "cache_hit", true);
                    j.journal.log(Journal.Cat.TTS, Journal.Level.DEBUG, "Озвучка из кэша (" + inner.info().id + ")", null, null, -1);
                    return;
                }
            } catch (IOException e) { /* a broken cache entry must never block speech: fall through to the network */ }
            AudioSink target = req.allowCache ? new app.jarvis.core.voice.CachingSink(cache, key, sink) : sink;
            inner.synthesize(req, target, cancel);
        }
    }

    // ---- SpeechApi --------------------------------------------------------------------------------------------------------
    @Override public List<TtsProvider> ttsProviders() { return hub.tts; }
    @Override public TtsProvider tts(String id) { return hub.ttsById(id); }
    @Override public List<SttProvider> sttProviders() { return hub.stt; }
    @Override public SttProvider stt(String id) { return hub.get(id); }
    @Override public void reconfigure() { cooldown.clear(); hub.reconfigure(); }

    @Override public List<String> activeChain() {
        List<String> ids = new ArrayList<>();
        for (TtsProvider p : chainFor(null)) ids.add(p.info().id);
        return ids;
    }

    @Override public CancelToken speakWith(final String providerId, final TtsRequest req, final Test cb) {
        final CancelToken token = new CancelToken();
        final TtsProvider p = hub.ttsById(providerId);
        if (p == null) { main.post(new Runnable() { @Override public void run() { cb.onError("Провайдер не найден", providerId); } }); return token; }
        if (!p.isConfigured()) { main.post(new Runnable() { @Override public void run() { cb.onError("Провайдер не настроен: добавьте ключ в настройках голоса", providerId); } }); return token; }
        stop();
        final String id = "t" + seq.incrementAndGet();
        synchronized (tests) { tests.put(id, cb); testStart.put(id, System.currentTimeMillis()); }
        main.post(new Runnable() { @Override public void run() { cb.onStarted(); } });
        token.onCancel(new Runnable() { @Override public void run() { out.stopAll(); } });
        out.speak(id, new SpeechOut.Producer() {
            @Override public void produce(AudioSink sink) throws Exception {
                TtsRequest r = req;
                r.text = SpeechText.forSpeech(r.text, opts(p));
                try { p.synthesize(r, sink, token); }
                catch (VoiceException e) {
                    synchronized (tests) { testErrors.put(id, new String[]{e.userMessage, e.technical}); }
                    j.journal.w(Journal.Cat.TTS, p.info().name + ": " + e.userMessage, e, J.put(new JSONObject(), "technical", e.technical));
                    throw new IOException(e.userMessage);
                }
            }
        });
        return token;
    }

    @Override public String prepareText(String text, String providerId) {
        TtsProvider p = providerId == null ? null : hub.ttsById(providerId);
        return SpeechText.forSpeech(text, opts(p));
    }

    // ---- profiles ------------------------------------------------------------------------------------------------------------
    private synchronized void loadProfiles() {
        profiles.clear();
        if (!profilesFile.isFile()) return;
        try (FileInputStream in = new FileInputStream(profilesFile)) {
            byte[] b = new byte[(int) profilesFile.length()];
            int off = 0;
            while (off < b.length) { int n = in.read(b, off, b.length - off); if (n < 0) break; off += n; }
            JSONObject root = J.parseOrNull(new String(b, 0, off, "UTF-8"));
            if (root == null) return;
            activeProfileId = J.str(root, "active");
            JSONArray a = J.arr(root, "profiles");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Profile p = new Profile();
                p.id = J.str(o, "id"); p.name = J.str(o, "name"); p.providerId = J.str(o, "provider"); p.voiceId = J.str(o, "voice");
                p.refAudioPath = J.str(o, "ref"); p.refText = J.str(o, "refText"); p.speed = (float) o.optDouble("speed", 1.0);
                p.cloned = J.bool(o, "cloned", false); p.created = J.lng(o, "created", 0); p.sentTo = J.str(o, "sentTo");
                JSONObject op = J.obj(o, "options");
                if (op != null) { java.util.Iterator<String> it = op.keys(); while (it.hasNext()) { String k = it.next(); p.options.put(k, J.str(op, k)); } }
                profiles.add(p);
            }
        } catch (IOException ignored) { }
    }

    private synchronized void saveProfiles() {
        try {
            JSONArray a = new JSONArray();
            for (Profile p : profiles) {
                JSONObject o = new JSONObject();
                J.put(o, "id", p.id); J.put(o, "name", p.name); J.put(o, "provider", p.providerId); J.put(o, "voice", p.voiceId);
                J.put(o, "ref", p.refAudioPath); J.put(o, "refText", p.refText); J.put(o, "speed", p.speed); J.put(o, "cloned", p.cloned);
                J.put(o, "created", p.created); J.put(o, "sentTo", p.sentTo);
                JSONObject op = new JSONObject();
                for (Map.Entry<String, String> e : p.options.entrySet()) J.put(op, e.getKey(), e.getValue());
                J.put(o, "options", op);
                a.put(o);
            }
            JSONObject root = new JSONObject();
            J.put(root, "active", activeProfileId);
            J.put(root, "profiles", a);
            File tmp = new File(profilesFile.getPath() + ".tmp");
            try (FileOutputStream os = new FileOutputStream(tmp)) { os.write(root.toString().getBytes("UTF-8")); os.getFD().sync(); }
            tmp.renameTo(profilesFile);
        } catch (IOException ignored) { }
    }

    @Override public synchronized List<Profile> profiles() { return new ArrayList<>(profiles); }

    @Override public synchronized Profile activeProfile() {
        for (Profile p : profiles) if (p.id.equals(activeProfileId)) return p;
        return null;
    }

    @Override public synchronized void setActiveProfile(String id) { activeProfileId = id == null ? "" : id; saveProfiles(); }

    @Override public synchronized Profile saveProfile(Profile p) {
        if (p.id.isEmpty()) { p.id = "p" + Long.toString(System.currentTimeMillis(), 36); p.created = System.currentTimeMillis(); }
        for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).id.equals(p.id)) { profiles.set(i, p); saveProfiles(); return p; }
        profiles.add(p);
        saveProfiles();
        return p;
    }

    @Override public synchronized void deleteProfile(String id) {
        for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).id.equals(id)) { profiles.remove(i); break; }
        if (id.equals(activeProfileId)) activeProfileId = "";
        saveProfiles();
    }

    @Override public CancelToken makeClone(final String providerId, final String name, final String refAudioPath, final String refText, final String testPhrase, final Test cb) {
        final CancelToken token = new CancelToken();
        final TtsProvider p = hub.ttsById(providerId);
        if (p == null || !p.info().cloning) { main.post(new Runnable() { @Override public void run() { cb.onError("Этот провайдер не умеет клонировать голос", providerId); } }); return token; }
        if (!p.isConfigured()) { main.post(new Runnable() { @Override public void run() { cb.onError("Провайдер не настроен: добавьте ключ в настройках голоса", providerId); } }); return token; }
        main.post(new Runnable() { @Override public void run() { cb.onStarted(); } });
        workers.execute(new Runnable() {
            @Override public void run() {
                long t0 = System.currentTimeMillis();
                try {
                    File ref = j.sandbox.resolve(refAudioPath);
                    if (!ref.isFile() || ref.length() < 2000) throw new VoiceException(VoiceException.Kind.CONFIG, "Образец голоса не найден или слишком короткий");
                    Profile prof = new Profile();
                    prof.name = name; prof.providerId = providerId; prof.cloned = true; prof.refAudioPath = refAudioPath; prof.refText = refText == null ? "" : refText.trim();
                    prof.sentTo = p.info().sendsDataTo;
                    if (providerId.equals("elevenlabs")) {
                        byte[] audio = readAll(ref);
                        prof.voiceId = hub.elevenApi.cloneVoice(name, audio, app.jarvis.core.util.Mime.guess(ref.getName()), token);
                        prof.refAudioPath = ""; prof.refText = "";
                    }
                    TtsRequest req = new TtsRequest();
                    req.text = SpeechText.forSpeech(testPhrase == null || testPhrase.trim().isEmpty() ? "Привет! Это проверка моего нового голоса." : testPhrase, opts(p));
                    req.voiceId = prof.voiceId;
                    req.allowCache = false;
                    if (!prof.refAudioPath.isEmpty()) { req.refAudioPath = ref.getPath(); req.refText = prof.refText; }
                    final ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    final long[] first = {-1};
                    final long start = System.currentTimeMillis();
                    p.synthesize(req, new AudioSink() {
                        @Override public void begin(AudioSpec s) { }
                        @Override public void write(byte[] d, int o, int l) { if (first[0] < 0) first[0] = System.currentTimeMillis() - start; bo.write(d, o, l); }
                        @Override public void end() { }
                        @Override public void abort() { }
                    }, token);
                    if (bo.size() < 2000) throw new VoiceException(VoiceException.Kind.NO_AUDIO, "Провайдер не вернул звук для клона");
                    final Profile saved = saveProfile(prof);
                    j.journal.log(Journal.Cat.TTS, Journal.Level.INFO, "Клон голоса «" + name + "» создан (" + providerId + "), тест " + bo.size() + " Б", null, null, System.currentTimeMillis() - t0);
                    final long total = System.currentTimeMillis() - t0;
                    main.post(new Runnable() { @Override public void run() { cb.onFirstAudio(first[0]); cb.onDone(total); } });
                } catch (final VoiceException e) {
                    j.journal.w(Journal.Cat.TTS, "Клонирование не удалось: " + e.userMessage, e, J.put(new JSONObject(), "technical", e.technical));
                    main.post(new Runnable() { @Override public void run() { cb.onError(e.userMessage, e.technical); } });
                } catch (final Exception e) {
                    main.post(new Runnable() { @Override public void run() { cb.onError("Клонирование не удалось: " + e.getMessage(), e.toString()); } });
                }
            }
        });
        return token;
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) f.length());
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        }
    }

    // ---- cache / dictionary ---------------------------------------------------------------------------------------------------
    @Override public long cacheBytes() { return cache.totalBytes(); }

    @Override public void clearCache() {
        File[] fs = cache.dir().listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    private void reloadDict() { String js = j.settings.get(Settings.TTS_PRONUNCIATION); dict = js.isEmpty() ? new PronunciationDict() : PronunciationDict.fromJson(js); }

    @Override public String pronunciationJson() { return dict.toJson(); }

    @Override public void setPronunciationJson(String json) { j.settings.set(Settings.TTS_PRONUNCIATION, json); }

    // ---- quick acknowledgement (C-10) ---------------------------------------------------------------------------------------
    /** Plays a short phrase only if it is already in the audio cache (a synthesis here would defeat the point); otherwise warms it for next time. */
    private final AckPolicy.Player ackPlayer = new AckPolicy.Player() {
        @Override public boolean play(final String phrase) {
            List<TtsProvider> chain = chainFor(null);
            if (chain.isEmpty()) return false;
            TtsProvider first = chain.get(0);
            String prepared = SpeechText.forSpeech(phrase, opts(first));
            if (prepared.trim().isEmpty()) return false;
            final String key = TtsCache.keyFor(requestFor(prepared, first), first.info().id);
            try { if (cache.get(key) == null) { warmPhraseAsync(phrase); return false; } } catch (IOException e) { return false; }
            final String id = "a" + seq.incrementAndGet();
            ackId = id; ackPhrase = phrase; ackFirstAudioNs = 0; ackActive = true;
            out.speak(id, new SpeechOut.Producer() {
                @Override public void produce(AudioSink sink) throws Exception {
                    if (!cache.replay(key, sink)) throw new IOException("ack phrase vanished from the cache");
                }
            });
            return true;
        }
        @Override public void stop() { if (ackActive) { ackActive = false; out.stopAll(); } }
        @Override public long remainingMs() {
            if (!ackActive) return 0;
            Long d = ackDurMs.get(ackPhrase);
            long dur = d == null ? 1000 : d;
            long started = ackFirstAudioNs;
            long elapsed = started == 0 ? 0 : (System.nanoTime() - started) / 1_000_000L;
            return Math.max(1, dur - elapsed);
        }
    };

    public AckPolicy.Player ackPlayer() { return ackPlayer; }

    private void warmPhraseAsync(final String phrase) {
        workers.execute(new Runnable() { @Override public void run() { try { warmPhrase(phrase); } catch (Exception ignored) { } } });
    }

    /** Synthesises one phrase into the cache with the current primary voice if that provider is free (Edge, Google); true when it is cached afterwards. */
    private boolean warmPhrase(String phrase) throws Exception {
        List<TtsProvider> chain = chainFor(null);
        if (chain.isEmpty()) return false;
        TtsProvider first = chain.get(0);
        String id = first.info().id;
        if (!(id.equals("edge") || id.equals("gtts"))) return false;     // never spend a paid/quota provider on warm-up
        TtsRequest r = requestFor(SpeechText.forSpeech(phrase, opts(first)), first);
        String key = TtsCache.keyFor(r, id);
        if (cache.get(key) != null) return true;
        first.synthesize(r, new app.jarvis.core.voice.CachingSink(cache, key, new NullSink()), new CancelToken());
        return cache.get(key) != null;
    }

    private final java.util.concurrent.atomic.AtomicBoolean warming = new java.util.concurrent.atomic.AtomicBoolean();

    /** Phrases worth having in the cache: the old greetings, every fast confirmation and the quick acknowledgements. */
    List<String> warmList() {
        List<String> l = new ArrayList<>();
        for (String s : WARM_PHRASES) l.add(s);
        l.addAll(FastPhrases.all());
        l.addAll(AckPolicy.parsePhrases(j.settings.get("ack.phrases")));
        return new ArrayList<>(new java.util.LinkedHashSet<>(l));
    }

    /**
     * Fills the cache with the fixed confirmations and acknowledgements so they start instantly later (~35 short phrases, one every 0.8 s,
     * only while the assistant is idle). Free providers only: an ElevenLabs/paid primary is never used for this.
     */
    public void prewarm() {
        if (!j.settings.getBool("tts.prewarm", true)) return;
        if (!warming.compareAndSet(false, true)) return;
        workers.execute(new Runnable() {
            @Override public void run() {
                try {
                    List<TtsProvider> chain = chainFor(null);
                    if (chain.isEmpty()) return;
                    String id = chain.get(0).info().id;
                    if (!(id.equals("edge") || id.equals("gtts"))) return;
                    int done = 0;
                    for (String phrase : warmList()) {
                        for (int wait = 0; wait < 30 && (out.isSpeaking() || j.agent.isRunning()); wait++) Thread.sleep(2000);   // idle only
                        try { if (warmPhrase(phrase)) done++; } catch (Exception e) { return; }
                        Thread.sleep(800);
                    }
                    j.journal.log(Journal.Cat.TTS, Journal.Level.INFO, "Кэш частых фраз прогрет (" + id + "): " + done + " фраз", null, null, -1);
                } catch (InterruptedException ignored) {
                } finally {
                    warming.set(false);
                }
            }
        });
    }
}
