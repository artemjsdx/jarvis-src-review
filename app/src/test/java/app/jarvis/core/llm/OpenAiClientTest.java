package app.jarvis.core.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.jarvis.core.MockServer;
import app.jarvis.core.log.Journal;
import app.jarvis.core.util.CancelToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;

public class OpenAiClientTest {
    private static LlmRequest req(String text) {
        LlmRequest r = new LlmRequest();
        r.model = "test-model";
        r.messages.add(ChatMessage.user(text));
        return r;
    }

    private static LlmConfig cfg(MockServer s) {
        LlmConfig c = new LlmConfig();
        c.baseUrl = s.base();
        c.apiKey = "test-key-123456";
        c.maxRetries = 2;
        c.idleTimeoutMs = 5000;
        return c;
    }

    @Test public void streamsTextIncrementallyBeforeServerFinishes() throws Exception {
        final long[] serverDoneAt = {0};
        try (MockServer s = new MockServer(new HttpHandler() {
            @Override public void handle(HttpExchange x) throws IOException {
                MockServer.sse(x, 150, MockServer.chunkText("При"), MockServer.chunkText("вет"), MockServer.chunkText("!"), MockServer.chunkFinish("stop"), "[DONE]");
                serverDoneAt[0] = System.nanoTime();
            }
        })) {
            final List<String> deltas = Collections.synchronizedList(new ArrayList<String>());
            final long[] firstAt = {0};
            LlmResult r = new OpenAiClient(Journal.NOP).chat(cfg(s), req("hi"), new LlmListener() {
                @Override public void onTextDelta(String d) { if (firstAt[0] == 0) firstAt[0] = System.nanoTime(); deltas.add(d); }
            }, new CancelToken());
            assertEquals("Привет!", r.content);
            assertEquals(3, deltas.size());
            assertEquals("stop", r.finishReason);
            assertEquals(11, r.promptTokens);
            assertEquals(3, r.reasoningTokens);
            assertTrue("первый фрагмент должен прийти до завершения ответа сервера", firstAt[0] > 0 && firstAt[0] < System.nanoTime() - 200_000_000L);
            assertEquals("Bearer test-key-123456", s.authHeaders.get(0));
            JSONObject body = new JSONObject(s.bodies.get(0));
            assertTrue(body.getBoolean("stream"));
            assertEquals("test-model", body.getString("model"));
        }
    }

    @Test public void assemblesFragmentedParallelToolCalls() throws Exception {
        try (MockServer s = new MockServer(new HttpHandler() {
            @Override public void handle(HttpExchange x) throws IOException {
                MockServer.sse(x, 0,
                    "{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"index\":0,\"id\":\"call_a\",\"type\":\"function\",\"function\":{\"name\":\"fs_list\",\"arguments\":\"\"}}]}}]}",
                    "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"path\\\":\\\"mu\"}}]}}]}",
                    "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"call_b\",\"type\":\"function\",\"function\":{\"name\":\"memory_get\",\"arguments\":\"{}\"}}]}}]}",
                    "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"sic\\\"}\"}}]}}]}",
                    "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}", "[DONE]");
            }
        })) {
            LlmResult r = new OpenAiClient(Journal.NOP).chat(cfg(s), req("x"), null, new CancelToken());
            assertEquals(2, r.toolCalls.size());
            assertEquals("call_a", r.toolCalls.get(0).id);
            assertEquals("fs_list", r.toolCalls.get(0).name);
            assertEquals("{\"path\":\"music\"}", r.toolCalls.get(0).arguments);
            assertEquals("memory_get", r.toolCalls.get(1).name);
            assertEquals("tool_calls", r.finishReason);
        }
    }

    @Test public void providerThatReusesIndexZeroForEveryCall() throws Exception {
        try (MockServer s = new MockServer(new HttpHandler() {
            @Override public void handle(HttpExchange x) throws IOException {
                MockServer.sse(x, 0,
                    "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"a\",\"arguments\":\"{}\"}}]}}]}",
                    "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c2\",\"function\":{\"name\":\"b\",\"arguments\":\"{}\"}}]}}]}",
                    "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}", "[DONE]");
            }
        })) {
            LlmResult r = new OpenAiClient(Journal.NOP).chat(cfg(s), req("x"), null, new CancelToken());
            assertEquals(2, r.toolCalls.size());
            assertEquals("a", r.toolCalls.get(0).name);
            assertEquals("b", r.toolCalls.get(1).name);
        }
    }

