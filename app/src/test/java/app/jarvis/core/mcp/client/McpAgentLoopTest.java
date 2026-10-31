package app.jarvis.core.mcp.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.MockServer;
import app.jarvis.core.agent.AgentLoop;
import app.jarvis.core.agent.AgentOutcome;
import app.jarvis.core.agent.AgentRunSpec;
import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.LlmConfig;
import app.jarvis.core.llm.OpenAiClient;
import app.jarvis.core.log.Journal;
import app.jarvis.core.util.CancelToken;
import com.sun.net.httpserver.HttpExchange;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The agent loop with a scripted model: lazy mcp_search, then mcp_call on a real external server, then the final answer. */
public class McpAgentLoopTest {
    private FakeMcpServer server;
    private File dir;

    @Before public void setUp() throws Exception {
        server = new FakeMcpServer(FakeMcpServer.Mode.STREAMABLE);
        for (int i = 0; i < 4; i++) server.addToolAndNotify("extra_" + i);
        dir = Files.createTempDirectory("jv-mcp-loop").toFile();
    }

    @After public void tearDown() {
        server.close();
        for (File f : dir.listFiles()) f.delete();
        dir.delete();
    }

    private static String toolCallChunk(String id, String name, String args) {
        return "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\"" + name
            + "\",\"arguments\":" + JSONObject.quote(args) + "}}]}}]}";
    }

    @Test public void lazySearchThenCallThenFinalAnswer() throws Exception {
        final ToolRegistry reg = new ToolRegistry();
        final List<String> asked = new ArrayList<>();
        McpHub hub = new McpHub(new File(dir, "mcp-servers.json"), new McpHub.HeaderStore() {
            @Override public String get(String id) { return ""; }
            @Override public void put(String id, String json) { }
            @Override public void remove(String id) { }
        }, Journal.NOP, reg, new ToolGate() {
            @Override public boolean confirm(String server, String tool, String argKeys, CancelToken cancel) {
                asked.add(tool);
                return true;
            }
        }, McpClientStreamableTest.fast());
        hub.load();
        ServerConfig c = new ServerConfig();
        c.id = "big";
        c.name = "Большой";
        c.url = server.url();
        c.timeoutMs = 5000;
        hub.save(c, null);
        assertEquals(13, hub.testServer("big", new CancelToken()).size());
        assertFalse(reg.has("big__echo"));

        final AtomicInteger step = new AtomicInteger();
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                int n = step.incrementAndGet();
                if (n == 1) {
                    MockServer.sse(x, 0, toolCallChunk("c1", "mcp_search", "{\"query\":\"echo\"}"),
                        "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}", "[DONE]");
                } else if (n == 2) {
                    MockServer.sse(x, 0, toolCallChunk("c2", "mcp_call", "{\"server\":\"big\",\"tool\":\"echo\",\"arguments\":{\"text\":\"привет\"}}"),
                        "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}", "[DONE]");
                } else {
                    MockServer.sse(x, 0, MockServer.chunkText("Сервер ответил: привет"), MockServer.chunkFinish("stop"), "[DONE]");
                }
            }
        })) {
            AgentRunSpec spec = new AgentRunSpec();
            spec.config = new LlmConfig();
            spec.config.baseUrl = s.base();
            spec.config.apiKey = "k";
            spec.config.idleTimeoutMs = 5000;
            spec.model = "m";
            spec.systemPrompt = "sys";
            spec.user = ChatMessage.user("повтори через внешний сервер");
            spec.tools = reg;
            AgentOutcome out = new AgentLoop(new OpenAiClient(Journal.NOP)).run(spec, null, new CancelToken());

            assertEquals(AgentOutcome.Status.DONE, out.status);
            assertEquals("Сервер ответил: привет", out.finalText);
            assertEquals(3, out.steps);
            assertEquals(2, out.toolCalls);

            String first = s.bodies.get(0);
            assertTrue(first.contains("\"mcp_search\""));
            assertTrue(first.contains("\"mcp_call\""));
            assertFalse("direct remote tools must not be in the prompt for a lazy server", first.contains("big__echo"));

            String third = s.bodies.get(2);
            assertTrue("the answer of the external server reaches the model as tool content", third.contains("\\\"content\\\":\\\"привет\\\""));
            assertEquals("[echo]", asked.toString());
        }
        assertTrue(server.callArgs.contains("echo"));
        hub.close();
    }
}
