package app.jarvis.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.MockServer;
import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.LlmConfig;
import app.jarvis.core.llm.OpenAiClient;
import app.jarvis.core.llm.ToolCall;
import app.jarvis.core.log.Journal;
import app.jarvis.core.tools.FsTools;
import app.jarvis.core.tools.Sandbox;
import app.jarvis.core.util.CancelToken;
import com.sun.net.httpserver.HttpExchange;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

/** The whole loop against a scripted provider over a real socket: tool call → real file written → final answer. */
public class AgentLoopTest {
    private File root;
    private Sandbox sb;
    private ToolRegistry reg;

    @Before public void setUp() throws Exception {
        root = Files.createTempDirectory("jv-agent").toFile();
        sb = new Sandbox(root);
        reg = new ToolRegistry();
        FsTools.register(reg, sb);
    }

    private AgentRunSpec spec(MockServer s, String userText) {
        AgentRunSpec r = new AgentRunSpec();
        r.config = new LlmConfig();
        r.config.baseUrl = s.base();
        r.config.apiKey = "k";
        r.config.idleTimeoutMs = 5000;
        r.model = "m";
        r.systemPrompt = "sys";
        r.user = ChatMessage.user(userText);
        r.tools = reg;
        return r;
    }

    private static String toolCallChunk(String id, String name, String args) {
        return "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\"" + name
            + "\",\"arguments\":" + JSONObject.quote(args) + "}}]}}]}";
    }

