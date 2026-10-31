package app.jarvis.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.MockServer;
import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.LlmConfig;
import app.jarvis.core.llm.OpenAiClient;
import app.jarvis.core.llm.ToolSpec;
import app.jarvis.core.log.Journal;
import app.jarvis.core.util.CancelToken;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Speed features of the loop: volatile context on the request only, local finish from tool hints, latency reporting. */
public class AgentLoopSpeedTest {
    private static String toolCallChunk(String id, String name, String args) {
        return "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\"" + name
            + "\",\"arguments\":" + JSONObject.quote(args) + "}}]}}]}";
    }

    private static ToolRegistry registry(final boolean withSpoken, final boolean ok) {
        ToolRegistry reg = new ToolRegistry();
        reg.add(ToolSpec.of("music_pause", "Пауза"), "player", 5000, true, new ToolRegistry.Handler() {
            @Override public ToolResult run(JSONObject a, ToolContext c) {
                if (!ok) return ToolResult.fail("плеер не играет");
                ToolResult r = ToolResult.ok("Пауза");
                return withSpoken ? r.spoken("Поставил на паузу.") : r;
            }
        });
        reg.add(ToolSpec.of("custom_tool", "Без подсказки"), "tools", 5000, true, new ToolRegistry.Handler() {
            @Override public ToolResult run(JSONObject a, ToolContext c) { return ToolResult.ok("сделано"); }
        });
        reg.add(ToolSpec.of("app_open", "Открыть"), "apps", 5000, true, new ToolRegistry.Handler() {
            @Override public ToolResult run(JSONObject a, ToolContext c) { return ToolResult.ok("Открыл").spoken("Открываю."); }
        });
        return reg;
    }

