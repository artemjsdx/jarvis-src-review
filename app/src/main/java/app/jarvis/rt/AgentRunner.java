package app.jarvis.rt;

import app.jarvis.core.agent.AgentListener;
import app.jarvis.core.agent.AgentLoop;
import app.jarvis.core.agent.AgentOutcome;
import app.jarvis.core.agent.AckPolicy;
import app.jarvis.core.agent.AgentRunSpec;
import app.jarvis.core.agent.ConfirmPolicy;
import app.jarvis.core.net.LatencyBook;
import app.jarvis.core.skills.SkillHint;
import app.jarvis.core.tools.MemoryStore;
import app.jarvis.core.agent.AgentCommentPolicy;
import app.jarvis.core.agent.DirectCommands;
import app.jarvis.core.agent.PromptReport;
import app.jarvis.core.agent.Prompts;
import app.jarvis.core.web.ExtraSearch;
import app.jarvis.core.agent.SpeechStreamer;
import app.jarvis.core.agent.StepLimit;
import app.jarvis.core.agent.ToolRouter;
import app.jarvis.core.agent.ToolSelection;
import app.jarvis.core.agent.Triggers;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.Cap;
import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.LlmException;
import app.jarvis.core.llm.LlmResult;
import app.jarvis.core.llm.ModelCaps;
import app.jarvis.core.llm.ToolCall;
import app.jarvis.core.llm.ToolSpec;
import app.jarvis.core.log.Journal;
import app.jarvis.core.trace.TraceStore;
import app.jarvis.core.trace.TurnTrace;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.J;
import app.jarvis.data.ChatItem;
import app.jarvis.data.Settings;
import app.jarvis.media.MusicOut;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Runs user commands: well-known ones directly, everything else through the model with real tools. Persists every
 * message and tool call, streams text to the UI, and hands finished sentences to the speaker while the model is still
 * writing. One run at a time; a new command cancels the current one.
 */
