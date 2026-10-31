package app.jarvis.core.agent;

import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.HedgedChat;
import app.jarvis.core.llm.LlmException;
import app.jarvis.core.llm.LlmListener;
import app.jarvis.core.llm.LlmRequest;
import app.jarvis.core.llm.LlmResult;
import app.jarvis.core.llm.OpenAiClient;
import app.jarvis.core.llm.ToolCall;
import app.jarvis.core.log.Journal;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.J;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import org.json.JSONObject;

/**
 * command → model request → tool call → tool → result → next step → final answer.
 * Invariants: every assistant tool_call gets exactly one tool message (also on cancel/timeout/error);
 * success is only ever reported by a tool, never invented here.
 */
public final class AgentLoop {
    private final OpenAiClient client;

    public AgentLoop(OpenAiClient client) { this.client = client; }

    /** Asked once when a step ends without any text (typical right after a tool call): a blank bubble is never an answer. */
    static final String NUDGE_AFTER_TOOLS = "[Системное напоминание] Инструменты уже выполнены, их результаты выше. Сообщи пользователю итог одним-двумя короткими предложениями по реальным результатам.";
    static final String NUDGE_PLAIN = "[Системное напоминание] Ответ был пустым. Ответь пользователю коротко.";

    /** Callers of one step's independent tool calls (the registry runs each tool on its own pool; this only provides waiting threads). */
    private static final ExecutorService BATCH = Executors.newCachedThreadPool(new ThreadFactory() {
        int n;
        @Override public synchronized Thread newThread(Runnable r) { Thread t = new Thread(r, "jv-batch-" + (++n)); t.setDaemon(true); return t; }
    });

    /** Only side-effect-free tools may run concurrently with their neighbours; everything else keeps the model's order. */
    private static boolean parallelSafe(AgentRunSpec spec, ToolCall call) {
        if (spec.tools == null) return false;
        Tool t = spec.tools.get(call.name);
        return t != null && !t.sideEffects();
    }

