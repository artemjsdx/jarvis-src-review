package app.jarvis.core.live;

import app.jarvis.core.agent.AgentListener;
import app.jarvis.core.agent.AgentLoop;
import app.jarvis.core.agent.AgentOutcome;
import app.jarvis.core.agent.AgentRunSpec;
import app.jarvis.core.agent.Prompts;
import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.LlmConfig;
import app.jarvis.core.llm.LlmException;
import app.jarvis.core.llm.LlmListener;
import app.jarvis.core.llm.LlmRequest;
import app.jarvis.core.llm.LlmResult;
import app.jarvis.core.llm.ModelInfo;
import app.jarvis.core.llm.OpenAiClient;
import app.jarvis.core.llm.Providers;
import app.jarvis.core.llm.ToolCall;
import app.jarvis.core.log.Journal;
import app.jarvis.core.tools.FsTools;
import app.jarvis.core.tools.MemoryStore;
import app.jarvis.core.tools.Sandbox;
import app.jarvis.core.tools.ShellSession;
import app.jarvis.core.tools.ShellTools;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.Redactor;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.json.JSONObject;

/** Manual live check against a real provider: JV_BASE, JV_KEY, JV_MODEL [, JV_QUIRKS=inception, JV_PROXY]. Never prints the key. */
public final class LiveCheck {
    public static void main(String[] a) throws Exception {
        try { run(a); } catch (Throwable t) { System.out.println("LIVECHECK CRASH: " + t); t.printStackTrace(System.out); System.out.flush(); System.exit(1); }
    }

