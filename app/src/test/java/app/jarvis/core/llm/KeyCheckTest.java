package app.jarvis.core.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.MockServer;
import app.jarvis.core.log.Journal;
import app.jarvis.core.util.CancelToken;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class KeyCheckTest {
    private static final String GOOD = "Bearer sk-good-key-123456";

    private static LlmConfig config(String base, String key) {
        LlmConfig c = new LlmConfig();
        c.providerId = "openrouter";
        c.baseUrl = base;
        c.apiKey = key;
        c.maxRetries = 0;
        c.connectTimeoutMs = 3000;
        c.idleTimeoutMs = 3000;
        return c;
    }

    private static List<String> rows = new ArrayList<>();

    private static Journal journal() {
        rows = new ArrayList<>();
        return new Journal() {
            @Override public void log(String category, Journal.Level level, String message, String runId, JSONObject details, long durationMs) {
                rows.add(category + "/" + level + ": " + message);
            }
        };
    }

    @Test public void probePathIsKeyInfoForOpenRouterAndModelsElsewhere() {
        assertEquals("/key", KeyCheck.probePath("openrouter"));
        assertEquals("/models", KeyCheck.probePath("deepseek"));
        assertEquals("/models", KeyCheck.probePath("inception"));
    }

    @Test public void acceptedKeyPassesWithLatencyAndOneJournalLine() throws Exception {
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                if (GOOD.equals(x.getRequestHeaders().getFirst("Authorization"))) MockServer.json(x, 200, "{\"data\":{\"label\":\"t\"}}");
                else MockServer.json(x, 401, "{\"error\":{\"message\":\"Invalid API key\",\"code\":401}}");
            }
        })) {
            Journal j = journal();
            KeyCheck.Result r = KeyCheck.run(new OpenAiClient(j), config(s.base(), "sk-good-key-123456"), "openrouter", j, new CancelToken());
            assertTrue(r.ok);
            assertNull(r.kind);
            assertTrue(r.latencyMs >= 0);
            assertEquals("/key", r.path);
            assertEquals(1, rows.size());
            assertTrue(rows.get(0), rows.get(0).startsWith("llm/INFO: Проверка ключа (openrouter): ключ принят"));
        }
    }

    @Test public void wrongKeyIsAuthWithRussianTextAndOneWarnLine() throws Exception {
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                MockServer.json(x, 401, "{\"error\":{\"message\":\"Invalid API key\",\"code\":401}}");
            }
        })) {
            Journal j = journal();
            KeyCheck.Result r = KeyCheck.run(new OpenAiClient(j), config(s.base(), "sk-wrong-key-000000"), "openrouter", j, new CancelToken());
            assertFalse(r.ok);
            assertEquals(LlmException.Kind.AUTH, r.kind);
            assertEquals(401, r.status);
            assertEquals("Неверный, просроченный или отозванный API-ключ", r.userMessage);
            assertEquals(1, rows.size());
            assertTrue(rows.get(0), rows.get(0).startsWith("llm/WARN: Проверка ключа (openrouter): Неверный"));
            assertTrue(rows.get(0), rows.get(0).contains("[HTTP 401]"));
        }
    }

    @Test public void paymentRequiredIsPaymentKind() throws Exception {
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                MockServer.json(x, 402, "{\"error\":{\"message\":\"Insufficient credits\"}}");
            }
        })) {
            KeyCheck.Result r = KeyCheck.run(new OpenAiClient(journal()), config(s.base(), "k"), "deepseek", journal(), new CancelToken());
            assertEquals(LlmException.Kind.PAYMENT, r.kind);
            assertEquals("/models", r.path);
        }
    }

    @Test public void geoBlockIsGeoKind() throws Exception {
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                MockServer.json(x, 403, "{\"error\":{\"message\":\"unsupported_country_region_territory\"}}");
            }
        })) {
            KeyCheck.Result r = KeyCheck.run(new OpenAiClient(journal()), config(s.base(), "k"), "inception", journal(), new CancelToken());
            assertEquals(LlmException.Kind.GEO_BLOCK, r.kind);
        }
    }

    @Test public void unreachableHostIsNetworkKind() {
        LlmConfig c = config("http://127.0.0.1:9", "k");
        KeyCheck.Result r = KeyCheck.run(new OpenAiClient(journal()), c, "openrouter", journal(), new CancelToken());
        assertFalse(r.ok);
        assertEquals(LlmException.Kind.NETWORK, r.kind);
        assertEquals(1, rows.size());
    }
}