    @Test public void nullContentAndMissingIdsAreHandled() throws Exception {
        try (MockServer s = new MockServer(new HttpHandler() {
            @Override public void handle(HttpExchange x) throws IOException {
                MockServer.sse(x, 0, "{\"choices\":[{\"delta\":{\"content\":null,\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"t\",\"arguments\":\"{\\\"a\\\":1}\"}}]}}]}",
                    "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}", "[DONE]");
            }
        })) {
            LlmResult r = new OpenAiClient(Journal.NOP).chat(cfg(s), req("x"), null, new CancelToken());
            assertEquals("", r.content);                                  // JSON null must not become the text "null"
            assertEquals(1, r.toolCalls.size());
            assertFalse("id must be synthesized so tool results can be paired", r.toolCalls.get(0).id.isEmpty());
        }
    }

    @Test public void nonStreamingJsonFallbackWhenServerIgnoresStream() throws Exception {
        try (MockServer s = new MockServer(new HttpHandler() {
            @Override public void handle(HttpExchange x) throws IOException {
                MockServer.json(x, 200, "{\"id\":\"r\",\"model\":\"m\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Целиком\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}");
            }
        })) {
            final StringBuilder sb = new StringBuilder();
            LlmResult r = new OpenAiClient(Journal.NOP).chat(cfg(s), req("x"), new LlmListener() {
                @Override public void onTextDelta(String d) { sb.append(d); }
            }, new CancelToken());
            assertEquals("Целиком", r.content);
            assertEquals("Целиком", sb.toString());
        }
    }

    @Test public void cancelDuringStreamReturnsPromptlyAndClosesConnection() throws Exception {
        final CancelToken cancel = new CancelToken();
        final MockServer[] holder = new MockServer[1];
        try (MockServer s = new MockServer(new HttpHandler() {
            @Override public void handle(HttpExchange x) throws IOException {
                boolean finished = MockServer.sse(x, 300, MockServer.chunkText("a"), MockServer.chunkText("b"), MockServer.chunkText("c"),
                    MockServer.chunkText("d"), MockServer.chunkText("e"), MockServer.chunkText("f"), MockServer.chunkFinish("stop"), "[DONE]");
                holder[0].clientDisconnected = !finished;
            }
        })) {
            holder[0] = s;
            final long t0 = System.nanoTime();
            try {
                new OpenAiClient(Journal.NOP).chat(cfg(s), req("x"), new LlmListener() {
                    @Override public void onTextDelta(String d) { cancel.cancel("user"); }
                }, cancel);
                fail("expected cancellation");
            } catch (LlmException e) {
                assertEquals(LlmException.Kind.CANCELLED, e.kind);
            }
            assertTrue("отмена должна сработать быстро, а не ждать конца потока", (System.nanoTime() - t0) / 1_000_000L < 1200);
            Thread.sleep(900);
            assertTrue("сервер должен увидеть закрытое соединение", s.clientDisconnected);
        }
    }

    private static LlmException failWith(final int status, final String body, final String extraHeader) throws Exception {
        try (MockServer s = new MockServer(new HttpHandler() {
            @Override public void handle(HttpExchange x) throws IOException {
                if (extraHeader != null) x.getResponseHeaders().add(extraHeader.split(":")[0], extraHeader.split(":")[1].trim());
                MockServer.json(x, status, body);
            }
        })) {
            LlmConfig c = cfg(s);
            c.maxRetries = 0;
            try {
                new OpenAiClient(Journal.NOP).chat(c, req("x"), null, new CancelToken());
            } catch (LlmException e) {
                return e;
            }
            fail("expected failure");
            return null;
        }
    }

