package app.jarvis.rt;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import app.jarvis.audio.Cues;
import app.jarvis.audio.MicArbiter;
import app.jarvis.core.audio.CueTiming;
import app.jarvis.core.audio.MicArbiterMachine;
import app.jarvis.core.audio.Segmenter;
import app.jarvis.core.audio.WakeMatcher;
import app.jarvis.core.log.Journal;
import app.jarvis.core.log.JournalAction;
import app.jarvis.core.log.WakeSummary;
import app.jarvis.core.trace.TraceStore;
import app.jarvis.core.trace.TurnTrace;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.J;
import app.jarvis.core.voice.PcmAudio;
import app.jarvis.core.voice.ProviderStatus;
import app.jarvis.core.voice.SttOptions;
import app.jarvis.core.voice.SttProvider;
import app.jarvis.audio.AudioEncoder;
import app.jarvis.core.voice.AudioPacker;
import app.jarvis.core.voice.UplinkMeter;
import app.jarvis.core.voice.SttHedge;
import app.jarvis.wake.LocalWake;
import app.jarvis.wake.WakeEngine;
import app.jarvis.wake.WakePack;
import app.jarvis.core.voice.SttResult;
import app.jarvis.core.voice.VoiceException;
import app.jarvis.data.Settings;
import app.jarvis.media.MicCapture;
import app.jarvis.media.MicIn;
import app.jarvis.media.MusicOut;
import app.jarvis.svc.JarvisService;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.IntSupplier;
import org.json.JSONObject;

/**
 * The microphone side of the assistant: push-to-talk, always-on wake word, command capture, recognition and the studio
 * helpers. One microphone stream feeds everything; speech is cut into utterances by the Segmenter (with a pre-roll, so
 * the first syllable is never lost), each utterance is recognised by the user's STT chain, and commands go to the agent.
 *
 * Wake detection here is "cloud-gated": every utterance of 0.3–8 s is transcribed and matched against the wake phrases.
 * That costs one STT request per utterance heard in the room (rate-limited below), which is why the Settings page tells the
 * user plainly what is uploaded. It never runs while the assistant itself is speaking.
 */
public final class VoiceController implements ListenApi {
    private enum Mode { OFF, WAKE, COMMAND }

