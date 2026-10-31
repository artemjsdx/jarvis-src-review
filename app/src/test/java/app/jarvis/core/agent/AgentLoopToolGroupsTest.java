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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The agent loop with tool groups: what each model request announces, the meta tool, and a call of a hidden tool. */
public class AgentLoopToolGroupsTest {
    /** Script entry: {"tool", name, argsJson} makes the "model" call a tool, {"text", answer} makes it answer. After the script: "Готово.". */
    private static String[] tool(String name, String args) { return new String[] {"tool", name, args}; }

    private static MockServer server(final AtomicInteger steps, final List<String> bodies, final String[]... script) throws IOException {
        return new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                bodies.add(body);
                int i = steps.getAndIncrement();
                String[] e = i < script.length ? script[i] : new String[] {"text", "Готово."};
                if ("tool".equals(e[0])) {
                    MockServer.sse(x, 0,
                        "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c" + i + "\",\"type\":\"function\",\"function\":{\"name\":\"" + e[1]
                            + "\",\"arguments\":" + JSONObject.quote(e[2]) + "}}]}}]}",
                        "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}", "[DONE]");
                } else {
                    MockServer.sse(x, 0, MockServer.chunkText(e[1]), MockServer.chunkFinish("stop"), "[DONE]");
                }
            }
        });
    }

    private static AgentRunSpec spec(MockServer s, ToolRegistry reg, ToolSelection sel) {
        AgentRunSpec r = new AgentRunSpec();
        r.config = new LlmConfig();
        r.config.baseUrl = s.base();
        r.config.apiKey = "k";
        r.config.idleTimeoutMs = 5000;
        r.model = "m";
        r.systemPrompt = "SYSTEM";
        r.user = ChatMessage.user("сделай что-нибудь");
        r.tools = reg;
        r.toolSelection = sel;
        return r;
    }

    /** Tool names announced in one request body. */
    private static List<String> announced(String body) throws Exception {
        List<String> r = new ArrayList<>();
        JSONArray t = new JSONObject(body).optJSONArray("tools");
        if (t != null) for (int i = 0; i < t.length(); i++) r.add(t.getJSONObject(i).getJSONObject("function").getString("name"));
        return r;
    }

    /** Registry whose music_pause and fs_read count their runs. */
    private static ToolRegistry counting(final AtomicInteger pauseRuns) {
        ToolRegistry reg = ToolFixtures.fullRegistry();
        reg.add(ToolSpec.of("music_pause", "Пауза"), "player", 5000, true, new ToolRegistry.Handler() {
            @Override public ToolResult run(JSONObject a, ToolContext c) { pauseRuns.incrementAndGet(); return ToolResult.ok("Пауза").spoken("Поставил на паузу."); }
        });
        return reg;
    }

    private static AgentOutcome run(AgentRunSpec sp) { return new AgentLoop(new OpenAiClient(Journal.NOP)).run(sp, AgentListener.NOP, new CancelToken()); }

    @Test public void withoutASelectionEveryToolIsAnnouncedAsBefore() throws Exception {
        AtomicInteger steps = new AtomicInteger();
        List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        ToolRegistry reg = ToolFixtures.fullRegistry();
        try (MockServer s = server(steps, bodies)) {
            AgentOutcome out = run(spec(s, reg, null));
            assertEquals(AgentOutcome.Status.DONE, out.status);
            assertEquals(reg.names(), announced(bodies.get(0)));
            assertFalse(announced(bodies.get(0)).contains(ToolGroups.META_TOOL));
        }
    }

    @Test public void aSelectionAnnouncesTheCoreAndTheMetaToolOnly() throws Exception {
        AtomicInteger steps = new AtomicInteger();
        List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        ToolRegistry reg = ToolFixtures.fullRegistry();
        try (MockServer s = server(steps, bodies)) {
            run(spec(s, reg, new ToolSelection(reg, null)));
            List<String> got = announced(bodies.get(0));
            assertEquals(ToolGroups.CORE_BUILTIN.size() + 1, got.size());
            assertTrue(got.contains(ToolGroups.META_TOOL));
            assertFalse(got.contains("music_play"));
        }
    }

    @Test public void aRoutedGroupIsAnnouncedFromTheFirstStep() throws Exception {
        AtomicInteger steps = new AtomicInteger();
        List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        ToolRegistry reg = ToolFixtures.fullRegistry();
        try (MockServer s = server(steps, bodies)) {
            run(spec(s, reg, new ToolSelection(reg, ToolRouter.route("включи музыку", null, null, false).groups())));
            assertTrue(announced(bodies.get(0)).containsAll(ToolGroups.toolsOf("music")));
        }
    }

    @Test public void theModelSwitchesAGroupOnAndUsesItInTheNextStep() throws Exception {
        AtomicInteger steps = new AtomicInteger(), pauses = new AtomicInteger();
        List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        ToolRegistry reg = counting(pauses);
        try (MockServer s = server(steps, bodies, tool(ToolGroups.META_TOOL, "{\"groups\":[\"music\"]}"), tool("music_pause", "{}"))) {
            AgentOutcome out = run(spec(s, reg, new ToolSelection(reg, null)));
            assertEquals(AgentOutcome.Status.DONE, out.status);
            assertEquals(3, steps.get());
            assertEquals(2, out.toolCalls);
            assertEquals("the real tool ran once", 1, pauses.get());
            assertFalse(announced(bodies.get(0)).contains("music_pause"));
            assertTrue("announced from the step after tools_enable", announced(bodies.get(1)).contains("music_pause"));
            assertTrue(announced(bodies.get(2)).contains("music_pause"));
            String answer = new JSONObject(bodies.get(1)).getJSONArray("messages").toString();
            assertTrue("the model is told what it got: " + answer, answer.contains("music_play") && answer.contains("enabled"));
        }
    }

    @Test public void aHiddenToolStillRunsAndItsGroupIsAnnouncedAfterwards() throws Exception {
        AtomicInteger steps = new AtomicInteger(), pauses = new AtomicInteger();
        List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        ToolRegistry reg = counting(pauses);
        try (MockServer s = server(steps, bodies, tool("music_pause", "{}"))) {
            AgentOutcome out = run(spec(s, reg, new ToolSelection(reg, null)));
            assertEquals(AgentOutcome.Status.DONE, out.status);
            assertEquals("a tool the model was not shown is not refused", 1, pauses.get());
            assertFalse(announced(bodies.get(0)).contains("music_pause"));
            assertTrue(announced(bodies.get(1)).containsAll(ToolGroups.toolsOf("music")));
        }
    }

    @Test public void aWrongGroupNameIsAnswerableAndSwitchesNothingOn() throws Exception {
        AtomicInteger steps = new AtomicInteger();
        List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        ToolRegistry reg = ToolFixtures.fullRegistry();
        try (MockServer s = server(steps, bodies, tool(ToolGroups.META_TOOL, "{\"groups\":[\"музыка\"]}"))) {
            AgentOutcome out = run(spec(s, reg, new ToolSelection(reg, null)));
            assertEquals(AgentOutcome.Status.DONE, out.status);
            assertFalse(announced(bodies.get(1)).contains("music_play"));
            assertTrue(bodies.get(1), bodies.get(1).contains("Нет таких групп"));
        }
    }

    @Test public void theMetaToolIsNotAnswerableWithoutASelection() throws Exception {
        AtomicInteger steps = new AtomicInteger();
        List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        ToolRegistry reg = ToolFixtures.fullRegistry();
        try (MockServer s = server(steps, bodies, tool(ToolGroups.META_TOOL, "{\"groups\":[\"music\"]}"))) {
            AgentOutcome out = run(spec(s, reg, null));
            assertEquals(AgentOutcome.Status.DONE, out.status);
            assertTrue("grouping is off: the tool does not exist and the model is told so", bodies.get(1).contains("Неизвестный инструмент"));
        }
    }

    @Test public void localFinishWaitsForTheRealToolAfterTheMetaCall() throws Exception {
        AtomicInteger steps = new AtomicInteger(), pauses = new AtomicInteger();
        List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        ToolRegistry reg = counting(pauses);
        try (MockServer s = server(steps, bodies, tool(ToolGroups.META_TOOL, "{\"groups\":[\"music\"]}"), tool("music_pause", "{}"))) {
            AgentRunSpec sp = spec(s, reg, new ToolSelection(reg, null));
            sp.localFinish = true;
            AgentOutcome out = run(sp);
            assertEquals(AgentOutcome.Status.DONE, out.status);
            assertEquals("tools_enable has no spoken hint, so the turn goes on; the pause finishes it locally", 2, steps.get());
            assertEquals("Поставил на паузу.", out.finalText);
            assertEquals(1, pauses.get());
        }
    }

    @Test public void everyFifthRequestGoesOutWithEveryToolAndTheOnesBetweenDoNot() throws Exception {
        ToolRegistry reg = ToolFixtures.fullRegistry();
        List<Integer> announcedCount = new ArrayList<>();
        List<Boolean> hadMeta = new ArrayList<>();
        for (int turn = 1; turn <= 5; turn++) {
            AtomicInteger steps = new AtomicInteger();
            List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
            try (MockServer s = server(steps, bodies)) {
                run(spec(s, reg, new ToolSelection(reg, ToolRouter.choose("привет", null, null, false, turn, 5).groups())));
                List<String> names = announced(bodies.get(0));
                announcedCount.add(names.size());
                hadMeta.add(names.contains(ToolGroups.META_TOOL));
            }
        }
        int grouped = ToolGroups.CORE_BUILTIN.size() + 1;
        assertEquals("requests 1-4 grouped, request 5 whole", Arrays.asList(grouped, grouped, grouped, grouped, reg.names().size()), announcedCount);
        assertEquals(Arrays.asList(true, true, true, true, false), hadMeta);
    }

    @Test public void theSelectionReachesTheRequestAsAStableList() throws Exception {
        AtomicInteger steps = new AtomicInteger();
        List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        ToolRegistry reg = ToolFixtures.fullRegistry();
        try (MockServer s = server(steps, bodies, tool("app_state", "{}"))) {
            run(spec(s, reg, new ToolSelection(reg, Arrays.asList("files"))));
            assertEquals("the same set between steps gives the same list", announced(bodies.get(0)), announced(bodies.get(1)));
        }
    }
}
