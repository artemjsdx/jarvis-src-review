package app.jarvis.rt;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import app.jarvis.core.agent.ContextFit;
import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.llm.LlmConfig;
import app.jarvis.core.llm.LlmException;
import app.jarvis.core.llm.ModelCaps;
import app.jarvis.core.llm.ModelCatalog;
import app.jarvis.core.llm.ModelInfo;
import app.jarvis.core.llm.OpenAiClient;
import app.jarvis.core.llm.ParamMemory;
import app.jarvis.core.llm.ProviderPreset;
import app.jarvis.core.llm.Presets;
import app.jarvis.core.llm.RecentModels;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.log.Journal;
import app.jarvis.core.tools.DevReport;
import app.jarvis.core.tools.DownloadTool;
import app.jarvis.core.skills.SkillStore;
import app.jarvis.core.skills.SkillTools;
import app.jarvis.core.tools.FsTools;
import app.jarvis.core.tools.HttpFetchTool;
import app.jarvis.core.tools.MemoryStore;
import app.jarvis.core.tools.Sandbox;
import app.jarvis.core.tools.ShellSession;
import app.jarvis.core.tools.ShellTools;
import app.jarvis.core.tools.WebTools;
import app.jarvis.core.tools.TermuxTool;
import app.jarvis.core.util.J;
import app.jarvis.core.util.Redactor;
import app.jarvis.data.ChatItem;
import app.jarvis.data.ChatStore;
import app.jarvis.data.Db;
import app.jarvis.data.Secrets;
import app.jarvis.data.Settings;
import app.jarvis.data.SqlJournal;
import app.jarvis.media.MusicOut;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Application runtime: owns every long-lived component and is the single place UI, service and tools talk to.
 * Created once by JarvisApp. UI code only reads from it and calls its methods; events come back through JarvisListener
 * on the main thread.
 */
public final class Jarvis {
    private static volatile Jarvis inst;

    public static Jarvis get() { return inst; }

    public final Context ctx;
    public final File home;
    public final Handler main = new Handler(Looper.getMainLooper());
    public final Settings settings;
    public final Secrets secrets;
    public final Db db;
    public final SqlJournal journal;
    public final ChatStore chat;
    public final Sessions sessions;
    public final Sandbox sandbox;
    public final ShellSession shell;
    public final TermuxBridge termux;
    public final MemoryStore memory;
    public final SkillStore skills;
    public final ToolRegistry tools = new ToolRegistry();
    public final ParamMemory params;
    public final McpServers mcp;
    public final OpenAiClient llm;
    public final ModelCatalog catalog;
    public final AgentRunner agent;
    public final long startedAtMs = System.currentTimeMillis();

    // pluggable modules, attached by JarvisApp once constructed
    public volatile Speaker speaker = Speaker.NONE;
    public volatile VoiceControl voice = VoiceControl.NONE;
    public volatile SpeechApi speech;           // null until the voice module is attached
    public volatile ListenApi listen;           // same, microphone side (also assigned to voice)
    public volatile MusicOut music;
    public volatile app.jarvis.core.adb.AdbApi adb;   // null until attached

    /** Registers the ADB tools; called by Modules once the client exists. */
    public void attachAdb(app.jarvis.core.adb.AdbApi api) { adb = api; AdbTools.register(this, api); }
    public final String[] proxyRef = {""};

    private final CopyOnWriteArrayList<JarvisListener> listeners = new CopyOnWriteArrayList<>();
    private volatile AState agentState = AState.IDLE, voiceState = AState.IDLE, flashState;
    private volatile String agentDetail = "", voiceDetail = "", flashDetail = "";
    private volatile boolean speaking;
    private volatile String lastAction = "";
    private volatile boolean lastActionOk = true;

