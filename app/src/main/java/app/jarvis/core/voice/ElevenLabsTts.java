package app.jarvis.core.voice;

import app.jarvis.core.util.CancelToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * ElevenLabs text-to-speech (official API, free tier: 10 000 chars/month).
 * Streams raw PCM (default 24 kHz s16le) or MP3 into the sink. Config keys: see {@link ElevenLabsApi}.
 * Cloning/Voice Design exist in the API but require a paid plan — see {@link ElevenLabsApi#designVoice}/{@link #api()}.
 * Models v3/v4 accept English emotion tags ({@code [whispers]}) — {@link ElevenLabsApi#supportsTags}.
 */
public final class ElevenLabsTts implements TtsProvider {
    public static final String ID = "elevenlabs";
    private static final String HOME = "https://elevenlabs.io";

    private final ElevenLabsApi api = new ElevenLabsApi();
    private volatile ProviderStatus status = ProviderStatus.NEVER;
    private volatile List<Voice> voiceCache;

    @Override public ProviderInfo info() {
        return new ProviderInfo(ID, "ElevenLabs",
            "21 готовый голос (Sarah, George, Roger, Alice, Lily, Brian…), все с русским; качество верхнего уровня",
            true, true, "10 000 символов в месяц на free",
            "на free PCM только до 24 кГц и MP3 до 64 kbps (PCM 44,1 кГц — тариф Pro); теги эмоций [..] — модели v3/v4; Voice Design и клонирование голоса через API — платные",
            true, "клонирование по образцу и Voice Design — только платный тариф (на free API отвечает 403); бесплатный клон есть у OpenRouter",
            true, "api.elevenlabs.io", HOME);
    }

    @Override public void configure(ProviderConfig cfg) { api.configure(cfg); }

    @Override public boolean isConfigured() { return api.isConfigured(); }

    /** Shared API client (for the studio UI: design/clone/quota). Configure before use. */
    public ElevenLabsApi api() { return api; }

    @Override public List<Voice> listVoices(CancelToken cancel) throws VoiceException {
        List<Voice> cached = voiceCache;
        if (cached != null) return cached;
        List<Voice> v = api.listVoices(cancel);
        voiceCache = v;
        return v;
    }

    @Override public void synthesize(TtsRequest req, AudioSink sink, CancelToken cancel) throws VoiceException {
        if (!api.isConfigured()) throw new VoiceException(VoiceException.Kind.CONFIG, "Не задан ключ API ElevenLabs.", "no apiKey", null);
        String model = req.options.containsKey("model") ? req.options.get("model") : api.model();
        String format = req.options.containsKey("output_format") ? req.options.get("output_format") : api.outputFormat();
        String voiceId = req.voiceId.isEmpty() ? api.voice() : req.voiceId;
        long t0 = System.nanoTime();
        CountingSink counting = new CountingSink(sink);
        try {
            long first = api.ttsStream(voiceId, req.text, model, format, req.language, req.speed, counting, cancel);
            long total = (System.nanoTime() - t0) / 1_000_000L;
            status = new ProviderStatus(System.currentTimeMillis(), true, "", first, total,
                model + ", " + format + ", " + counting.bytes / 1024 + " КБ");
        } catch (VoiceException e) {
            sink.abort();
            status = new ProviderStatus(System.currentTimeMillis(), false, e.userMessage, -1,
                (System.nanoTime() - t0) / 1_000_000L, e.technical);
            throw e;
        }
    }

    @Override public ProviderStatus lastStatus() { return status; }

    @Override public ProviderStatus check(String phrase, String voiceId, CancelToken cancel) {
        TtsRequest req = new TtsRequest();
        req.text = phrase;
        req.voiceId = voiceId == null ? "" : voiceId;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        AudioSink sink = new AudioSink() {
            @Override public void begin(AudioSpec spec) throws IOException { }
            @Override public void write(byte[] data, int off, int len) throws IOException { bo.write(data, off, len); }
            @Override public void end() throws IOException { }
            @Override public void abort() { }
        };
        try {
            synthesize(req, sink, cancel);
            return status;
        } catch (VoiceException e) {
            return status;
        }
    }

    @Override public String quotaText(CancelToken cancel) {
        try {
            return api.quotaText(cancel);
        } catch (VoiceException e) {
            return null;
        }
    }

    /** Counts bytes pushed to the real sink (for status detail). */
    private static final class CountingSink implements AudioSink {
        final AudioSink delegate;
        int bytes;
        CountingSink(AudioSink d) { delegate = d; }
        @Override public void begin(AudioSpec spec) throws IOException { delegate.begin(spec); }
        @Override public void write(byte[] data, int off, int len) throws IOException { bytes += len; delegate.write(data, off, len); }
        @Override public void end() throws IOException { delegate.end(); }
        @Override public void abort() { delegate.abort(); }
    }
}
