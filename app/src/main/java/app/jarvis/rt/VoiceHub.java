package app.jarvis.rt;

import app.jarvis.core.voice.EdgeTts;
import app.jarvis.core.voice.ElevenLabsApi;
import app.jarvis.core.voice.ElevenLabsStt;
import app.jarvis.core.voice.ElevenLabsTts;
import app.jarvis.core.voice.GoogleSpeechStt;
import app.jarvis.core.voice.GoogleTranslateTts;
import app.jarvis.core.voice.GroqStt;
import app.jarvis.core.voice.OpenRouterStt;
import app.jarvis.core.voice.OpenRouterTts;
import app.jarvis.core.voice.ProviderConfig;
import app.jarvis.core.voice.SileroSpaceTts;
import app.jarvis.core.voice.SlowProvider;
import app.jarvis.core.voice.SttProvider;
import app.jarvis.core.voice.TtsProvider;
import app.jarvis.data.Settings;
import app.jarvis.wake.LocalStt;
import app.jarvis.wake.WakePack;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * All speech providers in one place and the glue between user settings / secrets and each provider's ProviderConfig.
 * Call {@link #reconfigure()} after any key or setting changed.
 */
public final class VoiceHub implements SttHub {
    private final Jarvis j;
    public final List<TtsProvider> tts = new ArrayList<>();
    public final List<SttProvider> stt = new ArrayList<>();
    /** Direct access for ElevenLabs-only features (clone, voice changer, subscription). */
    public final ElevenLabsApi elevenApi = new ElevenLabsApi();

    public VoiceHub(Jarvis j) {
        this.j = j;
        tts.add(new EdgeTts());
        tts.add(new GoogleTranslateTts());
        tts.add(new ElevenLabsTts());
        tts.add(new OpenRouterTts());
        tts.add(new SileroSpaceTts());
        addOptional("app.jarvis.core.voice.F5SpaceTts");
        stt.add(new GoogleSpeechStt());
        stt.add(new GroqStt());
        stt.add(new ElevenLabsStt());
        stt.add(new OpenRouterStt());
        stt.add(new LocalStt());
        reconfigure();
    }

    /** Providers that may be absent in a given build are looked up by name; a missing one only shortens the list. */
    private void addOptional(String cls) {
        try { tts.add((TtsProvider) Class.forName(cls).getConstructor().newInstance()); }
        catch (Throwable t) { j.journal.w(app.jarvis.core.log.Journal.Cat.TTS, "Провайдер озвучки не подключён: " + cls, t, null); }
    }

    private ProviderConfig base() {
        ProviderConfig c = new ProviderConfig();
        c.journal = j.journal;
        c.tmpDir = j.sandbox.tmp;
        c.put("proxy", j.settings.get(Settings.LLM_PROXY));
        return c;
    }

    private String opt(String key, String def) { return j.settings.get(key, def); }

    public ProviderConfig configFor(String id) {
        ProviderConfig c = base();
        switch (id) {
            case "edge": c.put("voice", opt("tts.voice.edge", "ru-RU-SvetlanaNeural")); break;
            case "silero": c.put("speaker", opt("tts.voice.silero", "kseniya")); break;
            case "elevenlabs": case "elevenlabs-stt":
                c.put("apiKey", j.secrets.get("key.elevenlabs"));
                c.put("model", opt("tts.elevenlabs.model", "eleven_flash_v2_5"));
                c.put("voice", opt("tts.voice.elevenlabs", ElevenLabsApi.DEFAULT_VOICE));
                c.put("stability", opt("tts.elevenlabs.stability", "0.5"));
                c.put("similarity", opt("tts.elevenlabs.similarity", "0.75"));
                c.put("style", opt("tts.elevenlabs.style", "0"));
                break;
            case "openrouter-tts":
                c.put("apiKey", j.secrets.get("key.openrouter"));
                if (j.settings.has("tts.openrouter.model")) c.put("model", j.settings.get("tts.openrouter.model"));
                break;
            case "openrouter-stt":
                c.put("apiKey", j.secrets.get("key.openrouter"));
                if (j.settings.has("stt.openrouter.model")) c.put("model", j.settings.get("stt.openrouter.model"));
                break;
            case "groq-stt":
                c.put("apiKey", j.secrets.get("key.groq"));
                if (j.settings.has("stt.groq.model")) c.put("model", j.settings.get("stt.groq.model"));
                break;
            case "google-stt":
                if (j.secrets.has("key.google-stt")) c.put("key", j.secrets.get("key.google-stt"));
                break;
            case "vosk-local":
                c.put("modelDir", new File(j.ctx.getFilesDir(),
                    "models/" + WakePack.MODEL_NAME).getAbsolutePath());
                break;
            default: break;
        }
        return c;
    }

    public void reconfigure() {
        for (TtsProvider p : tts) p.configure(configFor(p.info().id));
        for (SttProvider p : stt) p.configure(configFor(sttSettingId(p)));
        elevenApi.configure(configFor("elevenlabs"));
    }

    /** ElevenLabsStt shares the "elevenlabs" id with the TTS provider; settings use the same key either way. */
    private static String sttSettingId(SttProvider p) { return p.info().id; }

    public TtsProvider ttsById(String id) { for (TtsProvider p : tts) if (p.info().id.equals(id)) return p; return null; }
    @Override public SttProvider get(String id) { for (SttProvider p : stt) if (p.info().id.equals(id)) return p; return null; }

    /** Ordered TTS ids from settings (default: edge, gtts). */
    public List<String> ttsOrder() {
        List<String> ids = j.settings.getList(Settings.TTS_CHAIN);
        if (ids.isEmpty()) { ids.add("edge"); ids.add("gtts"); }
        return ids;
    }

    /** The configured, non-slow providers in the user's order. */
    public List<TtsProvider> ttsChain() {
        List<TtsProvider> out = new ArrayList<>();
        for (String id : ttsOrder()) {
            TtsProvider p = ttsById(id);
            if (p != null && p.isConfigured() && !(p instanceof SlowProvider)) out.add(p);
        }
        return out;
    }

    @Override public List<SttProvider> chain(boolean forWake) {
        List<String> ids = new ArrayList<>();
        if (forWake && j.settings.has(Settings.WAKE_STT)) ids.addAll(j.settings.getList(Settings.WAKE_STT));
        ids.addAll(j.settings.getList(Settings.STT_CHAIN));
        if (ids.isEmpty()) {
            ids.add("google-stt");
            ids.add("groq-stt");
            ids.add("elevenlabs");
            ids.add("openrouter-stt");
            ids.add("vosk-local");
        }
        List<SttProvider> out = new ArrayList<>();
        for (String id : ids) {
            if (id.equals("elevenlabs-stt")) id = "elevenlabs";
            SttProvider p = get(id);
            if (p != null && p.isConfigured() && !out.contains(p)) out.add(p);
        }
        return out;
    }
}