    private static final int RATE = 16000;
    private final Jarvis j;
    private final Context ctx;
    private final MicIn mic;
    private final SttHub hub;
    private final AudioPacker packer;
    private final Cues cues;
    private final MicArbiter arbiter;
    private final MicArbiterMachine arbiterMachine = new MicArbiterMachine();
    private volatile long cueMuteUntil;
    private volatile boolean sttFormatLogged;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService sttExec = Executors.newFixedThreadPool(2, new ThreadFactory() {
        @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "jv-stt"); t.setDaemon(true); return t; }
    });

    private final Object lock = new Object();
    private volatile Mode mode = Mode.OFF;
    private volatile boolean wake;
    private volatile boolean micRunning;
    private volatile String problem = "";
    private volatile boolean speechSeen;
    private volatile long commandSince;
    private volatile boolean speakerBusy;
    private volatile long speakerFreeAt;
    private volatile CancelToken sttCancel;
    private WakeMatcher matcher;
    private LocalWake localWake;
    private Segmenter segmenter;
    private int endSilenceMs = 550;
    private Segmenter.Config segCfg;
    /** A command that ended mid-thought ("включи музыку и"): kept for a moment so the continuation can be joined to it. */
    private volatile String heldCmd;
    private volatile TurnTrace heldTrace;
    private static final long HOLD_MS = 1600;
    /** Turn started by a wake word without a command; the command utterance that follows continues it. */
    private volatile TurnTrace wakeTrace;
    private volatile long wakeTraceAt;
    private int restarts;
    private final long[] wakeStamps = new long[12];
    private int wakeIdx;
    private static final String WAKE_SUMMARY_KEY = "wake-summary";
    private final WakeSummary wakeSummary = new WakeSummary();

    // studio recording / stt test
    private volatile boolean recording;
    private ByteArrayOutputStream recBuf;
    private RecordCallback recCb;
    private int recMax;
    private long recStart;
    private volatile SttTest sttTest;
    private volatile String sttTestProvider;
    /** Serialises segmenter access between the microphone thread and voice_inject. */
    private final Object frameLock = new Object();
    private volatile float level;
    private volatile long injectConv, injectAt;

    public VoiceController(Jarvis j, MicIn mic, SttHub hub) {
        this.j = j;
        this.ctx = j.ctx;
        this.mic = mic;
        this.hub = hub;
        this.packer = new AudioEncoder(j.sandbox.tmp);
        this.cues = new Cues(ctx, j.settings, j.journal);
        this.arbiter = new MicArbiter(ctx, main, new IntSupplier() {
            @Override public int getAsInt() { return micRunning ? mic.sessionId() : -1; }
        }, new MicArbiter.Listener() {
            @Override public void onBlockers(boolean otherAppRecording, boolean inCall) { applyYield(arbiterMachine.blockers(otherAppRecording, inCall)); }
            @Override public void onOwnSilenced() { applyYield(arbiterMachine.silenced()); }
        });
        rebuildSegmenter();
        reloadMatcher();
        main.postDelayed(new Runnable() { @Override public void run() { cues.warmUp(); } }, 250);
        arbiter.start();
        j.settings.addListener(new Settings.Listener() {
            @Override public void onSettingChanged(String key) {
                if (key.equals(Settings.WAKE_PHRASES)) reloadMatcher();
                if (key.equals("stt.endSilenceMs")) applyEndSilence();
            }
        });
        j.addListener(new JarvisListener() {
            @Override public void onState(AState s, String d) { syncSpeaking(s == AState.SPEAKING); }
            @Override public void onPermissionResult(String perm, boolean granted) {
                if (Manifest.permission.RECORD_AUDIO.equals(perm) && granted && j.settings.getBool(Settings.WAKE_ENABLED, false) && !wake) setWakeEnabled(true);
            }
        });
    }

    /** The Segmenter reads its Config on every frame, so a changed pause length applies to the next utterance without rebuilding it. */
    private void applyEndSilence() {
        int v = Math.max(300, Math.min(1500, j.settings.getInt("stt.endSilenceMs", 550)));
        endSilenceMs = v;
        if (segCfg != null) segCfg.endSilenceMs = v;
    }

    private void reloadMatcher() {
        List<String> custom = j.settings.getList(Settings.WAKE_PHRASES);
        matcher = custom.isEmpty() ? WakeMatcher.defaultRu() : new WakeMatcher(custom);
        if (localWake != null) localWake.unload();
        localWake = new LocalWake(new File(ctx.getFilesDir(), "models/" + WakePack.MODEL_NAME), matcher.phraseList(), j.journal);
    }

    private boolean localWakeReady() { return localWake.availabilityProblem().isEmpty(); }

    private void rebuildSegmenter() {
        Segmenter.Config c = new Segmenter.Config();
        c.sampleRate = RATE;
        segCfg = c;
        applyEndSilence();
        segmenter = new Segmenter(c, new Segmenter.Listener() {
            @Override public void onSpeechStart() { speechSeen = true; if (mode == Mode.COMMAND) j.setVoiceState(AState.LISTENING, "речь"); }
            @Override public void onLevel(float level) { }
            @Override public void onUtterance(PcmAudio audio, long startMs, long endMs, boolean truncated) { handleUtterance(audio, truncated); }
        });
    }

    // ---- permission / mic ------------------------------------------------------------------------------------------
    private volatile Boolean permissionOverride;

    /** Headless tests inject audio through a fake MicIn and must not depend on the host process holding RECORD_AUDIO. */
    public void assumePermission(Boolean granted) { permissionOverride = granted; }

    private boolean hasMicPermission() {
        Boolean o = permissionOverride;
        if (o != null) return o;
        return Build.VERSION.SDK_INT < 23 || ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean ensureMic() {
        synchronized (lock) {
            if (micRunning) return true;
            if (!hasMicPermission()) { problem = "Нет разрешения на микрофон"; return false; }
            boolean ok = mic.start(RATE, j.settings.getBool(Settings.WAKE_AEC, true), new MicIn.FrameListener() {
                @Override public void onFrame(short[] pcm, int n) { onFrameIn(pcm, n); }
                @Override public void onError(String message) { onMicError(message); }
            });
            micRunning = ok;
            problem = ok ? "" : (mic.lastError() == null || mic.lastError().isEmpty() ? "Не удалось включить микрофон" : mic.lastError());
            if (ok) j.journal.log(Journal.Cat.WAKE, Journal.Level.INFO, "Микрофон включён (" + RATE + " Гц)", null, null, -1);
            else j.journal.log(Journal.Cat.WAKE, Journal.Level.ERROR, "Микрофон не включился: " + problem, null, null, -1);
            return ok;
        }
    }

    private void releaseMicIfIdle() {
        synchronized (lock) {
            if (!micRunning) return;
            if (wake || mode == Mode.COMMAND || recording || sttTest != null) return;
            mic.stop();
            micRunning = false;
            j.journal.log(Journal.Cat.WAKE, Journal.Level.INFO, "Микрофон выключен", null, null, -1);
        }
        syncYield();
    }

    // ---- microphone yielding (another app, a call) -----------------------------------------------------------------------
    static final String MIC_BUSY = "Микрофон занят другим приложением", CALL_NOTICE = "Идёт звонок: Джарвис не слушает";
    private final Runnable resumeTimer = new Runnable() { @Override public void run() { applyYield(arbiterMachine.timerFired()); } };

    /** Tells the arbiter whether the assistant wants the microphone now. Always runs on the main thread. */
    private void syncYield() {
        main.post(new Runnable() {
            @Override public void run() {
                boolean want = wake || mode == Mode.COMMAND || recording || sttTest != null;
                arbiter.setWatchingCall(want);
                applyYield(arbiterMachine.want(want));
            }
        });
    }

    private void applyYield(List<MicArbiterMachine.Act> acts) {
        for (MicArbiterMachine.Act a : acts) {
            switch (a) {
                case PAUSE_MIC: pauseForOther(); break;
                case OPEN_MIC: applyYield(arbiterMachine.micOpened(ensureMic())); break;
                case SCHEDULE_RESUME:
                    main.removeCallbacks(resumeTimer);
                    main.postDelayed(resumeTimer, MicArbiterMachine.RESUME_DELAY_MS);
                    break;
                case CANCEL_RESUME: main.removeCallbacks(resumeTimer); break;
                case NOTIFY_BUSY: yieldedTo(MIC_BUSY, "Джарвис уступил микрофон и вернётся, когда он освободится"); break;
                case NOTIFY_CALL: yieldedTo(CALL_NOTICE, "Прослушивание вернётся после звонка"); break;
                case NOTIFY_GAVE_UP: gaveUp(); break;
                case RESUMED: resumed(); break;
                default: break;
            }
        }
    }

    private void pauseForOther() {
        synchronized (lock) {
            if (!micRunning) return;
            mic.stop();
            micRunning = false;
        }
        segmenter.reset();
    }

    private void yieldedTo(String status, String why) {
        problem = status;
        j.journal.log(Journal.Cat.WAKE, Journal.Level.INFO, status + ": " + why, null, null, -1);
        j.setVoiceState(AState.WAITING, status);
    }

    private void gaveUp() {
        problem = "Микрофон не освободился: прослушивание остановлено";
        j.journal.log(Journal.Cat.WAKE, Journal.Level.WARN, problem + " после нескольких попыток. Включите обращение снова, когда микрофон освободится", null, null, -1);
        j.toast(problem, 2);
        wake = false;
        synchronized (lock) { mode = Mode.OFF; }
        j.settings.setBool(Settings.WAKE_ENABLED, false);
        j.setVoiceState(AState.IDLE, "");
        syncYield();
        JarvisService.sync(ctx);
    }

    private void resumed() {
        problem = "";
        j.journal.log(Journal.Cat.WAKE, Journal.Level.INFO, "Микрофон снова у Джарвиса", null, null, -1);
        j.setVoiceState(mode == Mode.COMMAND ? AState.LISTENING : wake ? AState.WAITING : AState.IDLE, "");
    }

    private void onMicError(String message) {
        micRunning = false;
        problem = message == null ? "Микрофон остановлен системой" : message;
        JSONObject details = null;
        if (MicCapture.NO_AUDIO_MESSAGE.equals(message)) {
            details = new JSONObject();
            new JournalAction(JournalAction.BACKGROUND_SETTINGS, "Открыть настройки фона").putInto(details);
        }
        j.journal.log(Journal.Cat.WAKE, Journal.Level.ERROR, "Ошибка микрофона: " + problem, null, details, -1);
        if ((wake || mode == Mode.COMMAND) && restarts++ < 3) {
            main.postDelayed(new Runnable() { @Override public void run() { if ((wake || mode == Mode.COMMAND) && !micRunning) ensureMic(); } }, 1200L * restarts);
        } else if (wake) {
            wake = false;
            j.settings.setBool(Settings.WAKE_ENABLED, false);
            j.setVoiceState(AState.IDLE, "");
            j.toast("Микрофон недоступен: " + problem + ". Режим обращения выключен.", 2);
            JarvisService.sync(ctx);
        }
    }

    // ---- frames -------------------------------------------------------------------------------------------------------
    private void onFrameIn(short[] pcm, int n) {
        float lvl = rms(pcm, n);
        level = lvl;
        if (mode != Mode.OFF || recording || sttTest != null) j.level(lvl);
        if (recording) { recordFrame(pcm, n, lvl); return; }
        if (speakerBusy || System.currentTimeMillis() < speakerFreeAt) return;      // never listen to ourselves
        if (System.currentTimeMillis() < cueMuteUntil) return;                      // a cue is sounding and its tail
        if (mode == Mode.OFF && sttTest == null) return;
        // A completely zero-filled frame is not proof that the microphone works.
        // Reset restart protection only after Android delivers actual samples.
        if (lvl > 0f) restarts = 0;
        synchronized (frameLock) { segmenter.feed(pcm, n); }
    }

    // ---- test injection (voice_inject): the segmenter and utterance path of the microphone, fed by a file instead -------------
    /**
     * Feeds 16 kHz mono PCM, then one second of silence (which closes the utterance), into the segmenter. Command mode skips the
     * wake word; wake mode screens for it. The microphone is not started, the speaker-busy gate is skipped, and the recognised
     * command goes to convId (a silent test session) instead of the active one for the next 90 s.
     */
    public void injectPcm(short[] pcm, int n, boolean command, long convId) {
        injectConv = convId;
        injectAt = System.currentTimeMillis();
        synchronized (frameLock) {
            if (command) enterCommandMode(7000);
            else synchronized (lock) { mode = Mode.WAKE; segmenter.reset(); segmenter.setEnabled(true); }
            restarts = 0;
            segmenter.feed(pcm, n);
            segmenter.feed(new short[RATE], RATE);
        }
        if (!command && !wake) synchronized (lock) { if (mode == Mode.WAKE) mode = Mode.OFF; }
    }

    /** Forgets the test session of injected turns (called when the test has finished waiting). */
    public void clearInject() { injectConv = 0; }

    /** Commands from the microphone go to the active session; an injected test utterance goes to its own session for 90 s. */
    private void submitVoice(String text) {
        long c = injectConv;
        if (c > 0 && System.currentTimeMillis() - injectAt < 90_000) j.submit(text, null, "voice", c);
        else j.submit(text, null, "voice");
    }

    /** Loudness of the last microphone frame (0..1); 0 when the microphone is off. */
    public float lastLevel() { return micRunning ? level : 0f; }

    /** The recognition order of the microphone path (user chain). */
    public List<SttProvider> sttChain() { return hub.chain(false); }

    /** The wake phrases in use (custom ones or the built-in list). */
    public List<String> wakePhrases() { WakeMatcher m = matcher; return m == null ? new ArrayList<String>() : m.phraseList(); }

    private static float rms(short[] p, int n) {
        double s = 0;
        for (int i = 0; i < n; i++) s += p[i] * (double) p[i];
        double r = Math.sqrt(s / Math.max(1, n)) / 32768.0;
        return (float) Math.min(1.0, Math.sqrt(r) * 1.9);     // perceptual-ish curve so quiet speech still moves the orb
    }

    private void syncSpeaking(boolean speaking) {
        if (speaking) { speakerBusy = true; segmenter.reset(); }
        else if (speakerBusy) { speakerBusy = false; speakerFreeAt = System.currentTimeMillis() + 450; }
    }

    // ---- utterances ---------------------------------------------------------------------------------------------------
    private void handleUtterance(final PcmAudio audio, boolean truncated) {
        final SttTest test = sttTest;
        if (test != null) { sttTest = null; runTest(test, sttTestProvider, audio); releaseMicIfIdle(); return; }
        Mode m = mode;
        final long deliveredNs = System.nanoTime();
        if (m == Mode.COMMAND) {
            exitCommandMode();
            cueMute(cues.playStop());
            j.listening(ListeningIndicator.HEARD);
            j.setVoiceState(AState.RECOGNIZING, "");
            final TurnTrace tr = utteranceTrace(deliveredNs, truncated);
            warmUpSpeech();
            sttExec.execute(new Runnable() { @Override public void run() { recognizeCommand(audio, tr); } });
        } else if (m == Mode.WAKE) {
            countWakeFragment();
            long ms = audio.durationMs();
            if (ms > 8500) { j.journal.log(Journal.Cat.WAKE, Journal.Level.DEBUG, "Длинная речь пропущена (" + ms + " мс): не похоже на обращение", null, null, -1); return; }
            final TurnTrace tr = TraceStore.global().create("voice");
            markUtteranceEnd(tr, deliveredNs, truncated);
            sttExec.execute(new Runnable() { @Override public void run() { screenForWake(audio, tr); } });
        }
    }

    /** The trace for a command utterance: continues the one a bare wake word started (if recent), else a new one. */
    private TurnTrace utteranceTrace(long deliveredNs, boolean truncated) {
        TurnTrace tr = wakeTrace;
        wakeTrace = null;
        if (tr == null || System.currentTimeMillis() - wakeTraceAt > 30_000) tr = TraceStore.global().create("voice");
        markUtteranceEnd(tr, deliveredNs, truncated);
        return tr;
    }

    /**
     * speech_end is an estimate: the segmenter delivers an utterance endSilenceMs after the voice stopped, so the voice end
     * is the delivery time minus that silence (a truncated utterance was cut by the length limit: no silence to subtract).
     */
    private void markUtteranceEnd(TurnTrace tr, long deliveredNs, boolean truncated) {
        tr.markLastAt(TurnTrace.SPEECH_END, truncated ? deliveredNs : deliveredNs - endSilenceMs * 1_000_000L);
        tr.markLastAt(TurnTrace.SPEECH_END_DETECTED, deliveredNs);
    }

    private void warmUpSpeech() {
        if (j.speaker instanceof SpeechService) ((SpeechService) j.speaker).warmUp();
    }

    private void countWakeFragment() {
        synchronized (wakeSummary) { wakeSummary.fragment(System.currentTimeMillis()); }
        publishWakeSummary();
    }

    private void countWakeCall() {
        synchronized (wakeSummary) { wakeSummary.call(System.currentTimeMillis()); }
        publishWakeSummary();
    }

    private void publishWakeSummary() {
        String text;
        synchronized (wakeSummary) { text = wakeSummary.text(System.currentTimeMillis()); }
        j.journal.summary(Journal.Cat.WAKE, WAKE_SUMMARY_KEY, text);
    }

    /** At most 10 wake screenings per minute. */
    private boolean wakeAllowed() {
        synchronized (wakeStamps) {
            long now = System.currentTimeMillis(), old = wakeStamps[wakeIdx];
            if (old != 0 && now - old < 60_000) return false;
            wakeStamps[wakeIdx] = now;
            wakeIdx = (wakeIdx + 1) % 10;
            return true;
        }
    }

    /**
     * Local screening (default): the offline gate decides whether the wake word was said; only then is the utterance sent
     * to the STT chain, once, for the command text. Cloud screening (wake.engine=cloud, or auto without a local model)
     * sends every utterance and is rate-limited.
     */
    private void screenForWake(PcmAudio audio, TurnTrace tr) {
        String engine = j.settings.get(Settings.WAKE_ENGINE, WakeEngine.AUTO);
        boolean localHit = false;
        if (WakeEngine.pick(engine, localWakeReady()) == WakeEngine.Kind.LOCAL) {
            LocalWake.Detection d = localWake.detect(audio);
            if (d.ok()) {
                if (!d.found) {
                    j.journal.log(Journal.Cat.WAKE, Journal.Level.DEBUG, "Речь без обращения (локально, " + d.decodeMs + " мс)", null, null, d.decodeMs);
                    return;
                }
                localHit = true;
            } else {
                j.journal.log(Journal.Cat.WAKE, Journal.Level.WARN, "Локальное обращение недоступно: " + d.error, null, null, -1);
                if (!WakeEngine.cloudFallbackAllowed(engine)) return;
            }
        }
        boolean cloudConfirm = j.settings.getBool("wake.cloudConfirm", false);
        boolean useCloud = !localHit || WakeEngine.verifyLocalHits(engine, cloudConfirm);
        if (useCloud && !wakeAllowed()) {
            j.journal.log(Journal.Cat.WAKE, Journal.Level.WARN, "Слишком много облачных проверок обращения в минуту", null, null, -1);
            return;
        }
        String text = "";
        if (useCloud) {
            try { text = transcribe(audio, true, "wake", tr); } catch (VoiceException e) {
                if (!localHit || cloudConfirm) {
                    j.journal.w(Journal.Cat.WAKE, "Распознавание обращения не удалось: " + e.userMessage, e, null);
                    return;
                }
            }
        }
        WakeMatcher.Match m = matcher.match(text);
        boolean commandHeard = m.found && m.positionOk;
        if (!commandHeard && !localHit) {
            j.journal.log(Journal.Cat.WAKE, Journal.Level.DEBUG, localHit ? "Локальное срабатывание не подтверждено облаком" : "Речь без обращения (" + text.split("\\s+").length + " сл.)", null, null, -1);
            return;
        }
        countWakeCall();
        j.journal.log(Journal.Cat.WAKE, Journal.Level.INFO, "Обращение услышано (" + (localHit ? "локально" : "облако") + ")", null,
            J.put(new JSONObject(), "heard", commandHeard ? m.heard : ""), -1);
        tr.mark(TurnTrace.WAKE_DETECTED);
        j.speaker.stop();
        warmUpSpeech();
        String command = commandHeard ? m.command.trim() : "";
        if (!command.isEmpty()) { j.setVoiceState(AState.WAITING, ""); TraceStore.global().handoff(tr); submitVoice(command); return; }
        wakeTrace = tr; wakeTraceAt = System.currentTimeMillis();
        cueMute(cues.playStart(false));
        enterCommandMode(7000);
    }

    private void recognizeCommand(PcmAudio audio, TurnTrace tr) {
        String text;
        try { text = transcribe(audio, false, "command", tr); } catch (VoiceException e) {
            failVoice(e.userMessage, e.technical);
            return;
        }
        if (text.isEmpty()) { if (flushHeld()) return; failVoice("Не разобрал речь. Повторите, пожалуйста.", "пустой результат распознавания"); return; }
        // the wake word may still prefix a push-to-talk command ("Джарвис, включи музыку")
        WakeMatcher.Match m = matcher.match(text);
        String cmd = m.found && m.positionOk ? m.command : text;
        if (cmd.trim().isEmpty()) { wakeTrace = tr; wakeTraceAt = System.currentTimeMillis(); cueMute(cues.playStart(false)); enterCommandMode(7000); return; }
        String prev = heldCmd;
        if (prev != null) { heldCmd = null; heldTrace = null; cmd = prev + " " + cmd; }      // the continuation of a held phrase
        else if (j.settings.getBool("stt.holdUnfinished", true) && app.jarvis.core.audio.UnfinishedSpeech.isUnfinished(cmd)) {
            heldCmd = cmd.trim(); heldTrace = tr;
            j.journal.log(Journal.Cat.STT, Journal.Level.DEBUG, "Фраза оборвана на полуслове, жду продолжение", null, null, -1);
            enterCommandMode(HOLD_MS);
            return;
        }
        submitCommand(cmd.trim(), tr);
    }

    private void submitCommand(String cmd, TurnTrace tr) {
        j.setVoiceState(wake ? AState.WAITING : AState.IDLE, "");
        TraceStore.global().handoff(tr);
        submitVoice(cmd);
        releaseMicIfIdle();
    }

    /** Sends a held phrase that got no continuation. True when there was one. */
    private boolean flushHeld() {
        String h = heldCmd;
        TurnTrace tr = heldTrace;
        if (h == null) return false;
        heldCmd = null; heldTrace = null;
        if (mode == Mode.COMMAND) exitCommandMode();
        submitCommand(h, tr != null ? tr : TraceStore.global().create("voice"));
        return true;
    }

    private void failVoice(String user, String technical) {
        if (flushHeld()) return;
        j.journal.log(Journal.Cat.STT, Journal.Level.WARN, "Команда не распознана: " + user, null, J.put(new JSONObject(), "technical", technical), -1);
        j.setVoiceState(wake ? AState.WAITING : AState.IDLE, "");
        j.flash(AState.ERROR, user);
        j.toast(user, 2);
        j.lastAction(user, false);
        releaseMicIfIdle();
        cueMute(cues.playError());
        j.listening(ListeningIndicator.FAILED);
    }

    /** Runs the user's chain; returns "" for silence. Throws only when every provider failed. */
    private String transcribe(PcmAudio audio, boolean forWake, String purpose, TurnTrace tr) throws VoiceException {
        List<SttProvider> chain = hub.chain(forWake);
        if (chain.isEmpty()) throw new VoiceException(VoiceException.Kind.CONFIG, "Не настроено распознавание речи. Откройте Настройки → Обращение и распознавание.");
        CancelToken c = new CancelToken();
        sttCancel = c;
        long t0 = System.nanoTime();
        if (tr != null) {
            tr.markLast(TurnTrace.STT_START);
            tr.put("stt", "purpose", purpose);
            tr.put("stt", "audio_ms", audio.durationMs());
            tr.put("stt", "pcm_bytes", audio.pcm.length);
        }
        SttOptions o = new SttOptions();
        o.language = j.settings.get(Settings.STT_LANGUAGE, "ru");
        o.hint = "Джарвис";
        o.format = j.settings.get(Settings.STT_FORMAT, "auto");
        o.packer = packer;
        SttHedge.Outcome out = SttHedge.run(chain, audio, o, j.settings.getInt(Settings.STT_HEDGE_MS, 3000), c, new SttHedge.Listener() {
            @Override public void onFailed(SttProvider p, VoiceException e) {
                j.journal.w(Journal.Cat.STT, p.info().name + ": " + e.userMessage, e, J.put(new JSONObject(), "technical", e.technical));
            }
        });
        SttResult r = out.result;
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        // every real upload teaches the next one which format is quickest (raw WAV on a fast link, Opus only on a slow one)
        if (r.uploadedBytes > 0 && r.latencyMs > 0) UplinkMeter.record(r.uploadedBytes, r.latencyMs);
        if (tr != null) {
            tr.markLast(TurnTrace.STT_FINAL);
            if (UplinkMeter.bytesPerSec() > 0) tr.put("stt", "uplink_kbs", Math.round(UplinkMeter.bytesPerSec() / 1000.0));
            tr.put("stt", "provider", r.providerId);
            tr.put("stt", "attempts", out.attempts);
            tr.put("stt", "chars", r.text == null ? 0 : r.text.length());
            tr.put("stt", "format", r.uploadFormat);
            tr.put("stt", "bytes_uploaded", r.uploadedBytes);
        }
        if (!sttFormatLogged && !r.uploadFormat.isEmpty()) {
            sttFormatLogged = true;
            String name = AudioPacker.OPUS.equals(r.uploadFormat) ? "ogg/opus" : r.uploadFormat;
            j.journal.log(Journal.Cat.STT, Journal.Level.INFO, "stt: " + name + " " + Math.max(1, r.uploadedBytes / 1024) + " КБ", null, null, -1);
        }
        j.journal.log(Journal.Cat.STT, "wake".equals(purpose) ? Journal.Level.DEBUG : Journal.Level.INFO, "Распознано (" + purpose + ", " + r.providerId + "): " + (r.text == null ? 0 : r.text.length()) + " симв., " + audio.durationMs() + " мс звука", null,
            J.put(J.put(new JSONObject(), "provider", r.providerId), "confidence", r.confidence), ms);
        return r.text == null ? "" : r.text.trim();
    }

    // ---- command mode -------------------------------------------------------------------------------------------------
    private void enterCommandMode(final long timeoutMs) {
        synchronized (lock) {
            mode = Mode.COMMAND;
            speechSeen = false;
            commandSince = System.currentTimeMillis();
            segmenter.reset();
            segmenter.setEnabled(true);
        }
        duckMusic(true);
        j.listening(ListeningIndicator.LISTENING);
        j.setVoiceState(AState.LISTENING, "");
        syncYield();
        final long stamp = commandSince;
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (mode == Mode.COMMAND && commandSince == stamp && !speechSeen) {
                    if (heldCmd != null) { sttExec.execute(new Runnable() { @Override public void run() { flushHeld(); } }); return; }
                    exitCommandMode();
                    j.setVoiceState(wake ? AState.WAITING : AState.IDLE, "");
                    j.toast("Не услышал команду", 0);
                    releaseMicIfIdle();
                    cueMute(cues.playError());
                    j.listening(ListeningIndicator.FAILED);
                }
            }
        }, timeoutMs);
    }

    private void exitCommandMode() {
        synchronized (lock) { mode = wake ? Mode.WAKE : Mode.OFF; }
        duckMusic(false);
    }

    private void duckMusic(boolean on) { j.duckMusic("mic", on); }

    /** Discards captured audio until the cue that just started has finished sounding (plus its tail). */
    private void cueMute(long cueMs) {
        cueMuteUntil = CueTiming.dropUntil(System.currentTimeMillis(), cueMs);
    }

    // ---- VoiceControl -------------------------------------------------------------------------------------------------
    @Override public boolean startListening() {
        if (!hasMicPermission()) { problem = "Нет разрешения на микрофон"; j.needPermission(Manifest.permission.RECORD_AUDIO); return false; }
        j.speaker.stop();
        if (mode == Mode.COMMAND) { cancel(); return true; }       // second tap = cancel
        if (arbiterMachine.blocked()) { j.toast(arbiterMachine.otherAppHolds() ? MIC_BUSY : CALL_NOTICE, 2); return false; }
        cueMute(cues.playStart(speakerBusy));
        if (!ensureMic()) { j.toast(problem, 2); return false; }
        enterCommandMode(7000);
        JarvisService.sync(ctx);
        return true;
    }

    @Override public void stopListening() { cancel(); }

    @Override public void cancel() {
        CancelToken c = sttCancel;
        if (c != null) c.cancel("cancel");
        heldCmd = null; heldTrace = null;
        boolean capturing = mode == Mode.COMMAND;
        if (capturing) exitCommandMode();
        segmenter.reset();
        sttTest = null;
        if (recording) stopRecording();
        j.setVoiceState(wake ? AState.WAITING : AState.IDLE, "");
        releaseMicIfIdle();
        if (capturing) {
            cueMute(cues.playStop());
            j.listening(ListeningIndicator.OFF);
        }
    }

    @Override public void setWakeEnabled(boolean enabled) {
        if (enabled == wake) return;
        if (enabled) {
            if (!hasMicPermission()) { problem = "Нет разрешения на микрофон"; j.needPermission(Manifest.permission.RECORD_AUDIO); return; }
            boolean localCanWake = WakeEngine.pick(
                j.settings.get(Settings.WAKE_ENGINE, WakeEngine.AUTO), localWakeReady()) == WakeEngine.Kind.LOCAL
                && localWakeReady();
            if (hub.chain(true).isEmpty() && !localCanWake) {
                problem = "Нет доступного распознавания. Откройте Студия голоса → Обращение и установите офлайн-модель.";
                j.toast(problem, 2);
                j.settings.setBool(Settings.WAKE_ENABLED, false);
                return;
            }
            wake = true;
            if (arbiterMachine.blocked()) {
                synchronized (lock) { mode = Mode.WAKE; segmenter.reset(); segmenter.setEnabled(true); }
                j.settings.setBool(Settings.WAKE_ENABLED, true);
                syncYield();
                JarvisService.sync(ctx);
                return;
            }
            if (!ensureMic()) { wake = false; j.settings.setBool(Settings.WAKE_ENABLED, false); j.toast(problem, 2); return; }
            synchronized (lock) { mode = Mode.WAKE; segmenter.reset(); segmenter.setEnabled(true); }
            j.settings.setBool(Settings.WAKE_ENABLED, true);
            j.setVoiceState(AState.WAITING, "");
            j.journal.log(Journal.Cat.WAKE, Journal.Level.INFO, "Режим обращения включён", null, null, -1);
            requestNotificationPermission();
            syncYield();
        } else {
            wake = false;
            if (mode == Mode.WAKE) mode = Mode.OFF;
            j.settings.setBool(Settings.WAKE_ENABLED, false);
            j.setVoiceState(AState.IDLE, "");
            j.journal.log(Journal.Cat.WAKE, Journal.Level.INFO, "Режим обращения выключен", null, null, -1);
            releaseMicIfIdle();
            syncYield();
        }
        JarvisService.sync(ctx);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) j.needPermission(Manifest.permission.POST_NOTIFICATIONS);
    }

    @Override public boolean isWakeEnabled() { return wake; }
    @Override public boolean isMicActive() { return micRunning; }
    @Override public String micProblem() { return problem; }

    // ---- ListenApi: recording a voice sample ----------------------------------------------------------------------------
    @Override public void recordSample(int maxSeconds, RecordCallback cb) {
        if (!hasMicPermission()) { j.needPermission(Manifest.permission.RECORD_AUDIO); cb.onError("Нет разрешения на микрофон"); return; }
        if (recording) { cb.onError("Запись уже идёт"); return; }
        j.speaker.stop();
        if (!ensureMic()) { cb.onError(problem); return; }
        synchronized (lock) { recBuf = new ByteArrayOutputStream(maxSeconds * RATE * 2); recCb = cb; recMax = maxSeconds; recStart = System.currentTimeMillis(); recording = true; }
        syncYield();
        JarvisService.sync(ctx);
    }

    private long lastRecProgress;

    private void recordFrame(short[] pcm, int n, float lvl) {
        ByteArrayOutputStream b = recBuf;
        if (b == null) return;
        byte[] bytes = new byte[n * 2];
        for (int i = 0; i < n; i++) { bytes[2 * i] = (byte) pcm[i]; bytes[2 * i + 1] = (byte) (pcm[i] >> 8); }
        synchronized (lock) { b.write(bytes, 0, bytes.length); }
        long now = System.currentTimeMillis();
        final float sec = b.size() / 2f / RATE;
        if (now - lastRecProgress > 50) {
            lastRecProgress = now;
            final RecordCallback cb = recCb;
            final float l = lvl;
            if (cb != null) main.post(new Runnable() { @Override public void run() { cb.onProgress(sec, l); } });
        }
        if (sec >= recMax) stopRecording();
    }

    @Override public void stopRecording() {
        final RecordCallback cb;
        final byte[] data;
        synchronized (lock) {
            if (!recording) return;
            recording = false;
            cb = recCb; recCb = null;
            data = recBuf == null ? new byte[0] : recBuf.toByteArray();
            recBuf = null;
        }
        releaseMicIfIdle();
        if (cb == null) return;
        if (data.length < RATE * 2 / 2) { main.post(new Runnable() { @Override public void run() { cb.onError("Запись слишком короткая (меньше 0,5 с)"); } }); return; }
        try {
            File dir = new File(j.sandbox.work, "voice-samples");
            dir.mkdirs();
            final File f = new File(dir, "sample-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".wav");
            try (FileOutputStream os = new FileOutputStream(f)) { os.write(new PcmAudio(data, RATE).toWav()); os.getFD().sync(); }
            final float sec = data.length / 2f / RATE;
            main.post(new Runnable() { @Override public void run() { cb.onDone(j.sandbox.rel(f), sec); } });
        } catch (final Exception e) {
            main.post(new Runnable() { @Override public void run() { cb.onError("Не удалось сохранить запись: " + e.getMessage()); } });
        }
    }

    @Override public boolean isRecording() { return recording; }

    // ---- ListenApi: STT tests ---------------------------------------------------------------------------------------------
    @Override public void testStt(String providerId, final SttTest cb) {
        if (!hasMicPermission()) { j.needPermission(Manifest.permission.RECORD_AUDIO); cb.onError("Нет разрешения на микрофон", ""); return; }
        if (hub.get(providerId) == null) { cb.onError("Провайдер не найден", providerId); return; }
        j.speaker.stop();
        if (!ensureMic()) { cb.onError(problem, ""); return; }
        sttTestProvider = providerId;
        sttTest = cb;
        synchronized (lock) { speechSeen = false; segmenter.reset(); segmenter.setEnabled(true); }
        syncYield();
        main.post(new Runnable() { @Override public void run() { cb.onStarted(); } });
        main.postDelayed(new Runnable() { @Override public void run() { if (sttTest == cb) { sttTest = null; cb.onError("Не услышал речь за 8 секунд", ""); releaseMicIfIdle(); } } }, 8000);
    }

    private void runTest(final SttTest cb, final String providerId, final PcmAudio audio) {
        final SttProvider p = hub.get(providerId);
        sttExec.execute(new Runnable() {
            @Override public void run() {
                long t0 = System.nanoTime();
                try {
                    SttOptions o = new SttOptions();
                    o.language = j.settings.get(Settings.STT_LANGUAGE, "ru");
                    final SttResult r = p.transcribe(audio, o, new CancelToken());
                    final long ms = (System.nanoTime() - t0) / 1_000_000L;
                    main.post(new Runnable() { @Override public void run() { cb.onResult(r.text, ms, providerId); } });
                } catch (final VoiceException e) {
                    main.post(new Runnable() { @Override public void run() { cb.onError(e.userMessage, e.technical); } });
                }
            }
        });
    }

    @Override public void checkStt(final String providerId, final SttTest cb) {
        final SttProvider p = hub.get(providerId);
        if (p == null) { cb.onError("Провайдер не найден", providerId); return; }
        main.post(new Runnable() { @Override public void run() { cb.onStarted(); } });
        sttExec.execute(new Runnable() {
            @Override public void run() {
                try (InputStream in = ctx.getAssets().open("check_ru.wav")) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    PcmAudio sample = PcmAudio.fromWav(bo.toByteArray());
                    if (sample == null) throw new java.io.IOException("встроенный образец повреждён");
                    final ProviderStatus st = p.check(sample, new CancelToken());
                    main.post(new Runnable() {
                        @Override public void run() { if (st.ok) cb.onResult(st.detail, st.totalMs, providerId); else cb.onError(st.lastError.isEmpty() ? "Проверка не пройдена" : st.lastError, st.detail); }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() { @Override public void run() { cb.onError("Не удалось выполнить проверку: " + e.getMessage(), ""); } });
                }
            }
        });
    }
}
