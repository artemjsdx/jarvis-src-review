package app.jarvis.core.skills;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.MockServer;
import app.jarvis.core.agent.AgentListener;
import app.jarvis.core.agent.AgentLoop;
import app.jarvis.core.agent.AgentOutcome;
import app.jarvis.core.agent.AgentRunSpec;
import app.jarvis.core.agent.Prompts;
import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.LlmConfig;
import app.jarvis.core.llm.OpenAiClient;
import app.jarvis.core.llm.ToolCall;
import app.jarvis.core.log.Journal;
import app.jarvis.core.util.CancelToken;
import com.sun.net.httpserver.HttpExchange;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * A scripted model lists skills, views one, patches it; the next request's system prompt must show the patched index.
 * Real loop, real tools, real files, real sockets.
 */
public class SkillTranscriptTest {
    private static final String OLD_DESC = "Используй, когда пользователь просит включить или выключить свет в комнате.";
    private static final String NEW_DESC = "Используй, когда пользователь просит включить или выключить свет в комнате; нужен подключённый ADB.";

    private static String toolCallChunk(String id, String name, String args) {
        return "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\""
            + name + "\",\"arguments\":" + JSONObject.quote(args) + "}}]}}]}";
    }

    private static String system(SkillStore store) {
        Prompts.Info info = new Prompts.Info();
        info.nowText = "среда";
        info.skills = store.systemBlock(1200);
        return Prompts.system(info);
    }

    private static AgentRunSpec spec(MockServer s, String system, String userText, ToolRegistry reg) {
        AgentRunSpec r = new AgentRunSpec();
        r.config = new LlmConfig();
        r.config.baseUrl = s.base();
        r.config.apiKey = "k";
        r.config.idleTimeoutMs = 5000;
        r.model = "m";
        r.systemPrompt = system;
        r.user = ChatMessage.user(userText);
        r.tools = reg;
        return r;
    }

    @Test public void modelListsViewsPatchesAndNextPromptShowsUpdatedIndex() throws Exception {
        File root = Files.createTempDirectory("jv-transcript").toFile();
        SkillStore store = new SkillStore(new File(root, "skills"), Journal.NOP);
        SkillStore.Op create = new SkillStore.Op();
        create.action = "create";
        create.name = "room-lights";
        create.content = "---\nname: room-lights\ndescription: \"" + OLD_DESC + " Работает через ADB-команды.\"\n---\n\n"
            + "## Шаги\n1. adb_shell: `input keyevent 224`\n";
        store.apply(Collections.singletonList(create));
        ToolRegistry reg = new ToolRegistry();
        SkillTools.register(reg, store);

        final JSONObject patchArgs = new JSONObject().put("operations", new JSONArray().put(new JSONObject()
            .put("action", "patch").put("name", "room-lights")
            .put("old_string", OLD_DESC).put("new_string", NEW_DESC)));
        final AtomicInteger step = new AtomicInteger();
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                switch (step.incrementAndGet()) {
                    case 1:
                        MockServer.sse(x, 0, toolCallChunk("c1", "skills_list", "{}"), "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}", "[DONE]");
                        break;
                    case 2:
                        MockServer.sse(x, 0, toolCallChunk("c2", "skill_view", "{\"name\":\"room-lights\"}"), "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}", "[DONE]");
                        break;
                    case 3:
                        MockServer.sse(x, 0, toolCallChunk("c3", "skill_manage", patchArgs.toString()), "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}", "[DONE]");
                        break;
                    default:
                        MockServer.sse(x, 0, MockServer.chunkText("Готово."), MockServer.chunkFinish("stop"), "[DONE]");
                }
            }
        })) {
            final List<String> results = new ArrayList<>();
            final List<String> names = new ArrayList<>();
            AgentListener listener = new AgentListener() {
                @Override public void onToolStart(ToolCall c) { names.add(c.name); }
                @Override public void onToolEnd(ToolCall c, ToolResult r, long ms) { results.add(c.name + ":" + r.ok); }
            };
            AgentOutcome out = new AgentLoop(new OpenAiClient(Journal.NOP))
                .run(spec(s, system(store), "какие у меня навыки про свет?", reg), listener, new CancelToken());
            assertEquals(AgentOutcome.Status.DONE, out.status);
            assertEquals(3, out.toolCalls);
            assertEquals(Arrays.asList("skills_list", "skill_view", "skill_manage"), names);
            assertEquals(Arrays.asList("skills_list:true", "skill_view:true", "skill_manage:true"), results);
            assertTrue(s.bodies.get(1).contains("room-lights"));
            assertTrue(store.read("room-lights", "").contains(NEW_DESC));

            AgentOutcome next = new AgentLoop(new OpenAiClient(Journal.NOP))
                .run(spec(s, system(store), "включи свет", reg), new AgentListener() { }, new CancelToken());
            assertEquals(AgentOutcome.Status.DONE, next.status);
            String lastRequest = s.bodies.get(s.bodies.size() - 1);
            assertTrue("системный промпт следующего запроса должен показывать новое описание", lastRequest.contains(NEW_DESC));
            assertFalse(lastRequest.contains(OLD_DESC + " Работает"));
            assertTrue(lastRequest.contains("НАВЫКИ:"));
        }
    }
}
