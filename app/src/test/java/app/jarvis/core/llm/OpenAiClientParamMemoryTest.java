package app.jarvis.core.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.MockServer;
import app.jarvis.core.log.Journal;
import app.jarvis.core.util.CancelToken;
import com.sun.net.httpserver.HttpExchange;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class OpenAiClientParamMemoryTest {
    private static LlmRequest request(String model) {
        LlmRequest r = new LlmRequest();
        r.model = model;
        r.messages.add(ChatMessage.user("hi"));
        r.temperature = 0.7;
        r.stream = false;
        return r;
    }

    private static LlmConfig config(MockServer s) {
        LlmConfig c = new LlmConfig();
        c.providerId = "deepseek";
        c.baseUrl = s.base();
        c.apiKey = "test-key-123456";
        c.maxRetries = 0;
        return c;
    }

    @Test public void refusedTemperatureIsLearnedOncePerModelAndNotSentAgain() throws Exception {
        final List<String> rejectedOnce = new ArrayList<>();
        File f = new File(Files.createTempDirectory("pm").toFile(), "params.json");
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                JSONObject b = new JSONObject(body);
                if (b.has("temperature") && "deepseek-v4-pro".equals(b.optString("model"))) {
                    MockServer.json(x, 400, "{\"error\":{\"message\":\"Unsupported parameter: temperature\",\"param\":\"temperature\",\"type\":\"invalid_request_error\"}}");
                    return;
                }
                MockServer.json(x, 200, "{\"id\":\"r1\",\"model\":\"" + b.optString("model") + "\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
                rejectedOnce.add(b.optString("model"));
            }
        })) {
            final List<String> journalRows = new ArrayList<>();
            Journal j = new Journal() {
                @Override public void log(String category, Journal.Level level, String message, String runId, JSONObject details, long durationMs) {
                    journalRows.add(message);
                }
            };
            ParamMemory memory = new ParamMemory(f);
            OpenAiClient client = new OpenAiClient(j, memory);
            LlmResult first = client.chat(config(s), request("deepseek-v4-pro"), LlmListener.NOP, new CancelToken());
            assertEquals("ok", first.content);
            assertEquals(java.util.Collections.singletonList("temperature"), first.droppedParams);
            assertTrue(journalRows.toString(), journalRows.contains("параметр temperature не поддерживается моделью deepseek-v4-pro: больше не отправляю"));
            assertEquals(java.util.Collections.singleton("temperature"), memory.rejected("deepseek", "deepseek-v4-pro"));

            int sentBefore = s.bodies.size();
            LlmResult second = client.chat(config(s), request("deepseek-v4-pro"), LlmListener.NOP, new CancelToken());
            assertEquals("ok", second.content);
            assertTrue(second.droppedParams.isEmpty());
            assertEquals(sentBefore + 1, s.bodies.size());
            assertFalse(new JSONObject(s.bodies.get(s.bodies.size() - 1)).has("temperature"));

            LlmResult restarted = new OpenAiClient(j, new ParamMemory(f)).chat(config(s), request("deepseek-v4-pro"), LlmListener.NOP, new CancelToken());
            assertTrue(restarted.droppedParams.isEmpty());
            assertFalse(new JSONObject(s.bodies.get(s.bodies.size() - 1)).has("temperature"));

            LlmResult other = client.chat(config(s), request("deepseek-flash"), LlmListener.NOP, new CancelToken());
            assertEquals("ok", other.content);
            assertTrue(new JSONObject(s.bodies.get(s.bodies.size() - 1)).has("temperature"));
        }
    }
}
