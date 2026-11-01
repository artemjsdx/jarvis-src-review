package app.jarvis.rt;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.voice.AudioSink;
import app.jarvis.core.voice.AudioSpec;
import app.jarvis.core.voice.ProviderConfig;
import app.jarvis.core.voice.ProviderInfo;
import app.jarvis.core.voice.ProviderStatus;
import app.jarvis.core.voice.TtsProvider;
import app.jarvis.core.voice.TtsRequest;
import app.jarvis.core.voice.Voice;
import app.jarvis.core.voice.VoiceException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android's installed TextToSpeech engine. Synthesizes to a temporary WAV file, so it works with
 * the same speech playback, fallback, tests and cache as network TTS providers.
 * Whether Russian is available/offline depends on installed engine and voice data.
 */
public final class AndroidSystemTts implements TtsProvider {
    private final Context context;
    private final Object synthLock = new Object();
    private volatile TextToSpeech engine;
    private volatile CountDownLatch initDone;
    private volatile int initState = TextToSpeech.ERROR;
    private volatile ProviderStatus status = ProviderStatus.NEVER;
    private volatile File tmpDir;

    public AndroidSystemTts(Context context) { this.context = context.getApplicationContext(); }

    @Override public ProviderInfo info() {
        return new ProviderInfo("android-tts", "Android — системный голос",
            "установленные в Android голоса; доступность русского зависит от движка",
            false, false, "без API-ключа и поминутной оплаты",
            "офлайн только при наличии скачанного голосового пакета; системный движок может использовать интернет",
            false, "", true, "системный TTS-движок Android", "https://developer.android.com/reference/android/speech/tts/TextToSpeech");
    }

    @Override public void configure(ProviderConfig c) { tmpDir = c.tmpDir; }
    @Override public boolean isConfigured() { return true; }
    @Override public ProviderStatus lastStatus() { return status; }
    @Override public String quotaText(CancelToken cancel) { return "Без API-квоты; зависит от системного движка"; }