    private static MockServer server(final AtomicInteger steps, final List<String> bodies, final String... toolNames) throws IOException {
        return new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                bodies.add(body);
                if (steps.incrementAndGet() == 1 && toolNames.length > 0) {
                    List<String> ev = new ArrayList<>();
                    for (int i = 0; i < toolNames.length; i++)
                        ev.add("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":" + i + ",\"id\":\"c" + i + "\",\"type\":\"function\",\"function\":{\"name\":\"" + toolNames[i] + "\",\"arguments\":\"{}\"}}]}}]}");
                    ev.add("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
                    ev.add("[DONE]");
                    MockServer.sse(x, 0, ev.toArray(new String[0]));
                } else {
                    MockServer.sse(x, 0, MockServer.chunkText("Модель ответила."), MockServer.chunkFinish("stop"), "[DONE]");
                }
            }
        });
    }

    private static AgentRunSpec spec(MockServer s, ToolRegistry reg) {
        AgentRunSpec r = new AgentRunSpec();
        r.config = new LlmConfig();
        r.config.baseUrl = s.base();
        r.config.apiKey = "k";
        r.config.idleTimeoutMs = 5000;
        r.model = "m";
        r.systemPrompt = "SYSTEM";
        r.user = ChatMessage.user("поставь на паузу");
        r.tools = reg;
        return r;
    }

    @Test public void volatileContextGoesToTheRequestOnlyAndSystemStaysStable() throws Exception {
        AtomicInteger steps = new AtomicInteger();
        List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        try (MockServer s = server(steps, bodies)) {
            final List<ChatMessage> persisted = new ArrayList<>();
            AgentRunSpec sp = spec(s, registry(true, true));
            sp.userContext = "[контекст: сейчас: 10:41]";
            sp.history.add(ChatMessage.user("привет"));
            sp.history.add(ChatMessage.assistant("Здравствуйте!", null));
            new AgentLoop(new OpenAiClient(Journal.NOP)).run(sp, new AgentListener() {
                @Override public void onMessage(ChatMessage m) { persisted.add(m); }
            }, new CancelToken());
            JSONArray m = new JSONObject(bodies.get(0)).getJSONArray("messages");
            assertEquals("SYSTEM", m.getJSONObject(0).getString("content"));
            assertEquals("поставь на паузу\n\n[контекст: сейчас: 10:41]", m.getJSONObject(m.length() - 1).getString("content"));
            assertEquals("привет", m.getJSONObject(1).getString("content"));
            // the persisted conversation never contains the volatile line
            for (ChatMessage p : persisted) assertFalse(p.content.contains("[контекст"));
            assertEquals("поставь на паузу", sp.user.content);
        }
    }

    @Test public void localFinishSkipsTheSecondModelCall() throws Exception {
        AtomicInteger steps = new AtomicInteger();
        List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        try (MockServer s = server(steps, bodies, "music_pause")) {
            AgentRunSpec sp = spec(s, registry(true, true));
            sp.localFinish = true;
            final StringBuilder spoken = new StringBuilder();
            AgentOutcome out = new AgentLoop(new OpenAiClient(Journal.NOP)).run(sp, new AgentListener() {
                @Override public void onTextDelta(int step, String d) { spoken.append(d); }
            }, new CancelToken());
            assertEquals(AgentOutcome.Status.DONE, out.status);
            assertEquals("Поставил на паузу.", out.finalText);
            assertEquals("Поставил на паузу.", spoken.toString());
            assertEquals(1, steps.get());
            assertEquals(1, out.toolCalls);
            assertTrue(out.firstTextMs >= 0);
        }
    }

    @Test public void localFinishJoinsHintsOfParallelTools() throws Exception {
        AtomicInteger steps = new AtomicInteger();
        List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        try (MockServer s = server(steps, bodies, "music_pause", "app_open")) {
            AgentRunSpec sp = spec(s, registry(true, true));
            sp.localFinish = true;
            AgentOutcome out = new AgentLoop(new OpenAiClient(Journal.NOP)).run(sp, null, new CancelToken());
            assertEquals("Поставил на паузу. Открываю.", out.finalText);
            assertEquals(1, steps.get());
        }
    }

    @Test public void withoutHintOrOnFailureOrWhenDisabledTheModelIsAskedAgain() throws Exception {
        for (int variant = 0; variant < 3; variant++) {
            AtomicInteger steps = new AtomicInteger();
            List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
            try (MockServer s = server(steps, bodies, variant == 0 ? "custom_tool" : "music_pause")) {
                AgentRunSpec sp = spec(s, registry(true, variant != 1));
                sp.localFinish = variant != 2;
                AgentOutcome out = new AgentLoop(new OpenAiClient(Journal.NOP)).run(sp, null, new CancelToken());
                assertEquals("variant " + variant, 2, steps.get());
                assertEquals("Модель ответила.", out.finalText);
            }
        }
    }

    @Test public void defaultHintsComeFromTheClosedPhraseSet() throws Exception {
        AtomicInteger steps = new AtomicInteger();
        List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        try (MockServer s = server(steps, bodies, "music_pause")) {
            AgentRunSpec sp = spec(s, registry(false, true));        // the tool gives no spoken text of its own
            sp.localFinish = true;
            AgentOutcome out = new AgentLoop(new OpenAiClient(Journal.NOP)).run(sp, null, new CancelToken());
            assertTrue(out.finalText, FastPhrases.contains(out.finalText));
            assertEquals(1, steps.get());
        }
    }

    @Test public void latencySinkReceivesFirstTokenOfEveryStep() throws Exception {
        AtomicInteger steps = new AtomicInteger();
        List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        try (MockServer s = server(steps, bodies, "music_pause")) {
            AgentRunSpec sp = spec(s, registry(false, true));
            final List<Long> seen = Collections.synchronizedList(new ArrayList<Long>());
            sp.latency = new AgentRunSpec.LatencySink() {
                @Override public void onStep(boolean hedgeWon, long firstTokenMs, long headersMs) { seen.add(firstTokenMs); }
            };
            new AgentLoop(new OpenAiClient(Journal.NOP)).run(sp, null, new CancelToken());
            assertEquals(2, seen.size());
            assertTrue(seen.get(0) >= 0);
        }
    }

    @Test public void cacheBreakpointPointsAtTheLastStableMessage() throws Exception {
        AtomicInteger steps = new AtomicInteger();
        List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        try (MockServer s = server(steps, bodies)) {
            AgentRunSpec sp = spec(s, registry(false, true));
            sp.model = "anthropic/claude-haiku-5.5";
            sp.config.quirks.cacheControl = true;
            sp.history.add(ChatMessage.user("привет"));
            sp.history.add(ChatMessage.assistant("Здравствуйте!", null));
            sp.userContext = "[контекст: x]";
            new AgentLoop(new OpenAiClient(Journal.NOP)).run(sp, null, new CancelToken());
            JSONArray m = new JSONObject(bodies.get(0)).getJSONArray("messages");
            assertTrue(m.getJSONObject(0).get("content") instanceof JSONArray);
            assertTrue(m.getJSONObject(2).get("content") instanceof JSONArray);       // last history message
            assertTrue(m.getJSONObject(3).get("content") instanceof String);          // current user turn is volatile: no marker
        }
    }
}