    @Test public void toolCallThenFinalAnswerWritesRealFile() throws Exception {
        final AtomicInteger step = new AtomicInteger();
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                if (step.incrementAndGet() == 1) {
                    MockServer.sse(x, 0, toolCallChunk("call_1", "fs_write", "{\"path\":\"docs/note.txt\",\"content\":\"Купить хлеб\"}"),
                        "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}", "[DONE]");
                } else {
                    MockServer.sse(x, 0, MockServer.chunkText("Заметка создана."), MockServer.chunkFinish("stop"), "[DONE]");
                }
            }
        })) {
            final List<ChatMessage> persisted = new ArrayList<>();
            final List<String> events = new ArrayList<>();
            AgentOutcome out = new AgentLoop(new OpenAiClient(Journal.NOP)).run(spec(s, "создай заметку"), new AgentListener() {
                @Override public void onMessage(ChatMessage m) { persisted.add(m); }
                @Override public void onToolStart(ToolCall c) { events.add("start:" + c.name); }
                @Override public void onToolEnd(ToolCall c, ToolResult r, long ms) { events.add("end:" + c.name + ":" + r.ok); }
            }, new CancelToken());
            assertEquals(AgentOutcome.Status.DONE, out.status);
            assertEquals("Заметка создана.", out.finalText);
            assertEquals(2, out.steps);
            assertEquals(1, out.toolCalls);
            // the file really exists with the real content: success is proven by the tool, not by the model's words
            File f = new File(sb.docs, "note.txt");
            assertTrue(f.isFile());
            assertEquals("Купить хлеб", new String(Files.readAllBytes(f.toPath()), "UTF-8"));
            assertEquals("[start:fs_write, end:fs_write:true]", events.toString());
            // persisted order and pairing: assistant(tool_calls) → tool(same id) → assistant(final)
            assertEquals(3, persisted.size());
            assertTrue(persisted.get(0).hasToolCalls());
            assertEquals("tool", persisted.get(1).role);
            assertEquals(persisted.get(0).toolCalls.get(0).id, persisted.get(1).toolCallId);
            assertTrue(persisted.get(1).content.contains("\"ok\":true"));
            // second request must carry the tool result back to the model
            JSONArray msgs = new JSONObject(s.bodies.get(1)).getJSONArray("messages");
            assertEquals("tool", msgs.getJSONObject(msgs.length() - 1).getString("role"));
            assertEquals("call_1", msgs.getJSONObject(msgs.length() - 1).getString("tool_call_id"));
        }
    }

    @Test public void failedToolIsReportedAsFailureNotSuccess() throws Exception {
        final AtomicInteger step = new AtomicInteger();
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                if (step.incrementAndGet() == 1)
                    MockServer.sse(x, 0, toolCallChunk("c1", "fs_read", "{\"path\":\"nope.txt\"}"), "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}", "[DONE]");
                else MockServer.sse(x, 0, MockServer.chunkText("Файла нет."), MockServer.chunkFinish("stop"), "[DONE]");
            }
        })) {
            final List<ToolResult> results = new ArrayList<>();
            new AgentLoop(new OpenAiClient(Journal.NOP)).run(spec(s, "прочитай"), new AgentListener() {
                @Override public void onToolEnd(ToolCall c, ToolResult r, long ms) { results.add(r); }
            }, new CancelToken());
            assertFalse(results.get(0).ok);
            JSONArray msgs = new JSONObject(s.bodies.get(1)).getJSONArray("messages");
            String toolMsg = msgs.getJSONObject(msgs.length() - 1).getString("content");
            assertTrue(toolMsg, toolMsg.contains("\"ok\":false"));
            assertTrue(toolMsg, toolMsg.contains("не найден"));
        }
    }

    @Test public void truncatedToolArgumentsGetAnErrorResultSoTheModelCanRecover() throws Exception {
        final AtomicInteger step = new AtomicInteger();
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                if (step.incrementAndGet() == 1)
                    MockServer.sse(x, 0, toolCallChunk("c1", "fs_write", "{\"path\":\"a.txt\",\"content\":\"обрыв"), "{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}", "[DONE]");
                else MockServer.sse(x, 0, MockServer.chunkText("Повторю."), MockServer.chunkFinish("stop"), "[DONE]");
            }
        })) {
            AgentOutcome out = new AgentLoop(new OpenAiClient(Journal.NOP)).run(spec(s, "x"), null, new CancelToken());
            assertEquals(AgentOutcome.Status.DONE, out.status);
            JSONArray msgs = new JSONObject(s.bodies.get(1)).getJSONArray("messages");
            String toolMsg = msgs.getJSONObject(msgs.length() - 1).getString("content");
            assertTrue(toolMsg, toolMsg.contains("\"ok\":false") && toolMsg.contains("JSON"));
            assertFalse("nothing may be written from broken arguments", new File(sb.root, "a.txt").exists());
        }
    }

    @Test public void identicalRepeatedCallIsBlockedAfterTwoRuns() throws Exception {
        final AtomicInteger step = new AtomicInteger();
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                int n = step.incrementAndGet();
                if (n <= 4) MockServer.sse(x, 0, toolCallChunk("c" + n, "fs_list", "{\"path\":\"\"}"), "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}", "[DONE]");
                else MockServer.sse(x, 0, MockServer.chunkText("Хватит."), MockServer.chunkFinish("stop"), "[DONE]");
            }
        })) {
            final int[] okCount = {0}, blocked = {0};
            new AgentLoop(new OpenAiClient(Journal.NOP)).run(spec(s, "x"), new AgentListener() {
                @Override public void onToolEnd(ToolCall c, ToolResult r, long ms) { if (r.ok) okCount[0]++; else blocked[0]++; }
            }, new CancelToken());
            assertEquals(2, okCount[0]);
            assertEquals(2, blocked[0]);
        }
    }

    @Test public void maxStepsIsReportedHonestly() throws Exception {
        final AtomicInteger n = new AtomicInteger();
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                MockServer.sse(x, 0, toolCallChunk("c" + n.incrementAndGet(), "fs_list", "{\"path\":\"" + n.get() + "\"}"), "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}", "[DONE]");
            }
        })) {
            AgentRunSpec sp = spec(s, "x");
            sp.maxSteps = 3;
            AgentOutcome out = new AgentLoop(new OpenAiClient(Journal.NOP)).run(sp, null, new CancelToken());
            assertEquals(AgentOutcome.Status.MAX_STEPS, out.status);
            assertEquals(3, out.steps);
        }
    }

    @Test public void cancelledDuringToolStillPairsEveryToolCall() throws Exception {
        ToolRegistry slow = new ToolRegistry();
        slow.add(app.jarvis.core.llm.ToolSpec.of("slow", "x"), "tools", 30_000, true, new ToolRegistry.Handler() {
            @Override public ToolResult run(JSONObject a, ToolContext ctx) throws Exception {
                while (!ctx.cancel.isCancelled()) Thread.sleep(20);
                return ToolResult.cancelled();
            }
        });
        reg = slow;
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                MockServer.sse(x, 0,
                    "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"slow\",\"arguments\":\"{}\"}},{\"index\":1,\"id\":\"b\",\"function\":{\"name\":\"slow\",\"arguments\":\"{\\\"x\\\":1}\"}}]}}]}",
                    "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}", "[DONE]");
            }
        })) {
            final CancelToken cancel = new CancelToken();
            final List<ChatMessage> persisted = Collections.synchronizedList(new ArrayList<ChatMessage>());
            new Thread(new Runnable() { @Override public void run() { try { Thread.sleep(600); } catch (InterruptedException ignored) { } cancel.cancel("user"); } }).start();
            AgentOutcome out = new AgentLoop(new OpenAiClient(Journal.NOP)).run(spec(s, "x"), new AgentListener() {
                @Override public void onMessage(ChatMessage m) { persisted.add(m); }
            }, cancel);
            assertEquals(AgentOutcome.Status.CANCELLED, out.status);
            assertEquals("assistant + 2 tool results, never a dangling call", 3, persisted.size());
            assertEquals("a", persisted.get(1).toolCallId);
            assertEquals("b", persisted.get(2).toolCallId);
        }
    }

    @Test public void contextLengthErrorShrinksPromptAndRetries() throws Exception {
        final AtomicInteger n = new AtomicInteger();
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                if (n.incrementAndGet() == 1) MockServer.json(x, 400, "{\"error\":{\"message\":\"too long\",\"code\":\"context_length_exceeded\"}}");
                else MockServer.sse(x, 0, MockServer.chunkText("ок"), MockServer.chunkFinish("stop"), "[DONE]");
            }
        })) {
            AgentRunSpec sp = spec(s, "вопрос");
            for (int i = 0; i < 100; i++) {
                sp.history.add(ChatMessage.user("старый вопрос " + i + " " + new String(new char[900]).replace('\0', 'я')));
                sp.history.add(ChatMessage.assistant("старый ответ " + i, null));
            }
            sp.contextChars = 40_000;
            AgentOutcome out = new AgentLoop(new OpenAiClient(Journal.NOP)).run(sp, null, new CancelToken());
            assertEquals(AgentOutcome.Status.DONE, out.status);
            int first = new JSONObject(s.bodies.get(0)).getJSONArray("messages").length();
            int second = new JSONObject(s.bodies.get(1)).getJSONArray("messages").length();
            assertTrue("первый запрос уже обрезан бюджетом: " + first, first < 202);
            assertTrue("после ошибки контекста запрос должен стать меньше (" + first + " → " + second + ")", second < first);
            assertTrue(s.bodies.get(1).length() < 24_000 * 2); // ~ half of the budget, bytes of UTF-8 Cyrillic ≈ 2 per char
        }
    }

    @Test public void providerErrorBecomesErrorOutcomeWithUserMessage() throws Exception {
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException { MockServer.json(x, 401, "{\"error\":{\"message\":\"bad key\"}}"); }
        })) {
            AgentOutcome out = new AgentLoop(new OpenAiClient(Journal.NOP)).run(spec(s, "x"), null, new CancelToken());
            assertEquals(AgentOutcome.Status.ERROR, out.status);
            assertTrue(out.errorText, out.errorText.contains("ключ"));
        }
    }

    @Test public void prefillIsSentAfterTheUserMessageAndNotReportedAsNewMessages() throws Exception {
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                MockServer.sse(x, 0, MockServer.chunkText("Остановил, тишина."), MockServer.chunkFinish("stop"), "[DONE]");
            }
        })) {
            AgentRunSpec sp = spec(s, "останови музыку");
            sp.prefill.add(ChatMessage.assistant("", java.util.Collections.singletonList(new ToolCall("d1", "music_stop", "{}"))));
            sp.prefill.add(ChatMessage.tool("d1", "music_stop", "{\"ok\":true,\"state\":\"stopped\"}"));
            final List<ChatMessage> reported = new ArrayList<>();
            AgentOutcome out = new AgentLoop(new OpenAiClient(Journal.NOP)).run(sp, new AgentListener() {
                @Override public void onMessage(ChatMessage m) { reported.add(m); }
            }, new CancelToken());
            assertEquals(AgentOutcome.Status.DONE, out.status);
            assertEquals(1, out.steps);
            assertEquals("Остановил, тишина.", out.finalText);
            assertEquals("only the model's own answer is reported", 1, reported.size());
            JSONArray msgs = new JSONObject(s.bodies.get(0)).getJSONArray("messages");
            // system, user, assistant(tool_calls), tool
            assertEquals(4, msgs.length());
            assertEquals("user", msgs.getJSONObject(1).getString("role"));
            assertEquals("assistant", msgs.getJSONObject(2).getString("role"));
            assertEquals("d1", msgs.getJSONObject(3).getString("tool_call_id"));
        }
    }

    // ---- empty answers (the "…" bubble) --------------------------------------------------------------------------------

    private static final String TOOL_FINISH = "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}";

    @Test public void emptyFinalAfterToolIsNudgedOnceAndThenAnswered() throws Exception {
        final AtomicInteger n = new AtomicInteger();
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                int i = n.incrementAndGet();
                if (i == 1) MockServer.sse(x, 0, toolCallChunk("c1", "fs_list", "{\"path\":\"\"}"), TOOL_FINISH, "[DONE]");
                else if (i == 2) MockServer.sse(x, 0, MockServer.chunkFinish("stop"), "[DONE]");
                else MockServer.sse(x, 0, MockServer.chunkText("Файлов пока нет."), MockServer.chunkFinish("stop"), "[DONE]");
            }
        })) {
            final List<ChatMessage> persisted = new ArrayList<>();
            AgentOutcome out = new AgentLoop(new OpenAiClient(Journal.NOP)).run(spec(s, "что в папке"), new AgentListener() {
                @Override public void onMessage(ChatMessage m) { persisted.add(m); }
            }, new CancelToken());
            assertEquals(AgentOutcome.Status.DONE, out.status);
            assertEquals("Файлов пока нет.", out.finalText);
            assertEquals(3, out.steps);
            JSONArray msgs = new JSONObject(s.bodies.get(2)).getJSONArray("messages");
            JSONObject last = msgs.getJSONObject(msgs.length() - 1);
            assertEquals("user", last.getString("role"));
            assertTrue(last.getString("content"), last.getString("content").contains("Системное напоминание"));
            for (ChatMessage m : persisted) assertFalse("no blank assistant message is ever persisted", "assistant".equals(m.role) && m.content.trim().isEmpty() && !m.hasToolCalls());
            for (ChatMessage m : persisted) assertFalse("the nudge is not part of the stored history", m.content != null && m.content.contains("Системное напоминание"));
        }
    }

    @Test public void stillEmptyAfterSuccessfulToolFallsBackToTheToolsOwnSummary() throws Exception {
        final AtomicInteger n = new AtomicInteger();
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                if (n.incrementAndGet() == 1) MockServer.sse(x, 0, toolCallChunk("c1", "fs_mkdir", "{\"path\":\"work/new\"}"), TOOL_FINISH, "[DONE]");
                else MockServer.sse(x, 0, MockServer.chunkFinish("stop"), "[DONE]");
            }
        })) {
            final StringBuilder spoken = new StringBuilder();
            AgentOutcome out = new AgentLoop(new OpenAiClient(Journal.NOP)).run(spec(s, "создай папку"), new AgentListener() {
                @Override public void onTextDelta(int step, String d) { spoken.append(d); }
            }, new CancelToken());
            assertEquals(AgentOutcome.Status.DONE, out.status);
            assertTrue(out.finalText, out.finalText.startsWith("Готово"));
            assertEquals("the fallback is also streamed so it gets spoken", out.finalText, spoken.toString());
            assertTrue("the folder really exists: the fallback only restates a real result", new File(sb.work, "new").isDirectory());
        }
    }

    @Test public void emptyAnswerWithoutAnyToolIsAnErrorNotABlankBubble() throws Exception {
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException { MockServer.sse(x, 0, MockServer.chunkFinish("stop"), "[DONE]"); }
        })) {
            final List<ChatMessage> persisted = new ArrayList<>();
            AgentOutcome out = new AgentLoop(new OpenAiClient(Journal.NOP)).run(spec(s, "привет"), new AgentListener() {
                @Override public void onMessage(ChatMessage m) { persisted.add(m); }
            }, new CancelToken());
            assertEquals(AgentOutcome.Status.ERROR, out.status);
            assertTrue(out.errorText, out.errorText.contains("не вернула текста") || out.errorText.contains("пустой"));
            assertEquals(0, persisted.size());
            assertEquals("one nudge, then give up", 2, s.bodies.size());
        }
    }

    // ---- parallel tool calls ---------------------------------------------------------------------------------------------

    private static String multiToolChunk(String... idNameArgs) {
        StringBuilder b = new StringBuilder("{\"choices\":[{\"delta\":{\"tool_calls\":[");
        for (int i = 0; i < idNameArgs.length; i += 3) {
            if (i > 0) b.append(',');
            b.append("{\"index\":").append(i / 3).append(",\"id\":\"").append(idNameArgs[i]).append("\",\"type\":\"function\",\"function\":{\"name\":\"")
                .append(idNameArgs[i + 1]).append("\",\"arguments\":").append(JSONObject.quote(idNameArgs[i + 2])).append("}}");
        }
        return b.append("]}}]}").toString();
    }

    private static ToolRegistry.Handler sleeper(final long ms, final List<String> log, final String tag) {
        return new ToolRegistry.Handler() {
            @Override public ToolResult run(JSONObject a, ToolContext ctx) throws Exception {
                log.add("start:" + tag);
                Thread.sleep(ms);
                log.add("end:" + tag);
                return ToolResult.ok(tag);
            }
        };
    }

    @Test public void independentReadOnlyCallsRunConcurrentlyAndResultsKeepCallOrder() throws Exception {
        final List<String> log = Collections.synchronizedList(new ArrayList<String>());
        ToolRegistry r = new ToolRegistry();
        for (String t : new String[]{"ra", "rb", "rc"}) r.add(app.jarvis.core.llm.ToolSpec.of(t, "read"), "tools", 10_000, false, sleeper(400, log, t));
        reg = r;
        final AtomicInteger n = new AtomicInteger();
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                if (n.incrementAndGet() == 1) MockServer.sse(x, 0, multiToolChunk("a", "ra", "{}", "b", "rb", "{\"x\":1}", "c", "rc", "{\"x\":2}"), TOOL_FINISH, "[DONE]");
                else MockServer.sse(x, 0, MockServer.chunkText("Всё прочитал."), MockServer.chunkFinish("stop"), "[DONE]");
            }
        })) {
            final List<String> ends = Collections.synchronizedList(new ArrayList<String>());
            long t0 = System.nanoTime();
            AgentOutcome out = new AgentLoop(new OpenAiClient(Journal.NOP)).run(spec(s, "x"), new AgentListener() {
                @Override public void onToolEnd(ToolCall c, ToolResult res, long ms) { ends.add(c.id); }
            }, new CancelToken());
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            assertEquals(AgentOutcome.Status.DONE, out.status);
            assertEquals(3, out.toolCalls);
            assertTrue("3 x 400 ms must overlap (sequential would be >= 1200 ms), took " + ms, ms < 1000);
            assertEquals("all three started before the first one ended", "[start:ra, start:rb, start:rc]", log.subList(0, 3).stream().sorted().collect(java.util.stream.Collectors.toList()).toString());
            JSONArray msgs = new JSONObject(s.bodies.get(1)).getJSONArray("messages");
            int len = msgs.length();
            assertEquals("a", msgs.getJSONObject(len - 3).getString("tool_call_id"));
            assertEquals("b", msgs.getJSONObject(len - 2).getString("tool_call_id"));
            assertEquals("c", msgs.getJSONObject(len - 1).getString("tool_call_id"));
            assertEquals(3, ends.size());
        }
    }

    @Test public void callsWithSideEffectsRunAloneInOrderBetweenReadBatches() throws Exception {
        final List<String> log = Collections.synchronizedList(new ArrayList<String>());
        ToolRegistry r = new ToolRegistry();
        r.add(app.jarvis.core.llm.ToolSpec.of("rd", "read"), "tools", 10_000, false, sleeper(150, log, "rd"));
        r.add(app.jarvis.core.llm.ToolSpec.of("wr", "write"), "tools", 10_000, true, sleeper(150, log, "wr"));
        reg = r;
        final AtomicInteger n = new AtomicInteger();
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                if (n.incrementAndGet() == 1) MockServer.sse(x, 0, multiToolChunk("1", "rd", "{\"i\":1}", "2", "wr", "{\"i\":2}", "3", "wr", "{\"i\":3}", "4", "rd", "{\"i\":4}"), TOOL_FINISH, "[DONE]");
                else MockServer.sse(x, 0, MockServer.chunkText("ок"), MockServer.chunkFinish("stop"), "[DONE]");
            }
        })) {
            AgentOutcome out = new AgentLoop(new OpenAiClient(Journal.NOP)).run(spec(s, "x"), null, new CancelToken());
            assertEquals(AgentOutcome.Status.DONE, out.status);
            assertEquals("[start:rd, end:rd, start:wr, end:wr, start:wr, end:wr, start:rd, end:rd]", log.toString());
        }
    }

    @Test public void builtInReadToolsAreMarkedSafeForParallelRuns() {
        ToolRegistry r = new ToolRegistry();
        FsTools.register(r, sb);
        for (String t : new String[]{"fs_list", "fs_read", "fs_stat"}) assertFalse(t + " is read-only", r.get(t).sideEffects());
        for (String t : new String[]{"fs_write", "fs_delete", "fs_move", "fs_mkdir"}) assertTrue(t + " changes files", r.get(t).sideEffects());
    }
}