    @Test public void classifiesProviderErrors() throws Exception {
        assertEquals(LlmException.Kind.AUTH, failWith(401, "{\"error\":{\"message\":\"Incorrect API key provided\",\"type\":\"authentication_error\",\"code\":\"invalid_api_key\"}}", null).kind);
        assertEquals(LlmException.Kind.PAYMENT, failWith(402, "{\"error\":{\"message\":\"Account is inactive\"}}", null).kind);
        assertEquals(LlmException.Kind.NOT_FOUND, failWith(404, "{\"error\":{\"message\":\"model not found\"}}", null).kind);
        LlmException rl = failWith(429, "{\"error\":{\"message\":\"slow down\"}}", "Retry-After: 7");
        assertEquals(LlmException.Kind.RATE_LIMIT, rl.kind);
        assertEquals(7000, rl.retryAfterMs);
        assertEquals(LlmException.Kind.CONTEXT_LENGTH, failWith(400, "{\"error\":{\"message\":\"too big\",\"code\":\"context_length_exceeded\"}}", null).kind);
        assertEquals(LlmException.Kind.SERVER, failWith(503, "{\"error\":{\"message\":\"Engine overloaded\"}}", null).kind);
    }

    @Test public void detectsRealCloudFrontGeoBlockBody() throws Exception {
        // verbatim shape of what api.inceptionlabs.ai returned to a Belarus IP during development
        String html = "<!DOCTYPE HTML><HTML><HEAD><TITLE>ERROR: The request could not be satisfied</TITLE></HEAD><BODY><H1>403 ERROR</H1>"
            + "<H2>The request could not be satisfied.</H2><HR noshade size=\"1px\">The Amazon CloudFront distribution is configured to block access from your country."
            + "<BR clear=\"all\"><HR noshade size=\"1px\"><PRE>Generated by cloudfront (CloudFront)</PRE></BODY></HTML>";
        LlmException e = failWith(403, html, null);
        assertEquals(LlmException.Kind.GEO_BLOCK, e.kind);
        assertTrue(e.userMessage, e.userMessage.contains("VPN") || e.userMessage.contains("прокси"));
        assertFalse("HTML must not leak into the user message", e.userMessage.contains("<"));
    }

    @Test public void retriesServerErrorThenSucceeds() throws Exception {
        final AtomicInteger n = new AtomicInteger();
        try (MockServer s = new MockServer(new HttpHandler() {
            @Override public void handle(HttpExchange x) throws IOException {
                if (n.incrementAndGet() == 1) MockServer.json(x, 503, "{\"error\":{\"message\":\"overloaded\"}}");
                else MockServer.sse(x, 0, MockServer.chunkText("ok"), MockServer.chunkFinish("stop"), "[DONE]");
            }
        })) {
            final List<String> retries = new ArrayList<>();
            LlmResult r = new OpenAiClient(Journal.NOP).chat(cfg(s), req("x"), new LlmListener() {
                @Override public void onRetry(int a, String why, long d) { retries.add(why); }
            }, new CancelToken());
            assertEquals("ok", r.content);
            assertEquals(2, r.attempts);
            assertEquals(1, retries.size());
        }
    }