    static void run(String[] a) throws Exception {
        String base = System.getenv("JV_BASE"), key = System.getenv("JV_KEY"), model = System.getenv("JV_MODEL");
        if (base == null || key == null) { System.err.println("need JV_BASE and JV_KEY"); System.exit(2); }
        Redactor.addSecret(key);
        String preset = System.getenv("JV_QUIRKS");
        LlmConfig cfg = preset != null && app.jarvis.core.llm.Presets.get(preset) != null
            ? app.jarvis.core.llm.Presets.get(preset).toConfig(key) : Providers.openAiCompatible("live", base, key);
        cfg.baseUrl = base;
        if (System.getenv("JV_PROXY") != null) cfg.proxy = System.getenv("JV_PROXY");
        cfg.idleTimeoutMs = 90_000;
        final Journal jr = new Journal() {
            @Override public void log(String c, Level l, String m, String r, JSONObject d, long ms) {
                System.out.println("   [" + l + "/" + c + "] " + Redactor.redact(m) + (ms >= 0 ? " (" + ms + " ms)" : ""));
            }
        };
        OpenAiClient client = new OpenAiClient(jr);
        String only = a.length > 0 ? a[0] : "all";

        System.out.println("== 1. catalog GET /models");
        try {
            List<ModelInfo> ms = client.listModels(cfg, new CancelToken());
            System.out.println("   models: " + ms.size() + "; first: " + (ms.size() > 0 ? ms.get(0).id : "-") + "; default pick: " + Providers.pickDefaultModel(ms));
            if (model == null || model.isEmpty()) model = Providers.pickDefaultModel(ms);
        } catch (LlmException e) {
            System.out.println("   catalog FAILED: " + e.kind + " — " + e.userMessage + " | " + Redactor.redact(e.technical));
        }
        if (model == null || model.isEmpty()) { System.out.println("no model"); return; }
        System.out.println("   using model: " + model);

        if (only.equals("all") || only.equals("stream")) {
            System.out.println("== 2. streaming reply");
            LlmRequest rq = new LlmRequest();
            rq.model = model;
            rq.messages.add(ChatMessage.system("Отвечай по-русски, коротко, без markdown."));
            rq.messages.add(ChatMessage.user("Назови три цвета радуги через запятую."));
            rq.maxTokens = 600;
            rq.temperature = 0.7;
            if (System.getenv("JV_REASONING") != null) rq.reasoning = System.getenv("JV_REASONING");
            rq.realtime = true;
            final StringBuilder sb = new StringBuilder();
            final int[] deltas = {0};
            LlmResult r = client.chat(cfg, rq, new LlmListener() {
                @Override public void onTextDelta(String d) { deltas[0]++; sb.append(d); }
                @Override public void onReasoningDelta(String d) { }
            }, new CancelToken());
            System.out.println("   text: " + sb.toString().trim().replace('\n', ' '));
            System.out.println("   deltas=" + deltas[0] + " finish=" + r.finishReason + " headers=" + r.headersMs + "ms firstDelta=" + r.firstDeltaMs + "ms total=" + r.totalMs
                + "ms tokens(p/c/r)=" + r.promptTokens + "/" + r.completionTokens + "/" + r.reasoningTokens + " attempts=" + r.attempts + " dropped=" + r.droppedParams + " warn=" + r.warning);
        }

        if (only.equals("all") || only.equals("agent")) {
            System.out.println("== 3. agent loop with real tools (file + shell)");
            File tmpBase = new File(System.getenv("TMPDIR") != null ? System.getenv("TMPDIR") : System.getProperty("java.io.tmpdir"));
            File root = Files.createTempDirectory(tmpBase.toPath(), "jv-live").toFile();
            Sandbox sb = new Sandbox(root);
            ToolRegistry reg = new ToolRegistry();
            FsTools.register(reg, sb);
            ShellTools.register(reg, new ShellSession(sb));
            MemoryStore.registerTools(reg, new MemoryStore(new File(root, "mem.json")));
            AgentRunSpec sp = new AgentRunSpec();
            sp.config = cfg;
            sp.model = model;
            sp.tools = reg;
            sp.journal = jr;
            sp.maxTokens = 3000;
            if (System.getenv("JV_REASONING") != null) sp.reasoning = System.getenv("JV_REASONING");
            sp.realtime = true;
            Prompts.Info info = new Prompts.Info();
            info.nowText = new java.util.Date().toString();
            sp.systemPrompt = Prompts.system(info);
            sp.user = ChatMessage.user("Создай в docs заметку note.txt с текстом «Купить хлеб и молоко». Потом выполни в терминале команду cat docs/note.txt и скажи, что вывела команда. И запомни, что я люблю утром спокойную музыку.");
            final StringBuilder said = new StringBuilder();
            AgentOutcome o = new AgentLoop(client).run(sp, new AgentListener() {
                @Override public void onStep(int s) { System.out.println("   -- step " + s); }
                @Override public void onTextDelta(int s, String d) { said.append(d); }
                @Override public void onToolStart(ToolCall c) { System.out.println("   tool> " + c.name + " " + Redactor.redact(c.arguments)); }
                @Override public void onToolEnd(ToolCall c, ToolResult r, long ms) { System.out.println("   tool< " + c.name + " ok=" + r.ok + " " + r.summary + " (" + ms + " ms)"); }
            }, new CancelToken());
            System.out.println("   outcome=" + o.status + " steps=" + o.steps + " tools=" + o.toolCalls + " firstText=" + o.firstTextMs + "ms total=" + o.totalMs + "ms err=" + o.errorText);
            System.out.println("   final: " + o.finalText.replace('\n', ' '));
            File note = new File(sb.docs, "note.txt");
            System.out.println("   VERIFY file exists=" + note.isFile() + (note.isFile() ? " content=«" + new String(Files.readAllBytes(note.toPath()), "UTF-8") + "»" : ""));
            System.out.println("   VERIFY memory: " + new MemoryStore(new File(root, "mem.json")).all().size() + " entries");
        }

        if (only.equals("all") || only.equals("cancel")) {
            System.out.println("== 4. cancel mid-stream");
            LlmRequest rq = new LlmRequest();
            rq.model = model;
            rq.messages.add(ChatMessage.user("Напиши очень длинный рассказ о космосе, не менее 2000 слов."));
            rq.maxTokens = 4000;
            final CancelToken c = new CancelToken();
            final long[] cancelAt = {0};
            long t0 = System.nanoTime();
            try {
                client.chat(cfg, rq, new LlmListener() {
                    int n;
                    @Override public void onTextDelta(String d) { if (++n == 2 && cancelAt[0] == 0) { cancelAt[0] = System.nanoTime(); c.cancel("user"); } }
                    @Override public void onReasoningDelta(String d) { }
                }, c);
                System.out.println("   (finished before cancel took effect)");
            } catch (LlmException e) {
                System.out.println("   kind=" + e.kind + " returned " + (cancelAt[0] == 0 ? -1 : (System.nanoTime() - cancelAt[0]) / 1_000_000L) + " ms after cancel; total " + (System.nanoTime() - t0) / 1_000_000L + " ms");
            }
        }
    }
}