    /** Joined hints when EVERY tool call of the step has one (null entry = no hint); null otherwise. "" means "say nothing". */
    static String spokenSummary(List<String> hints) {
        if (hints.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String h : hints) {
            if (h == null) return null;
            String t = h.trim();
            if (t.isEmpty() || sb.indexOf(t) >= 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
        }
        return sb.toString();
    }

    private static String clip(String s, int max) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    public AgentOutcome run(final AgentRunSpec spec, AgentListener listener, final CancelToken cancel) {
        final AgentListener lst = listener == null ? AgentListener.NOP : listener;
        final Journal jr = spec.journal == null ? Journal.NOP : spec.journal;
        final long t0 = System.nanoTime();
        final AgentOutcome out = new AgentOutcome();
        final List<ChatMessage> msgs = new ArrayList<>();
        if (spec.systemPrompt != null && !spec.systemPrompt.isEmpty()) msgs.add(ChatMessage.system(spec.systemPrompt));
        msgs.addAll(ContextBudget.repairPairing(spec.history));
        final ChatMessage reqUser = spec.userContext == null || spec.userContext.isEmpty() ? spec.user
            : ChatMessage.user(spec.user.content + "\n\n" + spec.userContext);
        msgs.add(reqUser);
        if (spec.prefill != null) msgs.addAll(spec.prefill);
        lst.onRunStart(spec.runId);
        String lastSig = "";
        int repeats = 0;
        boolean contextRetried = false;
        boolean nudged = false;
        ToolResult lastResult = null;
        boolean finished = false;
        try {
            for (int step = 1; step <= spec.maxSteps; step++) {
                if (cancel.isCancelled()) { out.status = AgentOutcome.Status.CANCELLED; finished = true; break; }
                out.steps = step;
                lst.onStep(step);
                LlmRequest req = new LlmRequest();
                req.model = spec.model;
                req.messages = ContextBudget.trim(msgs, spec.contextChars);
                req.tools = spec.tools == null ? new ArrayList<app.jarvis.core.llm.ToolSpec>()
                    : spec.toolSelection == null ? spec.tools.specs() : spec.toolSelection.advertise();
                req.temperature = spec.temperature;
                req.maxTokens = spec.maxTokens;
                req.reasoning = spec.reasoning;
                req.realtime = spec.realtime;
                for (int mi = 0; mi < req.messages.size(); mi++) if (req.messages.get(mi) == reqUser) { req.cacheBreakpoint = mi - 1; break; }
                final int st = step;
                LlmResult r;
                HedgedChat.Info hedgeInfo = null;
                try {
                    LlmListener ll = new LlmListener() {
                        @Override public void onTextDelta(String d) {
                            if (out.firstTextMs < 0) out.firstTextMs = (System.nanoTime() - t0) / 1_000_000L;
                            lst.onTextDelta(st, d);
                        }
                        @Override public void onReasoningDelta(String d) { lst.onReasoningDelta(st, d); }
                        @Override public void onRetry(int attempt, String reason, long delayMs) { lst.onRetry(reason, delayMs); }
                    };
                    if (spec.hedge != null && spec.hedge.afterMs > 0 && step == 1 && out.toolCalls == 0) {
                        hedgeInfo = new HedgedChat.Info();
                        r = HedgedChat.chat(client, spec.config, req, spec.hedge.config, req.withModel(spec.hedge.model), spec.hedge.afterMs, ll, cancel, hedgeInfo);
                        if (hedgeInfo.backupStarted) jr.log(Journal.Cat.LLM, Journal.Level.INFO, hedgeInfo.backupWon
                            ? "Основной маршрут не ответил за " + spec.hedge.afterMs + " мс, ответил запасной (" + spec.hedge.model + ")"
                            : "Запасной маршрут запускался параллельно, но основной ответил первым", spec.runId, null, -1);
                    } else {
                        r = client.chat(spec.config, req, ll, cancel);
                    }
                } catch (LlmException e) {
                    if (e.kind == LlmException.Kind.CONTEXT_LENGTH && !contextRetried) {
                        contextRetried = true;
                        spec.contextChars = Math.max(4000, spec.contextChars / 2);
                        jr.log(Journal.Cat.AGENT, Journal.Level.WARN, "Контекст слишком большой, сжимаю до " + spec.contextChars + " символов", spec.runId, null, -1);
                        step--;
                        continue;
                    }
                    if (e.emptyResponse) {
                        // nothing came back at all: handled below exactly like a blank answer (nudge once, then fallback or error)
                        r = new LlmResult();
                        r.model = spec.model;
                        r.finishReason = "stop";
                        r.warning = "empty provider response";
                    } else {
                        finished = true;
                        if (e.kind == LlmException.Kind.CANCELLED) { out.status = AgentOutcome.Status.CANCELLED; break; }
                        out.status = AgentOutcome.Status.ERROR;
                        out.error = e;
                        out.errorText = e.userMessage;
                        jr.e(Journal.Cat.LLM, "LLM: " + e.userMessage, e, spec.runId, J.put(new JSONObject(), "technical", e.technical));
                        break;
                    }
                }
                lst.onUsage(step, r);
                if (spec.latency != null && r.firstDeltaMs >= 0) spec.latency.onStep(hedgeInfo != null && hedgeInfo.backupWon, r.firstDeltaMs, r.headersMs);
                JSONObject d = new JSONObject();
                J.put(d, "step", step); J.put(d, "model", r.model); J.put(d, "finish", r.finishReason);
                J.put(d, "headers_ms", r.headersMs); J.put(d, "first_delta_ms", r.firstDeltaMs);
                J.put(d, "prompt_tokens", r.promptTokens); J.put(d, "completion_tokens", r.completionTokens);
                J.put(d, "reasoning_tokens", r.reasoningTokens); J.put(d, "tool_calls", r.toolCalls.size());
                if (!r.warning.isEmpty()) J.put(d, "warning", r.warning);
                jr.log(Journal.Cat.LLM, Journal.Level.INFO, "Ответ модели (шаг " + step + ")", spec.runId, d, r.totalMs);

                if (r.toolCalls.isEmpty()) {
                    boolean blank = r.content.trim().isEmpty();
                    boolean truncated = "length".equals(r.finishReason);
                    if (blank && !truncated && !nudged && step < spec.maxSteps) {
                        // The model ended the turn without text. Ask once for the actual answer instead of leaving a blank bubble.
                        nudged = true;
                        msgs.add(ChatMessage.user(out.toolCalls > 0 ? NUDGE_AFTER_TOOLS : NUDGE_PLAIN));
                        jr.log(Journal.Cat.AGENT, Journal.Level.WARN, "Модель вернула пустой ответ, прошу ответить ещё раз", spec.runId, null, -1);
                        continue;
                    }
                    finished = true;
                    if (!blank) {
                        ChatMessage a = ChatMessage.assistant(r.content, null);
                        msgs.add(a);
                        lst.onMessage(a);
                        out.finalText = r.content;
                        out.status = AgentOutcome.Status.DONE;
                    } else if (!truncated && lastResult != null && lastResult.ok) {
                        // Still nothing, but the last tool really succeeded: say exactly that, in the tool's own words.
                        String fb = lastResult.summary.isEmpty() ? "Готово." : "Готово: " + clip(lastResult.summary, 140);
                        lst.onTextDelta(step, fb);
                        ChatMessage a = ChatMessage.assistant(fb, null);
                        msgs.add(a);
                        lst.onMessage(a);
                        out.finalText = fb;
                        out.status = AgentOutcome.Status.DONE;
                        jr.log(Journal.Cat.AGENT, Journal.Level.WARN, "Модель так и не дала текста; ответ собран из результата инструмента", spec.runId, null, -1);
                    } else {
                        out.finalText = "";
                        out.status = AgentOutcome.Status.ERROR;
                        out.errorText = truncated
                            ? "Ответ модели обрезан по лимиту токенов ещё до появления текста. Увеличьте лимит ответа или понизьте уровень рассуждений."
                            : lastResult != null && !lastResult.ok
                                ? "Модель не ответила, а последний инструмент вернул ошибку: " + clip(lastResult.error, 160)
                                : "Модель не вернула текста.";
                    }
                    break;
                }

                ChatMessage a = ChatMessage.assistant(r.content, r.toolCalls, r.reasoning);
                msgs.add(a);
                lst.onMessage(a);
                // Consecutive side-effect-free calls of one step run concurrently; a call with side effects always runs alone,
                // in the model's order. Results are appended to the conversation in call order either way.
                final List<ToolCall> calls = r.toolCalls;
                final List<String> stepHints = new ArrayList<>();
                int idx = 0;
                while (idx < calls.size()) {
                    int end = idx + 1;
                    if (parallelSafe(spec, calls.get(idx))) while (end < calls.size() && parallelSafe(spec, calls.get(end))) end++;
                    final List<ToolCall> batch = new ArrayList<>(calls.subList(idx, end));
                    final int n = batch.size();
                    final boolean[] blocked = new boolean[n];
                    for (int b = 0; b < n; b++) {
                        ToolCall c = batch.get(b);
                        String sig = c.name + "|" + c.arguments;
                        if (sig.equals(lastSig)) repeats++; else { repeats = 0; lastSig = sig; }
                        blocked[b] = repeats >= 2;
                    }
                    final ToolResult[] results = new ToolResult[n];
                    final ChatMessage[] toolMsgs = new ChatMessage[n];
                    for (ToolCall c : batch) { out.toolCalls++; lst.onToolStart(c); }
                    if (n == 1) {
                        runCall(spec, batch.get(0), blocked[0], 0, results, toolMsgs, lst, jr, cancel);
                    } else {
                        List<Future<?>> fs = new ArrayList<>();
                        for (int b = 0; b < n; b++) {
                            final int bi = b;
                            fs.add(BATCH.submit(new Runnable() {
                                @Override public void run() { runCall(spec, batch.get(bi), blocked[bi], bi, results, toolMsgs, lst, jr, cancel); }
                            }));
                        }
                        for (Future<?> f : fs) {
                            try { f.get(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); cancel.cancel("interrupted"); break; } catch (ExecutionException ignored) { }
                        }
                    }
                    for (int b = 0; b < n; b++) {
                        // a worker that never finished (interrupt) still owes the model a paired result
                        if (results[b] == null) {
                            results[b] = ToolResult.cancelled();
                            toolMsgs[b] = ChatMessage.tool(batch.get(b).id, batch.get(b).name, results[b].toModelText(spec.maxToolOutputChars));
                            lst.onMessage(toolMsgs[b]);
                            lst.onToolEnd(batch.get(b), results[b], 0);
                        }
                        msgs.add(toolMsgs[b]);
                        lastResult = results[b];
                        stepHints.add(spec.localFinish ? SpokenHints.forTool(batch.get(b).name, batch.get(b).args(), results[b], FastPhrases.global()) : null);
                    }
                    idx = end;
                }
                if (cancel.isCancelled()) { out.status = AgentOutcome.Status.CANCELLED; finished = true; break; }
                String local = spec.localFinish ? spokenSummary(stepHints) : null;
                if (local != null) {
                    // every tool of this step succeeded and says in its own words what it did: no second model round trip
                    if (out.firstTextMs < 0) out.firstTextMs = (System.nanoTime() - t0) / 1_000_000L;
                    if (!local.isEmpty()) {
                        lst.onTextDelta(step, local);
                        ChatMessage la = ChatMessage.assistant(local, null);
                        msgs.add(la);
                        lst.onMessage(la);
                    }
                    out.finalText = local;
                    out.status = AgentOutcome.Status.DONE;
                    finished = true;
                    jr.log(Journal.Cat.AGENT, Journal.Level.INFO, "Ответ собран из результатов инструментов, второй запрос к модели пропущен", spec.runId, null, -1);
                    break;
                }
            }
            if (!finished) {
                out.status = AgentOutcome.Status.MAX_STEPS;
                out.errorText = "Достигнут лимит шагов (" + spec.maxSteps + ")";
            }
        } catch (RuntimeException e) {
            out.status = AgentOutcome.Status.ERROR;
            out.errorText = "Внутренняя ошибка: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            jr.e(Journal.Cat.AGENT, out.errorText, e);
        } finally {
            out.totalMs = (System.nanoTime() - t0) / 1_000_000L;
            lst.onFinish(out);
        }
        return out;
    }

    /** Executes one tool call, pairs it with its tool message and reports it. Safe to call from a worker thread. */
    private void runCall(AgentRunSpec spec, final ToolCall call, boolean blocked, int slot, ToolResult[] results, ChatMessage[] toolMsgs,
                         final AgentListener lst, Journal jr, CancelToken cancel) {
        long tt = System.nanoTime();
        ToolResult tr;
        try {
            if (cancel.isCancelled()) {
                tr = ToolResult.cancelled();
            } else if (blocked) {
                tr = ToolResult.fail("Этот же вызов с теми же аргументами уже выполнялся подряд несколько раз. Не повторяйте его: ответьте пользователю по уже имеющемуся результату.");
            } else if (spec.tools == null) {
                tr = ToolResult.fail("Инструменты недоступны");
            } else if (spec.toolSelection != null && ToolGroups.META_TOOL.equals(call.name)) {
                // not a registry tool: it only changes which tools the next model step is shown
                tr = spec.toolSelection.handleEnable(call.args());
            } else {
                tr = spec.tools.execute(call, spec.runId, cancel, jr, new ToolContext.Progress() {
                    @Override public void progress(String text) { lst.onToolProgress(call, text); }
                });
                // a tool of a hidden group runs all the same (the registry knows every tool); from now on the model is shown its group
                if (spec.toolSelection != null) spec.toolSelection.noteCalled(call.name);
            }
        } catch (RuntimeException e) {
            tr = ToolResult.fail(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
        long dur = (System.nanoTime() - tt) / 1_000_000L;
        ChatMessage tm = ChatMessage.tool(call.id, call.name, tr.toModelText(spec.maxToolOutputChars));
        results[slot] = tr;
        toolMsgs[slot] = tm;
        synchronized (lst) { // listeners are written for one thread; batches report one call at a time
            lst.onMessage(tm);
            lst.onToolEnd(call, tr, dur);
        }
        JSONObject td = new JSONObject();
        J.put(td, "tool", call.name); J.put(td, "ok", tr.ok);
        jr.log(Journal.Cat.TOOLS, tr.ok ? Journal.Level.INFO : Journal.Level.WARN,
            call.name + (tr.ok ? ": " + tr.summary : " — ошибка: " + tr.error), spec.runId, td, dur);
    }
}