    private Jarvis(Context app) throws IOException {
        ctx = app;
        home = app.getFilesDir();
        settings = new Settings(new File(home, "settings.json"));
        secrets = new Secrets(new File(home, "secrets.json"));
        db = Db.open(new File(home, "jarvis.db"));
        journal = new SqlJournal(db);
        journal.setMaxRows(settings.getInt(Settings.LOG_MAX_ROWS, 20000));
        chat = new ChatStore(db);
        sessions = new Sessions(chat, settings, main::post);
        sandbox = new Sandbox(new File(home, "sandbox"));
        shell = new ShellSession(sandbox);
        termux = new TermuxBridge(app);
        memory = new MemoryStore(new File(home, "memory.json"));
        memory.setBudget(() -> settings.getInt("memory.budget", MemoryStore.DEFAULT_BUDGET));
        skills = new SkillStore(new File(sandbox.root, "skills"), journal);
        params = new ParamMemory(new File(home, "models/params.json"));
        llm = new OpenAiClient(journal, params);
        catalog = new ModelCatalog(new File(home, "models"), new ModelCatalog.Fetcher() {
            @Override public List<ModelInfo> list(String providerId) throws LlmException { return fetchModels(providerId); }
        }, journal, new java.util.function.LongSupplier() { @Override public long getAsLong() { return System.currentTimeMillis(); } },
            Executors.newFixedThreadPool(2, new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "jv-models"); t.setDaemon(true); return t; }
            }));
        catalog.setParamMemory(params);
        agent = new AgentRunner(this);
        mcp = new McpServers(this);
    }

    public static synchronized Jarvis init(Context app) throws IOException {
        if (inst != null) return inst;
        Jarvis j = new Jarvis(app.getApplicationContext());
        inst = j;
        j.afterCreate();
        return j;
    }

    private void afterCreate() {
        journal.log(Journal.Cat.SYSTEM, Journal.Level.INFO, "Запуск Jarvis " + app.jarvis.BuildConfig.VERSION_NAME + " (" + app.jarvis.BuildConfig.GIT_REV + "), Android "
            + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL + ", ABI " + Build.SUPPORTED_ABIS[0], null,
            J.put(J.put(new JSONObject(), "keystore", secrets.isEncrypted() ? "encrypted" : "PLAINTEXT: " + secrets.keystoreProblem()), "sandbox", sandbox.root.getPath()), -1);
        int stuck = chat.recoverInterrupted();
        if (stuck > 0) journal.log(Journal.Cat.AGENT, Journal.Level.WARN, "После перезапуска закрыто прерванных вызовов инструментов: " + stuck + ". Они не повторяются автоматически.", null, null, -1);
        provision();
        applySandboxPermissions();
        proxyRef[0] = settings.get(Settings.LLM_PROXY);
        HttpFetchTool.proxy = proxyRef[0];
        settings.addListener(new Settings.Listener() {
            @Override public void onSettingChanged(String key) {
                if (key.equals(Settings.LLM_PROXY)) { proxyRef[0] = settings.get(key); HttpFetchTool.proxy = proxyRef[0]; }
                if (key.equals(Settings.SANDBOX_EXTERNAL)) applySandboxPermissions();
                if (key.equals(Settings.LOG_MAX_ROWS)) journal.setMaxRows(settings.getInt(key, 20000));
                for (JarvisListener l : listeners) l.onProvidersChanged();
            }
        });
        registerCoreTools();
        mcp.load();
    }

    private void registerCoreTools() {
        FsTools.register(tools, sandbox);
        ShellTools.register(tools, shell);
        if (TermuxBridge.installed(ctx)) TermuxTool.register(tools, termux);
        HttpFetchTool.register(tools);
        DownloadTool.register(tools, sandbox, journal, proxyRef);
        DevReport.register(tools, sandbox);
        WebTools.register(tools, sandbox, settings::get, secrets::get, proxyRef);
        MemoryStore.registerTools(tools, memory);
        SkillSeeds.install(ctx.getAssets(), skills, journal);
        SkillTools.register(tools, skills);
        app.jarvis.core.chat.SearchTools.register(tools, chat::search);
        TriggerTools.register(tools, new java.io.File(home, TriggerTools.FILE));
        SystemTools.register(this);
    }

    private void applySandboxPermissions() {
        sandbox.clearExternal();
        if (settings.getBool(Settings.SANDBOX_EXTERNAL, false)) {
            File ext = android.os.Environment.getExternalStorageDirectory();
            if (ext != null) sandbox.allowExternal(ext);
        }
    }

    /**
     * Personal builds may ship assets/provisioned.json = {"secrets":{...},"settings":{...}}. It is imported once per
     * distinct content so a re-install with new keys updates them but never overwrites keys the user typed later.
     */
    private void provision() {
        try (InputStream in = ctx.getAssets().open("provisioned.json")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            String json = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            String stamp = Integer.toHexString(json.hashCode());
            if (stamp.equals(settings.get("prov.stamp"))) return;
            JSONObject o = J.parseOrNull(json);
            if (o == null) return;
            int s = 0;
            JSONObject sec = J.obj(o, "secrets");
            if (sec != null) {
                java.util.Iterator<String> it = sec.keys();
                while (it.hasNext()) { String k = it.next(); if (!secrets.has(k)) { secrets.set(k, J.str(sec, k)); s++; } }
            }
            JSONObject st = J.obj(o, "settings");
            int t = 0;
            if (st != null) {
                java.util.Iterator<String> it = st.keys();
                while (it.hasNext()) { String k = it.next(); if (!settings.has(k)) { settings.set(k, J.str(st, k)); t++; } }
            }
            settings.set("prov.stamp", stamp);
            journal.log(Journal.Cat.SYSTEM, Journal.Level.INFO, "Импортирована встроенная конфигурация: ключей " + s + ", настроек " + t, null, null, -1);
        } catch (IOException e) {
            // no provisioning asset: normal for the public build
        }
    }

    // ---- listeners / events -----------------------------------------------------------------------------------------
    public void addListener(JarvisListener l) { listeners.addIfAbsent(l); }
    public void removeListener(JarvisListener l) { listeners.remove(l); }

    private void post(final Runnable r) { if (Looper.myLooper() == Looper.getMainLooper()) r.run(); else main.post(r); }

    public void fireChat(final ChatEvent.Type t, final ChatItem it, final String stream, final String runId) {
        final ChatEvent e = new ChatEvent(t, it, stream, runId);
        post(new Runnable() { @Override public void run() { for (JarvisListener l : listeners) safe(l, e); } });
    }

    private void safe(JarvisListener l, ChatEvent e) { try { l.onChat(e); } catch (Throwable t) { journal.e(Journal.Cat.SYSTEM, "Ошибка обработчика чата", t); } }

    public void toast(final String msg, final int kind) { post(new Runnable() { @Override public void run() { for (JarvisListener l : listeners) l.onToast(msg, kind); } }); }

    public void navigate(final String target) { post(new Runnable() { @Override public void run() { for (JarvisListener l : listeners) l.onNavigate(target); } }); }

    private volatile long lastLevelPost;

    /** Loudness for the orb; callable from audio threads, coalesced to ~25 Hz, delivered on the main thread. */
    public void level(final float v) {
        long now = android.os.SystemClock.uptimeMillis();
        if (v > 0.001f && now - lastLevelPost < 40) return;
        lastLevelPost = now;
        main.post(new Runnable() { @Override public void run() { for (JarvisListener l : listeners) l.onLevel(v); } });
    }

    public void listening(final ListeningIndicator s) { post(new Runnable() { @Override public void run() { for (JarvisListener l : listeners) l.onListening(s); } }); }

    public void needPermission(final String perm) { post(new Runnable() { @Override public void run() { for (JarvisListener l : listeners) l.onPermissionNeeded(perm); } }); }

    public void permissionResult(final String perm, final boolean granted) { post(new Runnable() { @Override public void run() { for (JarvisListener l : listeners) l.onPermissionResult(perm, granted); } }); }

    public void musicChanged(final MusicOut.Info i) { post(new Runnable() { @Override public void run() { for (JarvisListener l : listeners) l.onMusic(i); } }); }

    public void runChanged(final boolean running) { post(new Runnable() { @Override public void run() { for (JarvisListener l : listeners) l.onRunChanged(running); } }); }

    public void lastAction(String text, boolean ok) {
        lastAction = text;
        lastActionOk = ok;
        final String t = text;
        final boolean k = ok;
        post(new Runnable() { @Override public void run() { for (JarvisListener l : listeners) l.onLastAction(t, k); } });
    }

    public String lastActionText() { return lastAction; }
    public boolean lastActionOk() { return lastActionOk; }

    // ---- assistant state --------------------------------------------------------------------------------------------
    public void setAgentState(AState s, String detail) { agentState = s; agentDetail = nz(detail); publishState(); }
    public void setVoiceState(AState s, String detail) { voiceState = s; voiceDetail = nz(detail); publishState(); }
    public void setSpeaking(boolean on) { speaking = on; publishState(); }

    /** Shows DONE / ERROR / CANCELLED for a moment, then falls back to the live state. */
    public void flash(final AState s, String detail) {
        flashState = s;
        flashDetail = nz(detail);
        publishState();
        main.postDelayed(new Runnable() { @Override public void run() { if (flashState == s) { flashState = null; publishState(); } } }, s == AState.ERROR ? 4500 : 2200);
    }

    public AState state() {
        if (speaking) return AState.SPEAKING;
        if (agentState == AState.THINKING || agentState == AState.TOOL) return agentState;
        if (voiceState == AState.LISTENING || voiceState == AState.RECOGNIZING) return voiceState;
        if (flashState != null) return flashState;
        if (voiceState == AState.WAITING || voice.isWakeEnabled()) return AState.WAITING;
        return AState.IDLE;
    }

    /** The detail of the state that {@link #state()} reports; a detail of another source never leaks into it. */
    public String stateDetail() {
        AState s = state();
        if (s == flashState) return flashDetail;
        if (s == agentState) return agentDetail;
        return s == voiceState ? voiceDetail : "";
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private void publishState() {
        final AState s = state();
        final String d = stateDetail();
        post(new Runnable() { @Override public void run() { for (JarvisListener l : listeners) l.onState(s, d); } });
    }

    // ---- LLM selection ----------------------------------------------------------------------------------------------
    public static final class LlmChoice {
        public ProviderPreset preset;
        public LlmConfig config;
        public String model = "";
        public boolean hasKey;
        public String label() { return preset.name + (model.isEmpty() ? "" : " · " + model); }
    }

    public String providerId() { return settings.get(Settings.LLM_PROVIDER, "inception"); }

    public String modelFor(String providerId) {
        String m = settings.get("llm.model." + providerId);
        if (m.isEmpty() && providerId.equals(providerId())) m = settings.get(Settings.LLM_MODEL);
        if (m.isEmpty()) {
            ProviderPreset p = Presets.get(providerId);
            if (p != null && p.modelHints.length > 0) m = p.modelHints[0];
        }
        return m;
    }

    public void setModel(String providerId, String model) {
        settings.set("llm.model." + providerId, model);
        if (providerId.equals(providerId())) settings.set(Settings.LLM_MODEL, model);
    }

    public LlmChoice choiceFor(String providerId, String modelOverride) {
        ProviderPreset p = Presets.get(providerId);
        if (p == null) return null;
        LlmChoice c = new LlmChoice();
        c.preset = p;
        String key = secrets.get("key." + providerId);
        c.hasKey = !key.isEmpty() || !p.keyRequired;
        c.config = p.toConfig(key);
        if (providerId.equals("custom") || settings.has("llm.baseUrl." + providerId)) c.config.baseUrl = settings.get("llm.baseUrl." + providerId, p.baseUrl);
        c.config.proxy = settings.get(Settings.LLM_PROXY);
        c.model = modelOverride != null && !modelOverride.isEmpty() ? modelOverride : modelFor(providerId);
        return c;
    }

    /** Model list of one provider. Providers that need a key are not asked without one (the answer would only be a 401). */
    private List<ModelInfo> fetchModels(String providerId) throws LlmException {
        LlmChoice ch = choiceFor(providerId, null);
        if (ch == null) throw new LlmException(LlmException.Kind.CONFIG, 0, "Провайдер не найден", "unknown provider " + providerId, 0, null, null);
        if (ch.preset.keyRequired && !ch.hasKey && !"openrouter".equals(providerId))
            throw new LlmException(LlmException.Kind.CONFIG, 0, "Нужен API-ключ, чтобы загрузить список моделей", "no key for " + providerId, 0, null, null);
        return llm.listModels(ch.config, new CancelToken());
    }

    /** What switching to this model would cost in the active conversation: the check every model change goes through. */
    public ContextFit.Result checkSwitch(String providerId, String model) {
        long conv = sessions.active();
        List<ChatMessage> history = chat.history(conv, Integer.MAX_VALUE);
        boolean images = false;
        for (ChatItem it : chat.items(conv, 0, 400))
            for (ChatItem.Attachment a : it.attachments) if (a.mime != null && a.mime.startsWith("image/")) images = true;
        return ContextFit.check(history, images, catalog.caps(providerId, model), catalog.caps(providerId(), modelFor(providerId())));
    }

    /** Makes a model the current one, moves it to the recent list and switches the provider when needed. Callers check {@link ContextFit} first. */
    public void selectModel(String providerId, String model) {
        if (!providerId.equals(providerId())) settings.set(Settings.LLM_PROVIDER, providerId);
        setModel(providerId, model);
        settings.setList(Settings.LLM_RECENT, RecentModels.push(settings.getList(Settings.LLM_RECENT), providerId, model));
    }

    /** Character budget of the conversation history for this model: automatic from its window, or the manual value. */
    public int contextChars(String providerId, String model) {
        if (!settings.getBool(Settings.LLM_CONTEXT_AUTO, true)) return Math.max(8000, settings.getInt(Settings.LLM_CONTEXT_CHARS, 60000));
        ModelCaps caps = catalog.caps(providerId, model);
        int answer = settings.getInt(Settings.LLM_MAX_TOKENS, 4000);
        return ContextFit.autoContextChars(caps.contextTokens, answer > 0 ? answer : 4000);
    }

    /** Primary provider first, then the user's fallbacks that have keys. Mercury ids are mapped to OpenRouter's naming. */
    public List<LlmChoice> llmChoices() {
        List<LlmChoice> out = new ArrayList<>();
        LlmChoice primary = choiceFor(providerId(), null);
        if (primary != null) out.add(primary);
        String fb = settings.get(Settings.LLM_FALLBACKS, "openrouter");
        for (String id : fb.split(",")) {
            id = id.trim();
            if (id.isEmpty() || id.equals(providerId())) continue;
            String model = null;
            if (id.equals("openrouter") && primary != null && primary.model.startsWith("mercury")) model = "inception/" + primary.model;
            LlmChoice c = choiceFor(id, model);
            if (c != null && c.hasKey && !c.model.isEmpty()) out.add(c);
        }
        return out;
    }

    // ---- entry points for UI / voice ------------------------------------------------------------------------------
    public void submit(String text, List<ChatItem.Attachment> attachments, String source) { agent.submit(text, attachments, source); }

    /** convId 0 = the active session. */
    public void submit(String text, List<ChatItem.Attachment> attachments, String source, long convId) { agent.submit(text, attachments, source, convId); }

    /** Starts listening inside the given session, or inside a fresh one when convId is 0. */
    public boolean startVoiceChat(long convId) {
        if (convId > 0) { if (!sessions.select(convId)) return false; }
        else sessions.newChat();
        return voice.startListening();
    }

    public void cancelAll() {
        agent.cancel();
        voice.cancel();
        speaker.stop();
    }

    public boolean isBusy() { return agent.isRunning(); }

    // ---- music ducking shared by speech output and microphone capture ---------------------------------------------------
    private final java.util.Set<String> duckOwners = new java.util.HashSet<>();
    private float duckBase = -1f;
    private volatile int duckEpoch;
    private android.animation.ValueAnimator duckFade; // accessed on the main looper only

    /**
     * Reference-counted by owner, so parallel speech/capture events cannot unduck prematurely.
     * Music playback is never paused, and on cancellation the latest target always wins.
     * Speech: tts.duck / tts.duckPercent (default 10%). Capture retains the legacy 22% reduction.
     */
    public synchronized void duckMusic(String owner, boolean on) {
        final MusicOut m = music;
        if (m == null || owner == null) return;
        if (on && "speech".equals(owner) && !settings.getBool("tts.duck", true)) return;
        try {
            if (on) {
                if (duckOwners.isEmpty()) {
                    MusicOut.Info info = m.info();
                    if (info.state != MusicOut.State.PLAYING) return;
                    duckBase = info.volume;
                }
                duckOwners.add(owner);
            } else {
                if (!duckOwners.remove(owner)) return;
            }
            if (duckBase < 0f) return;
            final boolean speechActive = duckOwners.contains("speech");
            final boolean captureActive = !duckOwners.isEmpty() && (!speechActive || duckOwners.size() > 1);
            final float goal = app.jarvis.core.audio.DuckPolicy.target(duckBase, speechActive, captureActive,
                settings.getBool("tts.duck", true), settings.getInt("tts.duckPercent", 10));
            if (duckOwners.isEmpty()) duckBase = -1f;
            final int generation = ++duckEpoch;
            main.post(new Runnable() {
                @Override public void run() {
                    if (duckEpoch != generation || music != m) return;
                    if (duckFade != null) { duckFade.cancel(); duckFade = null; }
                    final float start;
                    try { start = m.info().volume; } catch (Throwable error) { return; }
                    if (Math.abs(start - goal) < 0.001f) return;
                    final android.animation.ValueAnimator fade = android.animation.ValueAnimator.ofFloat(start, goal);
                    duckFade = fade;
                    fade.setDuration(200);
                    fade.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                        @Override public void onAnimationUpdate(android.animation.ValueAnimator animation) {
                            if (duckEpoch == generation && music == m)
                                m.setVolume((Float) animation.getAnimatedValue());
                        }
                    });
                    fade.addListener(new android.animation.AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(android.animation.Animator a) {
                            if (duckFade == fade) duckFade = null;
                        }
                    });
                    fade.start();
                }
            });
        } catch (Throwable ignored) { }
    }

    /** Streams a picked document into the sandbox inbox (call off the main thread). */
    public ChatItem.Attachment attach(Uri uri, Attachments.Progress p) throws IOException { return Attachments.importUri(ctx, sandbox, uri, p); }

    public String userCall() { return settings.get(Settings.USER_CALL); }

    public String statusText() {
        StringBuilder sb = new StringBuilder();
        AState s = state();
        sb.append("Сейчас: ").append(s.label.toLowerCase(java.util.Locale.ROOT)).append('.');
        MusicOut m = music;
        if (m != null) {
            MusicOut.Info i = m.info();
            if (i.state == MusicOut.State.PLAYING) sb.append(" Играет ").append(i.title).append('.');
            else if (i.state == MusicOut.State.PAUSED) sb.append(" Пауза: ").append(i.title).append('.');
        }
        if (agent.isRunning()) sb.append(" Выполняю задачу.");
        if (voice.isWakeEnabled()) sb.append(" Слушаю обращение «Джарвис».");
        return sb.toString();
    }

    public static String describeProvider(LlmChoice c) { return c == null ? "не выбран" : c.label(); }
}