public final class AgentRunner {
    private final Jarvis j;
    private final DirectCommands direct = new DirectCommands();
    private final ExecutorService exec = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "jv-agent"); t.setDaemon(true); return t; }
    });
    private final AtomicInteger pending = new AtomicInteger();
    private final SkillHint skillHint = new SkillHint();
    private static final String SKILLS_SUGGEST = "skills.suggest";
    public static final String REMINDER_EVERY = "prompt.reminderEvery";
    public static final int REMINDER_DEFAULT = 8;
    /** true (default): the model is shown core tools + the groups a request needs; false: every tool on every request, as before groups existed. */
    public static final String TOOLS_GROUPS = "tools.groups";
    /** With groups on: every N-th user request carries EVERY tool (a reminder of all that the assistant can do). 0 = never, 1 = every request. */
    public static final String TOOLS_FULL_EVERY = "tools.fullEvery";
    public static final int TOOLS_FULL_DEFAULT = 0;
    private static final int SKILL_INDEX_CHARS = 1200;
    private volatile CancelToken current;
    /** Conversations that must never speak (MCP test sessions while mcp.allowAudio is off). */
    public final java.util.Set<Long> silentConvs = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
    private final LatencyBook book;
    private final java.util.concurrent.ScheduledExecutorService sch = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "jv-ack"); t.setDaemon(true); return t; }
    });
    private volatile AckPolicy ack;

    AgentRunner(Jarvis j) {
        this.j = j;
        this.book = new LatencyBook(new File(j.home, "latency.json"));
        TraceStore.install(new File(j.home, "trace"), new TraceStore.Listener() {
            @Override public void onTurnFinished(TurnTrace t) {
                j.journal.log(Journal.Cat.PERFORMANCE, Journal.Level.INFO, t.summaryRu(), null, t.toJson(), t.span(t.has(TurnTrace.SPEECH_END) ? TurnTrace.SPEECH_END : TurnTrace.SUBMIT, t.has(TurnTrace.PLAY_START) ? TurnTrace.PLAY_START : TurnTrace.AGENT_DONE));
            }
        });
    }

    public boolean isRunning() { return pending.get() > 0; }

    public void submit(final String text, final List<ChatItem.Attachment> atts, final String source) { submit(text, atts, source, 0); }

    /** convId 0 = the conversation the UI shows; otherwise the given one (used by the MCP bridge for test sessions). */
    public void submit(final String text, final List<ChatItem.Attachment> atts, final String source, final long convId) {
        final String t = text == null ? "" : text.trim();
        if (t.isEmpty() && (atts == null || atts.isEmpty())) return;
        CancelToken prev = current;
        if (prev != null) prev.cancel("новая команда");
        j.speaker.stop();
        final TraceStore traces = TraceStore.global();
        TurnTrace tr0 = "voice".equals(source) ? traces.takeHandoff() : null;
        final TurnTrace trace = tr0 != null ? tr0 : traces.create(source);
        trace.mark(TurnTrace.SUBMIT);
        traces.publish(trace);
        if (pending.getAndIncrement() == 0) j.runChanged(true);
        exec.execute(new Runnable() {
            @Override public void run() {
                try { runOnce(t, atts == null ? new ArrayList<ChatItem.Attachment>() : atts, source, trace, convId); }
                catch (Throwable e) {
                    j.journal.e(Journal.Cat.AGENT, "Сбой запуска агента", e);
                    j.toast("Внутренняя ошибка: " + e.getClass().getSimpleName(), 2);
                    j.setAgentState(AState.IDLE, "");
                    j.flash(AState.ERROR, "Внутренняя ошибка");
                } finally {
                    traces.agentDone(trace);
                    if (pending.decrementAndGet() == 0) { current = null; j.runChanged(false); }
                }
            }
        });
    }

    public void cancel() {
        CancelToken c = current;
        if (c != null) c.cancel("пользователь");
    }

    // ----------------------------------------------------------------------------------------------------------------
    private void runOnce(String text, List<ChatItem.Attachment> atts, String source, TurnTrace trace, long convOverride) {
        final String runId = "r" + Long.toString(System.currentTimeMillis(), 36);
        final CancelToken cancel = new CancelToken();
        current = cancel;
        final long t0 = System.nanoTime();
        final long conv = convOverride > 0 ? convOverride : j.sessions.active();
        final boolean voiceSource = "voice".equals(source);

        ChatItem u = new ChatItem();
        u.convId = conv; u.kind = ChatItem.Kind.USER; u.text = text; u.source = source; u.runId = runId;
        u.attachments.addAll(atts);
        j.chat.add(u);
        j.sessions.autoTitle(conv, text);
        j.fireChat(ChatEvent.Type.ADDED, u, null, runId);
        j.setAgentState(AState.THINKING, "");

        final boolean speak = shouldSpeak(voiceSource) && !silentConvs.contains(conv);
        final Run run = new Run(runId, conv, cancel, speak, trace);
        trace.put("turn", "run_id", runId);
        if (speak) warmUpSpeech();    // connect to the TTS service while the model thinks

        // 1) direct commands: the ACTION runs at once without a model round trip; the confirmation is phrased by the model from the
        //    real tool result (natural, varied), and falls back to the short template only when the model is unavailable.
        if (atts.isEmpty()) {
            List<ChatMessage> historyBefore = j.chat.history(conv, j.contextChars(j.providerId(), j.modelFor(j.providerId())) - 4000);
            if (!historyBefore.isEmpty() && ChatMessage.USER.equals(historyBefore.get(historyBefore.size() - 1).role)) historyBefore.remove(historyBefore.size() - 1);
            DirectEnv denv = new DirectEnv(run);
            DirectCommands.Outcome o = direct.handle(text, denv);
            ConfirmPolicy.Mode cmode = ConfirmPolicy.Mode.parse(j.settings.get("direct.confirm", "auto"));
            boolean fastConfirm = o.handled && o.ok && !o.fast.isEmpty() && ConfirmPolicy.useFast(cmode, book, j.providerId());
            if (o.handled && !denv.calls.isEmpty() && !fastConfirm && j.settings.getBool("direct.llmReply", true)) {
                List<Jarvis.LlmChoice> cs = j.llmChoices();
                if (!cs.isEmpty()) j.chat.runStarted(runId, conv, cs.get(0).model, cs.get(0).preset.id, text);
                AgentOutcome po = phraseDirect(run, text, source, historyBefore, denv.calls, o);
                if (po != null) { finishRun(run, po, null, voiceSource, t0); return; }
            }
            if (o.handled) {
                ChatItem a = new ChatItem();
                a.convId = denv.switchedTo > 0 ? denv.switchedTo : conv; a.kind = ChatItem.Kind.ASSISTANT; a.text = o.reply; a.runId = runId; a.model = "direct";
                j.chat.add(a);
                j.fireChat(ChatEvent.Type.ADDED, a, null, runId);
                if (speak) j.speaker.speak(fastConfirm ? o.fast : o.reply);
                j.lastAction(o.reply, o.ok);
                j.chat.runFinished(runId, o.ok ? "DONE" : "FAILED", 0, run.toolCount, 0, ms(t0), o.ok ? "" : o.reply);
                if (fastConfirm) trace.put("turn", "confirm", "fast");
                j.journal.log(Journal.Cat.AGENT, Journal.Level.INFO, "Прямая команда: «" + text + "» → " + (o.ok ? "выполнено" : "не выполнено"), runId, null, ms(t0));
                j.setAgentState(AState.IDLE, "");
                j.flash(o.ok ? AState.DONE : AState.ERROR, o.reply);
                return;
            }
        }

        // 2) model with tools
        List<Jarvis.LlmChoice> choices = j.llmChoices();
        if (choices.isEmpty() || !choices.get(0).hasKey || choices.get(0).model.isEmpty()) {
            String why = choices.isEmpty() ? "Провайдер не выбран" : !choices.get(0).hasKey ? "Не задан API-ключ провайдера «" + choices.get(0).preset.name + "»" : "Не выбрана модель";
            failRun(run, voiceSource, why + ". Откройте Настройки → LLM.", AgentOutcome.Status.ERROR, "settings/llm", t0);
            return;
        }
        j.chat.runStarted(runId, conv, choices.get(0).model, choices.get(0).preset.id, text);
        armAck(run, voiceSource, trace, t0);

        AgentOutcome out = null;
        Jarvis.LlmChoice used = null;
        for (int i = 0; i < choices.size(); i++) {
            Jarvis.LlmChoice ch = choices.get(i);
            if (i > 0 && !ch.hasKey) continue;
            used = ch;
            run.model = ch.model;
            out = execute(run, ch, text, atts, source);
            boolean retryable = out.status == AgentOutcome.Status.ERROR && out.toolCalls == 0 && out.error != null && !out.error.partial && run.streamedChars == 0
                && (out.error.kind == LlmException.Kind.GEO_BLOCK || out.error.kind == LlmException.Kind.NETWORK || out.error.kind == LlmException.Kind.TIMEOUT
                || out.error.kind == LlmException.Kind.SERVER || out.error.kind == LlmException.Kind.RATE_LIMIT || out.error.kind == LlmException.Kind.PAYMENT
                || out.error.kind == LlmException.Kind.AUTH || out.error.kind == LlmException.Kind.NOT_FOUND);
            if (!retryable || i == choices.size() - 1 || cancel.isCancelled()) break;
            j.journal.log(Journal.Cat.LLM, Journal.Level.WARN, "Провайдер «" + ch.preset.name + "» недоступен (" + out.error.userMessage + "), пробую «" + choices.get(i + 1).label() + "»", runId, null, -1);
            j.toast(ch.preset.name + " недоступен, использую " + choices.get(i + 1).preset.name, 0);
        }
        run.streamer.flush();
        disarmAck(run);
        finishRun(run, out, used, voiceSource, t0);
    }

    // ---- quick acknowledgement -------------------------------------------------------------------------------------------
    private AckPolicy ackPolicy() {
        AckPolicy a = ack;
        if (a == null && j.speaker instanceof SpeechService) {
            synchronized (this) {
                if (ack == null) ack = new AckPolicy(sch, ((SpeechService) j.speaker).ackPlayer(), AckPolicy.parsePhrases(j.settings.get("ack.phrases")));
                a = ack;
            }
        }
        return a;
    }

    private void armAck(Run run, boolean voiceSource, TurnTrace trace, long t0) {
        AckPolicy.Mode mode = AckPolicy.Mode.parse(j.settings.get("ack.mode", "voice"));
        if (mode == AckPolicy.Mode.OFF || !run.speak || (mode == AckPolicy.Mode.VOICE && !voiceSource)) return;
        AckPolicy a = ackPolicy();
        if (a == null) return;
        a.setPhrases(AckPolicy.parsePhrases(j.settings.get("ack.phrases")));
        long delay = Math.max(0, j.settings.getInt("ack.delayMs", 900));
        long sinceEnd = trace.span(TurnTrace.SPEECH_END, TurnTrace.SUBMIT);
        long already = Math.max(0, sinceEnd) + ms(t0);      // the delay counts from the end of the user's speech
        run.ackBefore = a.lastPhrase();
        run.ackToken = a.arm(Math.max(0, delay - already));
        run.ack = a;
    }

    private void disarmAck(Run run) {
        if (run.ack != null) run.ack.disarm(run.ackToken);
    }

    /**
     * The command was already executed by tools. Ask the model for ONE natural confirmation using the real results; returns null
     * (caller falls back to the template) when no model is configured or the model call failed before producing any text.
     */
    private AgentOutcome phraseDirect(Run run, String text, String source, List<ChatMessage> history, List<DirectEnv.Call> calls, DirectCommands.Outcome template) {
        List<Jarvis.LlmChoice> choices = j.llmChoices();
        if (choices.isEmpty() || !choices.get(0).hasKey || choices.get(0).model.isEmpty()) return null;
        j.setAgentState(AState.THINKING, "");
        AgentOutcome out = null;
        for (int i = 0; i < choices.size(); i++) {
            Jarvis.LlmChoice ch = choices.get(i);
            if (i > 0 && !ch.hasKey) continue;
            run.model = ch.model;
            AgentRunSpec spec = new AgentRunSpec();
            spec.runId = run.id; spec.config = ch.config; spec.model = ch.model; spec.tools = j.tools; spec.journal = j.journal;
            spec.maxSteps = 3;
            spec.contextChars = j.contextChars(ch.preset.id, ch.model);
            spec.temperature = j.catalog.caps(ch.preset.id, ch.model).temperature == Cap.NO ? null : 0.8;
            spec.maxTokens = 400;
            spec.reasoning = "off";
            spec.realtime = true;
            Prompts.Info info = promptInfo();
            info.hint = "Команда пользователя УЖЕ выполнена инструментами (вызовы и результаты выше). Ответь коротко и естественно, в одну-две фразы, по реальному результату: "
                + "если ok:true — подтверди по-человечески, своими словами; если ok:false — честно назови причину и что можно сделать. Не вызывай те же инструменты повторно.";
            spec.systemPrompt = Prompts.system(info);
            spec.userContext = Prompts.volatileContext(info);
            configureRouting(spec, ch, run);
            spec.history = history;
            spec.user = ChatMessage.user(text);
            noteLlm(run, ch, spec);
            for (DirectEnv.Call c : calls) {
                spec.prefill.add(ChatMessage.assistant("", java.util.Collections.singletonList(c.call)));
                spec.prefill.add(ChatMessage.tool(c.call.id, c.call.name, c.result.toModelText(8000)));
            }
            applyToolGroups(spec, run);
            run.resetForAttempt();
            out = new AgentLoop(j.llm).run(spec, new PhraseListener(run), run.cancel);
            boolean retry = out.status == AgentOutcome.Status.ERROR && run.streamedChars == 0 && out.error != null && !out.error.partial;
            if (!retry || i == choices.size() - 1 || run.cancel.isCancelled()) break;
        }
        run.streamer.flush();
        if (out != null && out.status == AgentOutcome.Status.ERROR && run.streamedChars == 0) {
            j.journal.log(Journal.Cat.AGENT, Journal.Level.WARN, "Ответ модели на прямую команду не получен (" + out.errorText + "): использую шаблон", run.id, null, -1);
            return null;
        }
        return out;
    }

    private AgentOutcome execute(final Run run, Jarvis.LlmChoice ch, String text, List<ChatItem.Attachment> atts, String source) {
        AgentRunSpec spec = new AgentRunSpec();
        spec.runId = run.id;
        spec.config = ch.config;
        spec.model = ch.model;
        spec.tools = j.tools;
        spec.journal = j.journal;
        ModelCaps caps = j.catalog.caps(ch.preset.id, ch.model);
        int steps = j.settings.getInt(Settings.LLM_MAX_STEPS, 10);
        if (StepLimit.unlimited(steps)) j.journal.log(Journal.Cat.AGENT, Journal.Level.INFO, "Шаги агента без ограничения: жёсткий предел " + StepLimit.UNLIMITED_CAP, run.id, null, -1);
        spec.maxSteps = StepLimit.effective(steps);
        spec.contextChars = j.contextChars(ch.preset.id, ch.model);
        float temp = j.settings.getFloat(Settings.LLM_TEMPERATURE, -1f);
        boolean sendTemperature = caps.temperature != Cap.NO && !caps.rejectedParams.contains("temperature");
        spec.temperature = temp < 0 || !sendTemperature ? null : (double) temp;
        int mt = j.settings.getInt(Settings.LLM_MAX_TOKENS, 4000);
        if (mt > 0 && caps.maxOutputTokens > 0) mt = Math.min(mt, caps.maxOutputTokens);
        spec.maxTokens = mt > 0 ? mt : null;
        String reasoning = j.settings.get(Settings.LLM_REASONING, "low");
        if (caps.reasoningLevel(reasoning) == Cap.NO) {
            j.journal.log(Journal.Cat.LLM, Journal.Level.DEBUG, "Уровень рассуждений «" + reasoning + "» не поддерживается моделью " + ch.model + ": не отправляю", run.id, null, -1);
            reasoning = "";
        }
        spec.reasoning = reasoning;
        spec.realtime = true;
        Prompts.Info pinfo = promptInfo();
        pinfo.reminder = Prompts.reminderDue(j.chat.userTurns(run.conv), j.settings.getInt(REMINDER_EVERY, REMINDER_DEFAULT));
        if (!"voice".equals(source) && j.settings.getBool(SKILLS_SUGGEST, true)) pinfo.hint = skillHint.takeHint(System.currentTimeMillis());
        Triggers.Trigger phrase = triggers().match(text);
        if (phrase != null && !phrase.direct()) {
            String hint = "сработал триггер «" + phrase.phrases.get(0) + "»: " + phrase.instruction;
            pinfo.hint = pinfo.hint == null || pinfo.hint.isEmpty() ? hint : pinfo.hint + "; " + hint;
        }
        spec.systemPrompt = Prompts.system(pinfo);
        spec.userContext = Prompts.volatileContext(pinfo);
        spec.localFinish = ConfirmPolicy.useFast(ConfirmPolicy.Mode.parse(j.settings.get("direct.confirm", "auto")), book, ch.preset.id);
        configureRouting(spec, ch, run);
        // history WITHOUT the message we just stored (it is passed as spec.user), kept consistent by ChatStore.history()
        List<ChatMessage> hist = j.chat.history(run.conv, spec.contextChars - 4000);
        if (!hist.isEmpty() && ChatMessage.USER.equals(hist.get(hist.size() - 1).role)) hist.remove(hist.size() - 1);
        spec.history = hist;
        StringBuilder ut = new StringBuilder(text);
        for (ChatItem.Attachment a : atts) ut.append("\n[Вложение: ").append(a.path).append(" — ").append(a.mime).append(", ").append(app.jarvis.data.ChatStore.humanSize(a.size))
            .append(a.durationText.isEmpty() ? "" : ", " + a.durationText).append("]");
        spec.user = ChatMessage.user(ut.toString());
        applyToolGroups(spec, run);
        noteLlm(run, ch, spec);
        run.resetForAttempt();
        return new AgentLoop(j.llm).run(spec, new Listener(run), run.cancel);
    }

    /** Setting {@code tools.groups} (default on): announce core tools + the groups a request needs instead of every tool. Off = every tool, as before. */
    private boolean toolGroupsOn() { return j.settings.getBool(TOOLS_GROUPS, true); }

    private int toolsFullEvery() { return j.settings.getInt(TOOLS_FULL_EVERY, TOOLS_FULL_DEFAULT); }

    private boolean musicActive() {
        MusicOut m = j.music;
        return m != null && (m.info().state == MusicOut.State.PLAYING || m.info().state == MusicOut.State.PAUSED);
    }

    /**
     * Chooses which tool groups this request announces (words of the message, tools used in the last turns, the player) and records
     * the choice in the journal and the trace. The model can switch more groups on with tools_enable; a call of a hidden tool still runs.
     */
    private void applyToolGroups(AgentRunSpec spec, Run run) {
        if (!toolGroupsOn() || spec.tools == null) return;
        ToolRouter.Result routed = ToolRouter.choose(spec.user == null ? "" : spec.user.content, spec.history, spec.prefill, musicActive(),
            j.chat.userTurns(run.conv), toolsFullEvery());
        spec.toolSelection = new ToolSelection(spec.tools, routed.groups());
        int shown = spec.toolSelection.advertisedCount();
        run.trace.put("llm", "tool_specs", shown);
        run.trace.put("llm", "tool_groups", routed.isFull() ? "all" : routed.groups().toString());
        j.journal.log(Journal.Cat.AGENT, Journal.Level.INFO, "Инструменты: " + shown + " из " + spec.tools.names().size() + ", группы: " + routed, run.id, null, -1);
    }

    /** True when the NEXT user request of this session is one that carries every tool (setting tools.fullEvery). */
    private boolean fullListNext(long conv) { return toolGroupsOn() && ToolRouter.fullListDue(j.chat.userTurns(conv) + 1, toolsFullEvery()); }

    /** The tools the NEXT request of this session would announce (before the words of the new message are known): for the prompt report. */
    public List<ToolSpec> nextRequestTools(long conv) {
        if (!toolGroupsOn()) return j.tools.specs();
        int window = Math.max(8000, j.settings.getInt(Settings.LLM_CONTEXT_CHARS, 60000));
        ToolRouter.Result routed = ToolRouter.choose("", j.chat.history(conv, window - 4000), null, musicActive(), j.chat.userTurns(conv) + 1, toolsFullEvery());
        return new ToolSelection(j.tools, routed.groups()).advertise();
    }

    /** Latency statistics for every step and, for the primary route only, a hedged second route (llm.hedgeMs; 0 = off). */
    private void configureRouting(AgentRunSpec spec, final Jarvis.LlmChoice ch, Run run) {
        final String pid = ch.preset.id;
        List<Jarvis.LlmChoice> all = j.llmChoices();
        final Jarvis.LlmChoice backup = all.size() > 1 && all.get(0).preset.id.equals(pid) ? all.get(1) : null;
        final long hedgeMs = j.settings.getInt("llm.hedgeMs", 4000);
        if (backup != null && backup.hasKey && hedgeMs > 0) spec.hedge = new AgentRunSpec.Hedge(backup.config, backup.model, hedgeMs);
        spec.latency = new AgentRunSpec.LatencySink() {
            @Override public void onStep(boolean hedgeWon, long firstTokenMs, long headersMs) {
                if (hedgeWon && backup != null) { book.record("llm:" + pid, hedgeMs); book.record("llm:" + backup.preset.id, firstTokenMs); }
                else { book.record("llm:" + pid, firstTokenMs); if (headersMs >= 0) book.record("llm.headers:" + pid, headersMs); }
            }
        };
    }

    /** Records which model/provider serves the turn and how big the prompt is (characters, not tokens). */
    private static void noteLlm(Run run, Jarvis.LlmChoice ch, AgentRunSpec spec) {
        long chars = spec.systemPrompt == null ? 0 : spec.systemPrompt.length();
        if (spec.history != null) for (ChatMessage m : spec.history) chars += m.content == null ? 0 : m.content.length();
        if (spec.user != null && spec.user.content != null) chars += spec.user.content.length();
        run.trace.put("llm", "provider", ch.preset.id);
        run.trace.put("llm", "model_requested", ch.model);
        run.trace.put("llm", "prompt_chars", chars);
        run.trace.put("llm", "tool_specs", spec.tools == null ? 0 : spec.toolSelection != null ? spec.toolSelection.advertisedCount() : spec.tools.specs().size());
    }

    public Prompts.Info currentPromptInfo() { return promptInfo(); }

    /** Read on every command: the settings page and the trigger_save tool write the same file. */
    Triggers triggers() { return Triggers.load(new File(j.home, TriggerTools.FILE)); }

    /** Size of the next request for this session, by part (settings page «Контекст и промпт», MCP agent_prompt_dump). */
    public PromptReport promptReport(long conv) {
        Prompts.Info info = promptInfo();
        int window = Math.max(8000, j.settings.getInt(Settings.LLM_CONTEXT_CHARS, 60000));
        List<PromptReport.Row> rows = new ArrayList<>(PromptReport.systemRows(info));
        rows.add(PromptReport.row("volatile", "Время, плеер, подсказки", Prompts.volatileContext(info), "в последнем сообщении пользователя"));
        int toolChars = 0, toolTokens = 0;
        List<ToolSpec> specs = nextRequestTools(conv);
        for (ToolSpec s : specs) {
            String t = s.toJson(false).toString();
            toolChars += t.length();
            toolTokens += PromptReport.tokens(t);
        }
        boolean grouped = toolGroupsOn();
        String toolsNote = !grouped ? "схемы для модели: все инструменты в каждом запросе"
            : fullListNext(conv) ? "схемы для модели: этот запрос несёт полный список (каждый " + toolsFullEvery() + "-й)"
            : "схемы для модели: ядро и группы по запросу, остальные подключаются через tools_enable";
        rows.add(PromptReport.sized("tools", "Инструменты (" + specs.size() + (grouped ? " из " + j.tools.names().size() : "") + ")", toolChars, toolTokens, toolsNote));
        List<ChatMessage> all = j.chat.history(conv, Integer.MAX_VALUE);
        List<ChatMessage> kept = j.chat.history(conv, window - 4000);
        int histChars = 0, histTokens = 0;
        for (ChatMessage m : kept) {
            String c = m.content == null ? "" : m.content;
            histChars += c.length();
            histTokens += PromptReport.tokens(c);
        }
        rows.add(PromptReport.sized("history", "История (" + kept.size() + " сообщ.)", histChars, histTokens, "обрезается по бюджету контекста"));
        return PromptReport.of(rows, window, kept.size(), all.size() - kept.size());
    }

    private Prompts.Info promptInfo() {
        Prompts.Info i = new Prompts.Info();
        i.toolGroups = toolGroupsOn();
        i.betaReports = j.settings.getBool("agent.betaReports", true);
        i.userPrompt = j.settings.get("prompt.userExtra", "");
        i.nowText = new SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", new Locale("ru")).format(new Date());
        MemoryStore.PromptParts mem = j.memory.promptParts(j.memory.budget());
        i.memory = mem.facts;
        i.memoryStyle = mem.style;
        i.skills = j.skills.systemBlock(SKILL_INDEX_CHARS);
        MusicOut m = j.music;
        if (m != null) {
            MusicOut.Info mi = m.info();
            i.player = mi.state == MusicOut.State.PLAYING ? "играет «" + mi.title + "»" : mi.state == MusicOut.State.PAUSED ? "пауза: «" + mi.title + "»" : "не играет";
        }
        i.device = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + ", Android " + android.os.Build.VERSION.RELEASE;
        String call = j.userCall();
        StringBuilder ex = new StringBuilder();
        if (!call.isEmpty()) ex.append("Пользователь любит, когда его называют «").append(call).append("».\n");
        ex.append("Если пользователь ссылается на прошлый разговор — сначала session_search.\n");
        if (j.settings.getInt("agent.commentEvery", 0) > 0)
            ex.append("Промежуточные комментарии создаёт приложение; не дублируй их при ответе.\n");
        String favorite = j.settings.get(Settings.MUSIC_FAVORITE);
        ex.append("Любимая песня пользователя: ").append(favorite.isEmpty() ? "не выбрана (используй music_set_favorite, когда пользователь скажет, какая)" : favorite).append(".\n");
        if (j.settings.get("tts.chain").startsWith("elevenlabs") && j.settings.get("tts.elevenlabs.model", "eleven_flash_v2_5").startsWith("eleven_v"))
            ex.append("Озвучка ElevenLabs v3/v4 понимает теги эмоций в квадратных скобках по-английски: не больше одного на фразу, например [whispers] или [sighs]; используй редко.\n");
        String mcpLine = j.mcp.promptLine();
        if (!mcpLine.isEmpty()) ex.append(mcpLine).append('\n');
        String extraWeb = ExtraSearch.agentSummary(j.settings::get, j.secrets::get);
        if (!extraWeb.isEmpty()) ex.append(extraWeb).append('\n');
        i.extra = ex.toString();
        return i;
    }

    private void warmUpSpeech() {
        Speaker sp = j.speaker;
        if (sp instanceof SpeechService) ((SpeechService) sp).warmUp();
    }

    private boolean shouldSpeak(boolean voiceSource) {
        String mode = j.settings.get("tts.auto", "voice");
        return mode.equals("always") || (mode.equals("voice") && voiceSource);
    }

    /** A progress phrase is a separate assistant message; it never pretends to be a model or tool result. */
    private void emitAgentComment(Run run, String text, boolean voice) {
        if (run.cancel.isCancelled() || run.finalSeen || text == null || text.isEmpty()) return;
        ChatItem c = new ChatItem();
        c.convId = run.conv; c.kind = ChatItem.Kind.ASSISTANT; c.runId = run.id;
        c.model = "progress"; c.text = text;
        j.chat.add(c);
        j.fireChat(ChatEvent.Type.ADDED, c, null, run.id);
        if (voice && run.speak) j.speaker.speak(text);
    }

    // ---- listener: persistence + UI + speech --------------------------------------------------------------------
    private final class Listener implements AgentListener {
        final Run run;
        final StringBuilder stream = new StringBuilder();
        final Map<String, ChatItem> toolItems = new HashMap<>();
        final Map<String, Integer> toolSpans = new HashMap<>();
        long stepNanos;
        Listener(Run r) { run = r; }

        @Override public void onStep(int step) {
            stepNanos = run.trace.now();
            run.trace.mark(TurnTrace.LLM_REQUEST);
            run.trace.add("llm", "calls", 1);
            stream.setLength(0);
            if (step > 1) j.setAgentState(AState.THINKING, "");
        }

        @Override public void onTextDelta(int step, String delta) {
            run.trace.mark(TurnTrace.LLM_FIRST_TEXT);
            if (run.ack != null && stream.length() == 0 && run.streamedChars == 0) run.ack.onRealText(run.ackToken);
            stream.append(delta);
            run.streamedChars += delta.length();
            if (run.completedActions > 0 && !delta.trim().isEmpty()) {
                run.finalSeen = true;
                if (run.pendingComment != null) run.pendingComment.cancel(false);
            }
            j.fireChat(ChatEvent.Type.STREAM, null, stream.toString(), run.id);
            if (run.speak) run.streamer.feed(delta);
        }

        @Override public void onRetry(String reason, long delayMs) { j.toast("Повтор запроса: " + reason, 0); }

        @Override public void onMessage(ChatMessage m) {
            if (ChatMessage.ASSISTANT.equals(m.role)) {
                if (!m.hasToolCalls() && m.content != null && !m.content.trim().isEmpty()) {
                    run.finalSeen = true;
                    if (run.pendingComment != null) run.pendingComment.cancel(false);
                }
                ChatItem a = new ChatItem();
                a.convId = run.conv; a.kind = ChatItem.Kind.ASSISTANT; a.text = m.content; a.runId = run.id; a.model = run.model;
                if (m.hasToolCalls()) { JSONArray arr = new JSONArray(); for (ToolCall c : m.toolCalls) arr.put(new ToolCall(c.id, c.name, AdbTools.redactArgs(c.name, c.arguments)).toJson()); a.toolCallsJson = arr.toString(); }
                j.chat.add(a);
                j.fireChat(ChatEvent.Type.STREAM_END, null, null, run.id);
                j.fireChat(ChatEvent.Type.ADDED, a, null, run.id);
                stream.setLength(0);
            } else if (ChatMessage.TOOL.equals(m.role)) {
                ChatItem t = toolItems.get(m.toolCallId);
                if (t != null) { t.text = m.content; j.chat.update(t); }
            }
        }

        @Override public void onToolStart(ToolCall call) {
            run.toolCount++;
            boolean ackPlayed = run.ack != null && !run.ackBefore.equals(run.ack.lastPhrase());
            if (!run.preActionSent && AgentCommentPolicy.preAction(
                    j.settings.getBool("agent.preAction", true), run.toolCount, run.streamedChars,
                    run.cancel.isCancelled(), ackPlayed)) {
                run.preActionSent = true;
                if (run.ack != null) run.ack.onRealText(run.ackToken);
                emitAgentComment(run, AgentCommentPolicy.preActionPhrase(call.name,
                    j.settings.get("agent.preActionPhrases", "")), j.settings.getBool("agent.preActionVoice", true));
            }
            toolSpans.put(call.id, run.trace.toolStart(call.name));
            j.setAgentState(AState.TOOL, call.name);
            ChatItem t = new ChatItem();
            t.convId = run.conv; t.kind = ChatItem.Kind.TOOL; t.runId = run.id; t.callId = call.id; t.toolName = call.name; t.toolArgs = AdbTools.redactArgs(call.name, call.arguments);
            t.toolStatus = ChatItem.ToolStatus.RUNNING;
            j.chat.add(t);
            toolItems.put(call.id, t);
            j.fireChat(ChatEvent.Type.ADDED, t, null, run.id);
        }

        @Override public void onToolEnd(ToolCall call, ToolResult r, long ms) {
            if (!r.ok) run.toolFailed = true;
            else if (run.toolFailed) run.recovered = true;
            Integer span = toolSpans.get(call.id);
            if (span != null) run.trace.toolEnd(span, r.ok);
            ChatItem t = toolItems.get(call.id);
            if (t == null) return;
            t.ok = r.ok; t.durationMs = ms; t.summary = r.ok ? r.summary : r.error;
            t.toolStatus = r.ok ? ChatItem.ToolStatus.DONE : ChatItem.ToolStatus.FAILED;
            if (t.text.isEmpty()) t.text = r.toModelText(8000);
            j.chat.update(t);
            j.fireChat(ChatEvent.Type.UPDATED, t, null, run.id);
            j.lastAction(describe(call, r), r.ok);
            run.completedActions++;
            final int every = j.settings.getInt("agent.commentEvery", 0);
            if (AgentCommentPolicy.due(run.completedActions, every, run.cancel.isCancelled(), run.finalSeen)) {
                if (run.pendingComment != null) run.pendingComment.cancel(false);
                final String phrase = AgentCommentPolicy.progress(call.name, r.ok);
                run.pendingComment = sch.schedule(new Runnable() {
                    @Override public void run() {
                        if (!run.cancel.isCancelled() && !run.finalSeen)
                            emitAgentComment(run, phrase, j.settings.getBool("agent.commentVoice", false));
                    }
                }, 650, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        }

        @Override public void onUsage(int step, LlmResult r) {
            TurnTrace tr = run.trace;
            if (r.firstDeltaMs >= 0) tr.markAt(TurnTrace.LLM_FIRST_TOKEN, stepNanos + r.firstDeltaMs * 1_000_000L);
            tr.markLast(TurnTrace.LLM_DONE);
            if (!r.model.isEmpty()) tr.put("llm", "model", r.model);
            if (r.promptTokens >= 0) tr.add("llm", "tokens_in", r.promptTokens);
            if (r.completionTokens >= 0) tr.add("llm", "tokens_out", r.completionTokens);
            if (r.cachedTokens >= 0) tr.add("llm", "tokens_cached", r.cachedTokens);
            if (step == 1 && r.headersMs >= 0) tr.put("llm", "headers_ms", r.headersMs);
            if (step == 1 && r.firstDeltaMs >= 0) j.journal.log(Journal.Cat.PERFORMANCE, Journal.Level.INFO, "LLM: до первого фрагмента " + r.firstDeltaMs + " мс, заголовки " + r.headersMs + " мс", run.id, null, r.totalMs);
        }
    }

    /** Same persistence/streaming/speech as a normal run; the direct tool items were already stored by DirectEnv. */
    private final class PhraseListener implements AgentListener {
        private final Listener inner;
        PhraseListener(Run r) { inner = new Listener(r); }
        @Override public void onStep(int step) { inner.onStep(step); }
        @Override public void onTextDelta(int step, String d) { inner.onTextDelta(step, d); }
        @Override public void onRetry(String reason, long delayMs) { inner.onRetry(reason, delayMs); }
        @Override public void onMessage(ChatMessage m) { inner.onMessage(m); }
        @Override public void onToolStart(ToolCall c) { inner.onToolStart(c); }
        @Override public void onToolEnd(ToolCall c, ToolResult r, long ms) { inner.onToolEnd(c, r, ms); }
        @Override public void onUsage(int step, LlmResult r) { inner.onUsage(step, r); }
    }

    static String describe(ToolCall c, ToolResult r) {
        String s = r.ok ? r.summary : r.error;
        return c.name + ": " + (s == null || s.isEmpty() ? (r.ok ? "выполнено" : "ошибка") : s);
    }

    // ---- finishing --------------------------------------------------------------------------------------------------
    private void finishRun(Run run, AgentOutcome out, Jarvis.LlmChoice used, boolean voiceSource, long t0) {
        run.finalSeen = true;
        if (run.pendingComment != null) run.pendingComment.cancel(false);
        j.fireChat(ChatEvent.Type.STREAM_END, null, null, run.id);
        String status = out == null ? "ERROR" : out.status.name();
        String err = out == null ? "" : (out.errorText == null ? "" : out.errorText);
        if (out != null && !voiceSource && j.settings.getBool(SKILLS_SUGGEST, true)) skillHint.noteRun(out.toolCalls, run.recovered, System.currentTimeMillis());
        j.chat.runFinished(run.id, status, out == null ? 0 : out.steps, out == null ? 0 : out.toolCalls, out == null ? -1 : out.firstTextMs, ms(t0), err);
        j.setAgentState(AState.IDLE, "");
        if (out == null) { failRun(run, voiceSource, "Не удалось выполнить запрос", AgentOutcome.Status.ERROR, null, t0); return; }
        j.journal.log(Journal.Cat.AGENT, out.ok() ? Journal.Level.INFO : Journal.Level.WARN, "Запуск завершён: " + status + ", шагов " + out.steps + ", инструментов " + out.toolCalls
            + (used != null ? ", " + used.label() : ""), run.id, J.put(J.put(new JSONObject(), "first_text_ms", out.firstTextMs), "total_ms", out.totalMs), out.totalMs);
        j.journal.log(Journal.Cat.PERFORMANCE, Journal.Level.INFO, "Команда → первый текст: " + out.firstTextMs + " мс, весь запуск: " + out.totalMs + " мс", run.id, null, out.totalMs);
        switch (out.status) {
            case DONE:
                if (!out.finalText.trim().isEmpty()) j.lastAction(j.lastActionText().isEmpty() || run.toolCount == 0 ? short80(out.finalText) : j.lastActionText(), true);
                j.flash(AState.DONE, "");
                break;
            case CANCELLED:
                j.speaker.stop();
                j.flash(AState.CANCELLED, "");
                break;
            default: {
                String msg = out.errorText.isEmpty() ? "Ошибка" : out.errorText;
                ChatItem e = new ChatItem();
                e.convId = run.conv; e.kind = ChatItem.Kind.ERROR; e.text = msg; e.runId = run.id;
                if (out.error != null) e.toolArgs = out.error.technical;
                j.chat.add(e);
                j.fireChat(ChatEvent.Type.ADDED, e, null, run.id);
                j.lastAction(msg, false);
                if (voiceSource) j.speaker.speak("Не получилось. " + shortSpoken(msg));
                j.flash(AState.ERROR, msg);
                if (out.error != null && out.error.kind == LlmException.Kind.GEO_BLOCK) j.toast("Провайдер недоступен из вашей сети. Включите VPN или добавьте ключ OpenRouter.", 2);
            }
        }
    }

    private void failRun(Run run, boolean voiceSource, String msg, AgentOutcome.Status st, String navigate, long t0) {
        ChatItem e = new ChatItem();
        e.convId = run.conv; e.kind = ChatItem.Kind.ERROR; e.text = msg; e.runId = run.id;
        j.chat.add(e);
        j.fireChat(ChatEvent.Type.ADDED, e, null, run.id);
        j.lastAction(msg, false);
        j.setAgentState(AState.IDLE, "");
        j.flash(AState.ERROR, msg);
        if (voiceSource) j.speaker.speak(shortSpoken(msg));
        if (navigate != null) j.navigate(navigate);
        j.chat.runFinished(run.id, "ERROR", 0, 0, -1, ms(t0), msg);
    }

    private static String shortSpoken(String m) { String s = m.replaceAll("\\s+", " "); return s.length() > 160 ? s.substring(0, 160) : s; }
    private static String short80(String s) { String t = s.replaceAll("\\s+", " ").trim(); return t.length() > 90 ? t.substring(0, 90) + "…" : t; }
    private static long ms(long t0) { return (System.nanoTime() - t0) / 1_000_000L; }

    // ---- per-run state ------------------------------------------------------------------------------------------------
    final class Run {
        final String id;
        final long conv;
        final CancelToken cancel;
        final boolean speak;
        final TurnTrace trace;
        String model = "";
        int toolCount;
        volatile int completedActions;
        boolean preActionSent;
        volatile boolean finalSeen;
        boolean toolFailed, recovered;
        String ackBefore = "";
        java.util.concurrent.ScheduledFuture<?> pendingComment;
        long streamedChars;
        SpeechStreamer streamer;
        AckPolicy ack;
        int ackToken;

        Run(String id, long conv, CancelToken cancel, boolean speak, TurnTrace trace) {
            this.id = id; this.conv = conv; this.cancel = cancel; this.speak = speak; this.trace = trace;
            resetForAttempt();
        }

        void resetForAttempt() {
            streamedChars = 0;
            streamer = new SpeechStreamer(new SpeechStreamer.Out() { @Override public void piece(String t) { j.speaker.speak(t); } })
                .firstClauseCut(j.settings.getBool("tts.firstClause", true));
        }
    }

    // ---- direct commands environment ----------------------------------------------------------------------------------
    private final class DirectEnv implements DirectCommands.Env {
        final class Call { final ToolCall call; final ToolResult result; Call(ToolCall c, ToolResult r) { call = c; result = r; } }
        final Run run;
        final List<Call> calls = new ArrayList<>();
        int n;
        DirectEnv(Run r) { run = r; }

        @Override public ToolResult tool(String name, JSONObject args) {
            ToolCall call = new ToolCall("direct_" + run.id + "_" + (++n), name, args.toString());
            JSONArray arr = new JSONArray();
            arr.put(call.toJson());
            ChatItem a = new ChatItem();
            a.convId = run.conv; a.kind = ChatItem.Kind.ASSISTANT; a.runId = run.id; a.model = "direct"; a.toolCallsJson = arr.toString();
            j.chat.add(a);
            ChatItem t = new ChatItem();
            t.convId = run.conv; t.kind = ChatItem.Kind.TOOL; t.runId = run.id; t.callId = call.id; t.toolName = name; t.toolArgs = call.arguments;
            t.toolStatus = ChatItem.ToolStatus.RUNNING;
            j.chat.add(t);
            j.fireChat(ChatEvent.Type.ADDED, t, null, run.id);
            run.toolCount++;
            j.setAgentState(AState.TOOL, name);
            long t0 = System.nanoTime();
            int span = run.trace.toolStart(name);
            ToolResult r = j.tools.execute(call, run.id, run.cancel, j.journal, null);
            run.trace.toolEnd(span, r.ok);
            t.ok = r.ok; t.durationMs = ms(t0); t.summary = r.ok ? r.summary : r.error; t.text = r.toModelText(8000);
            t.toolStatus = r.ok ? ChatItem.ToolStatus.DONE : ChatItem.ToolStatus.FAILED;
            j.chat.update(t);
            j.fireChat(ChatEvent.Type.UPDATED, t, null, run.id);
            calls.add(new Call(call, r));
            j.lastAction(describe(call, r), r.ok);
            return r;
        }

        /** Session the command moved the user to (0 = stays); the reply is stored there. */
        long switchedTo;

        @Override public String session(DirectCommands.SessionCommand c) {
            if (c == DirectCommands.SessionCommand.NEW_CHAT) { switchedTo = j.sessions.newChat(); return "Открыл новый чат."; }
            j.sessions.clearContext(run.conv);
            return "Контекст очищен.";
        }

        @Override public Triggers.Trigger trigger(String raw) { return triggers().match(raw); }

        @Override public String userCall() { return j.userCall(); }
        @Override public String favoritePath() { return j.settings.get(Settings.MUSIC_FAVORITE); }
        @Override public void navigate(String target) { j.navigate(target); }
        @Override public String statusText() { return j.statusText(); }
        @Override public boolean musicPaused() { MusicOut m = j.music; return m != null && m.info().state == MusicOut.State.PAUSED; }
        @Override public boolean musicActive() { MusicOut m = j.music; return m != null && (m.info().state == MusicOut.State.PLAYING || m.info().state == MusicOut.State.PAUSED); }
    }
}