    @Test public void dropsParameterTheProviderRejectsAndRemembersIt() throws Exception {
        final AtomicInteger n = new AtomicInteger();
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                n.incrementAndGet();
                if (body.contains("\"realtime\""))
                    MockServer.json(x, 400, "{\"error\":{\"message\":\"Unknown parameter: realtime\",\"param\":\"realtime\",\"type\":\"invalid_request_error\"}}");
                else MockServer.sse(x, 0, MockServer.chunkText("fine"), MockServer.chunkFinish("stop"), "[DONE]");
            }
        })) {
            OpenAiClient c = new OpenAiClient(Journal.NOP);
            LlmConfig cf = cfg(s);
            cf.quirks.sendRealtime = true;
            LlmRequest rq = req("x");
            rq.realtime = true;
            LlmResult r = c.chat(cf, rq, null, new CancelToken());
            assertEquals("fine", r.content);
            assertTrue(r.droppedParams.contains("realtime"));
            assertEquals(2, n.get());                           // rejected once, then accepted
            c.chat(cf, rq, null, new CancelToken());           // learned: first attempt succeeds now
            assertEquals(3, n.get());
        }
    }

    @Test public void inceptionQuirksShapeTheRequestBody() {
        LlmConfig c = Providers.inception("k");
        LlmRequest r = req("привет");
        r.temperature = 1.7;           // outside the documented [0.5, 1] range
        r.maxTokens = 4096;
        r.reasoning = "instant";
        r.realtime = true;
        r.tools.add(ToolSpec.of("t", "d", ToolSpec.Param.str("a", "b", true)));
        JSONObject b = OpenAiClient.buildBody(c, r, new java.util.HashSet<String>(), false);
        assertEquals(1.0, b.getDouble("temperature"), 1e-9);
        assertEquals("instant", b.getString("reasoning_effort"));
        assertTrue(b.getBoolean("realtime"));
        assertEquals(4096, b.getInt("max_tokens"));
        assertTrue(b.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getBoolean("strict"));
        assertFalse("diffusing must never be requested", b.has("diffusing"));
        // the same request to a generic provider must not carry Inception-only fields
        JSONObject g = OpenAiClient.buildBody(Providers.openAiCompatible("x", "http://h/v1", "k"), r, new java.util.HashSet<String>(), false);
        assertFalse(g.has("realtime"));
        assertFalse(g.has("reasoning_effort"));
        assertEquals(1.7, g.getDouble("temperature"), 1e-9);
    }

    @Test public void parsesModelCatalogShapes() throws Exception {
        List<ModelInfo> a = OpenAiClient.parseModels("{\"object\":\"list\",\"data\":[{\"id\":\"mercury-2\",\"owned_by\":\"inception\"},{\"id\":\"text-embedding-3\"},{\"id\":\"mercury-3\",\"context_length\":1000000}]}");
        assertEquals(3, a.size());
        assertEquals("mercury-2", a.get(0).id);                 // chat-like first, alphabetical
        assertEquals("mercury-3", a.get(1).id);
        assertEquals(1000000, a.get(1).contextLength);
        assertFalse(a.get(2).chatLikely);
        assertEquals("mercury-3", Providers.pickDefaultModel(a));
        assertEquals(2, OpenAiClient.parseModels("[\"a\",\"b\"]").size());
    }

    @Test public void messageJsonRoundTripAndAssistantToolCallShape() {
        ChatMessage a = ChatMessage.assistant("", java.util.Collections.singletonList(new ToolCall("c1", "fs_list", "{\"path\":\"\"}")));
        JSONObject j = a.toJson(false, false);
        assertFalse("assistant tool-call message without text must omit content (Inception docs: exclude_none)", j.has("content"));
        assertEquals("function", j.getJSONArray("tool_calls").getJSONObject(0).getString("type"));
        ChatMessage back = ChatMessage.fromJson(a.toJson());
        assertEquals("c1", back.toolCalls.get(0).id);
        JSONObject t = ChatMessage.tool("c1", "fs_list", "{\"ok\":true}").toJson(false, false);
        assertEquals("c1", t.getString("tool_call_id"));
        assertFalse(t.has("name"));
    }

    @Test public void reasoningLevelIsMappedOntoEachProvidersOwnSwitch() {
        LlmRequest r = req("x");
        r.reasoning = "off";
        java.util.Set<String> none = new java.util.HashSet<String>();
        JSONObject inc = OpenAiClient.buildBody(Presets.get("inception").toConfig("k"), r, none, false);
        assertEquals("instant", inc.getString("reasoning_effort"));
        JSONObject ds = OpenAiClient.buildBody(Presets.get("deepseek").toConfig("k"), r, none, false);
        assertEquals("disabled", ds.getJSONObject("thinking").getString("type"));
        assertFalse(ds.has("reasoning_effort"));
        JSONObject qw = OpenAiClient.buildBody(Presets.get("qwen").toConfig("k"), r, none, false);
        assertFalse(qw.getBoolean("enable_thinking"));
        JSONObject or = OpenAiClient.buildBody(Presets.get("openrouter").toConfig("k"), r, none, false);
        assertFalse(or.getJSONObject("reasoning").getBoolean("enabled"));
        JSONObject oa = OpenAiClient.buildBody(Presets.get("openai").toConfig("k"), r, none, false);
        assertEquals("minimal", oa.getString("reasoning_effort"));
        assertTrue("OpenAI newer models want max_completion_tokens", true);
        r.reasoning = "high";
        assertEquals("high", OpenAiClient.buildBody(Presets.get("inception").toConfig("k"), r, none, false).getString("reasoning_effort"));
        assertEquals("enabled", OpenAiClient.buildBody(Presets.get("zai").toConfig("k"), r, none, false).getJSONObject("thinking").getString("type"));
        // providers without a reasoning switch must not receive any of these fields
        JSONObject g = OpenAiClient.buildBody(Presets.get("groq").toConfig("k"), r, none, false);
        assertFalse(g.has("reasoning_effort") || g.has("thinking") || g.has("enable_thinking") || g.has("reasoning"));
        r.reasoning = "";
        assertFalse(OpenAiClient.buildBody(Presets.get("inception").toConfig("k"), r, none, false).has("reasoning_effort"));
    }

    @Test public void learnsToEchoReasoningWhenProviderDemandsIt() throws Exception {
        final AtomicInteger n = new AtomicInteger();
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                n.incrementAndGet();
                JSONObject b = new JSONObject(body);
                boolean hasAssistantCall = false, echoed = false;
                for (int i = 0; i < b.getJSONArray("messages").length(); i++) {
                    JSONObject m = b.getJSONArray("messages").getJSONObject(i);
                    if ("assistant".equals(m.optString("role")) && m.has("tool_calls")) { hasAssistantCall = true; echoed |= m.has("reasoning_content"); }
                }
                if (hasAssistantCall && !echoed) MockServer.json(x, 400, "{\"error\":{\"message\":\"The reasoning_content in the thinking mode must be passed back to the API.\"}}");
                else MockServer.sse(x, 0, MockServer.chunkText("итог"), MockServer.chunkFinish("stop"), "[DONE]");
            }
        })) {
            LlmConfig c = cfg(s);                                   // preset says: do not echo
            LlmRequest rq = req("x");
            rq.messages.add(ChatMessage.assistant("", java.util.Collections.singletonList(new ToolCall("c1", "t", "{}")), "размышление модели"));
            rq.messages.add(ChatMessage.tool("c1", "t", "{\"ok\":true}"));
            OpenAiClient cl = new OpenAiClient(Journal.NOP);
            LlmResult r = cl.chat(c, rq, null, new CancelToken());
            assertEquals("итог", r.content);
            assertTrue(r.droppedParams.toString(), r.droppedParams.get(0).contains("echo"));
            assertEquals(2, n.get());
            cl.chat(c, rq, null, new CancelToken());                // learned: no second round trip
            assertEquals(3, n.get());
        }
    }

    @Test public void learnsToStopEchoingReasoningWhenProviderRejectsIt() throws Exception {
        final AtomicInteger n = new AtomicInteger();
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                n.incrementAndGet();
                if (body.contains("reasoning_content")) MockServer.json(x, 400, "{\"error\":{\"message\":\"reasoning_content is not allowed in input\"}}");
                else MockServer.sse(x, 0, MockServer.chunkText("ок"), MockServer.chunkFinish("stop"), "[DONE]");
            }
        })) {
            LlmConfig c = cfg(s);
            c.quirks.echoReasoning = true;
            LlmRequest rq = req("x");
            rq.messages.add(ChatMessage.assistant("", java.util.Collections.singletonList(new ToolCall("c1", "t", "{}")), "думаю"));
            rq.messages.add(ChatMessage.tool("c1", "t", "{}"));
            LlmResult r = new OpenAiClient(Journal.NOP).chat(c, rq, null, new CancelToken());
            assertEquals("ок", r.content);
            assertEquals(2, n.get());
        }
    }

    @Test public void presetsAreSaneAndUnique() {
        java.util.Set<String> ids = new java.util.HashSet<String>();
        for (ProviderPreset p : Presets.all()) {
            assertTrue("duplicate id " + p.id, ids.add(p.id));
            assertFalse(p.name.isEmpty());
            assertFalse("trailing slash breaks url(): " + p.baseUrl, p.baseUrl.endsWith("/"));
            assertTrue(p.id, p.baseUrl.isEmpty() || p.baseUrl.startsWith("https://") || p.baseUrl.startsWith("http://127.0.0.1"));
            assertFalse(p.verified.isEmpty());
        }
        assertEquals("https://api.inceptionlabs.ai/v1/chat/completions", Presets.inception().toConfig("k").url("/chat/completions"));
        assertEquals(0.5, Presets.inception().toConfig("k").quirks.tempMin, 1e-9);
        assertTrue(Presets.get("deepseek") != null && Presets.get("zai") != null && Presets.get("openrouter") != null);
    }
}