    private TextToSpeech requireEngine() throws VoiceException {
        TextToSpeech t = engine;
        if (t == null) {
            synchronized (synthLock) {
                t = engine;
                if (t == null) {
                    CountDownLatch done = new CountDownLatch(1);
                    initDone = done;
                    t = new TextToSpeech(context, value -> {
                        initState = value;
                        done.countDown();
                    });
                    engine = t;
                }
            }
        }
        try {
            CountDownLatch ready = initDone;
            if (ready != null && !ready.await(8, TimeUnit.SECONDS))
                throw new VoiceException(VoiceException.Kind.TIMEOUT,
                    "Android TTS: движок не ответил.", "init timeout", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw VoiceException.cancelled();
        }
        if (initState != TextToSpeech.SUCCESS)
            throw new VoiceException(VoiceException.Kind.CONFIG,
                "В Android не найден работающий движок озвучки.", "TTS init error", null);
        return t;
    }

    @Override public List<Voice> listVoices(CancelToken cancel) throws VoiceException {
        TextToSpeech t = requireEngine();
        Set<android.speech.tts.Voice> raw = t.getVoices();
        List<Voice> out = new ArrayList<>();
        if (raw != null) for (android.speech.tts.Voice v : raw) {
            Locale lang = v.getLocale();
            if (lang == null) continue;
            out.add(new Voice(v.getName(), lang.getDisplayName(new Locale("ru")) + " · " + v.getName(),
                lang.toLanguageTag(), "", v.isNetworkConnectionRequired() ? "нужен интернет" : "офлайн", false));
        }
        Collections.sort(out, Comparator.comparing(a -> a.language + " " + a.name));
        return out;
    }

    @Override public void synthesize(TtsRequest request, AudioSink sink, CancelToken cancel) throws VoiceException {
        if (request.text == null || request.text.trim().isEmpty())
            throw new VoiceException(VoiceException.Kind.CONFIG, "Пустой текст для озвучки.");
        long start = System.nanoTime();
        File audio = null;
        synchronized (synthLock) {
            try {
                TextToSpeech t = requireEngine();
                cancel.throwIfCancelled();
                String wanted = request.voiceId == null ? "" : request.voiceId;
                if (!wanted.isEmpty()) {
                    boolean found = false;
                    Set<android.speech.tts.Voice> voices = t.getVoices();
                    if (voices != null) for (android.speech.tts.Voice v : voices) {
                        if (v.getName().equals(wanted)) {
                            if (t.setVoice(v) == TextToSpeech.SUCCESS) found = true;
                            break;
                        }
                    }
                    if (!found) throw new VoiceException(VoiceException.Kind.CONFIG,
                        "Выбранный системный голос недоступен.", wanted, null);
                } else {
                    int result = t.setLanguage(Locale.forLanguageTag(
                        request.language == null || request.language.isEmpty() ? "ru-RU" : request.language));
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED)
                        throw new VoiceException(VoiceException.Kind.UNSUPPORTED,
                            "В Android отсутствует голос для выбранного языка.", "language", null);
                }
                t.setSpeechRate(Math.max(0.5f, Math.min(2f, request.speed)));
                File root = tmpDir != null ? tmpDir : context.getCacheDir();
                if (!root.isDirectory() && !root.mkdirs())
                    throw new IOException("Cannot create TTS cache directory");
                audio = File.createTempFile("jarvis-system-", ".wav", root);
                final CountDownLatch done = new CountDownLatch(1);
                final AtomicBoolean ok = new AtomicBoolean();
                final String utterance = UUID.randomUUID().toString();
                t.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) {
                        if (utterance.equals(id)) { ok.set(true); done.countDown(); }
                    }
                    @Override public void onError(String id) {
                        if (utterance.equals(id)) done.countDown();
                    }
                    @Override public void onError(String id, int code) {
                        if (utterance.equals(id)) done.countDown();
                    }
                });
                final TextToSpeech active = t;
                Runnable unhook = cancel.onCancel(() -> { active.stop(); done.countDown(); });
                try {
                    if (t.synthesizeToFile(request.text, null, audio, utterance) != TextToSpeech.SUCCESS)
                        throw new VoiceException(VoiceException.Kind.SERVER,
                            "Android TTS не смог начать озвучку.", "synthesizeToFile failed", null);
                    if (!done.await(60, TimeUnit.SECONDS))
                        throw new VoiceException(VoiceException.Kind.TIMEOUT,
                            "Android TTS слишком долго озвучивает.", "synthesis timeout", null);
                    cancel.throwIfCancelled();
                    if (!ok.get() || !audio.isFile() || audio.length() < 44)
                        throw new VoiceException(VoiceException.Kind.NO_AUDIO,
                            "Android TTS не вернул звук.", "no WAV generated", null);
                } finally {
                    unhook.run();
                }
                sink.begin(AudioSpec.wav());
                try (FileInputStream in = new FileInputStream(audio)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        cancel.throwIfCancelled();
                        sink.write(buf, 0, n);
                    }
                }
                sink.end();
                long ms = (System.nanoTime() - start) / 1_000_000L;
                status = new ProviderStatus(System.currentTimeMillis(), true, "", ms, ms, "системный движок Android");
            } catch (CancelToken.CancelledException e) {
                sink.abort();
                throw VoiceException.cancelled();
            } catch (VoiceException e) {
                sink.abort();
                status = new ProviderStatus(System.currentTimeMillis(), false, e.userMessage, -1, -1, e.technical);
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sink.abort();
                throw VoiceException.cancelled();
            } catch (IOException e) {
                sink.abort();
                throw new VoiceException(VoiceException.Kind.NO_AUDIO,
                    "Android TTS: ошибка обработки аудио.", e.getClass().getSimpleName(), e);
            } finally {
                if (audio != null) audio.delete();
            }
        }
    }

    @Override public ProviderStatus check(String phrase, String voice, CancelToken cancel) {
        TtsRequest req = new TtsRequest();
        req.text = phrase == null || phrase.isEmpty() ? "Привет! Я Джарвис." : phrase;
        req.voiceId = voice == null ? "" : voice;
        try {
            synthesize(req, new AudioSink() {
                @Override public void begin(AudioSpec s) { }
                @Override public void write(byte[] b, int o, int l) { }
                @Override public void end() { }
                @Override public void abort() { }
            }, cancel);
        } catch (VoiceException ignored) { }
        return status;
    }
}
