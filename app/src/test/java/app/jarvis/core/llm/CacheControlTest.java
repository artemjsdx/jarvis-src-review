package app.jarvis.core.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class CacheControlTest {
    private static LlmRequest req(String model) {
        LlmRequest r = new LlmRequest();
        r.model = model;
        r.messages.add(ChatMessage.system("Системный текст"));
        r.messages.add(ChatMessage.user("привет"));
        r.messages.add(ChatMessage.assistant("Здравствуйте!", null));
        r.messages.add(ChatMessage.user("который час\n\n[контекст: сейчас: 10:41]"));
        r.cacheBreakpoint = 2;
        r.tools.add(new ToolSpec("t", "d", null));
        return r;
    }

    private static LlmConfig cfg(boolean flag) {
        LlmConfig c = new LlmConfig();
        c.quirks.cacheControl = flag;
        return c;
    }

    private static JSONObject body(LlmConfig c, LlmRequest r) {
        return OpenAiClient.buildBody(c, r, Collections.<String>emptySet(), false);
    }

    @Test public void anthropicModelOnCapableProviderGetsTwoBreakpoints() {
        JSONObject b = body(cfg(true), req("anthropic/claude-haiku-5.5"));
        JSONArray m = b.getJSONArray("messages");
        JSONObject sys = m.getJSONObject(0).getJSONArray("content").getJSONObject(0);
        assertEquals("text", sys.getString("type"));
        assertEquals("Системный текст", sys.getString("text"));
        assertEquals("ephemeral", sys.getJSONObject("cache_control").getString("type"));
        // the last stable history message (assistant), not the current user turn
        assertEquals("ephemeral", m.getJSONObject(2).getJSONArray("content").getJSONObject(0).getJSONObject("cache_control").getString("type"));
        assertTrue(m.getJSONObject(1).get("content") instanceof String);
        assertTrue(m.getJSONObject(3).get("content") instanceof String);
        assertTrue(b.has("tools"));
    }

    @Test public void otherModelsAndProvidersAreUntouched() {
        String plain = body(cfg(false), req("anthropic/claude-haiku-5.5")).toString();
        assertFalse(plain.contains("cache_control"));
        assertFalse(body(cfg(true), req("deepseek/deepseek-v4.1-flash")).toString().contains("cache_control"));
        assertFalse(body(cfg(true), req("inception/mercury-3")).toString().contains("cache_control"));
        JSONArray m = body(cfg(true), req("deepseek/deepseek-v4.1-flash")).getJSONArray("messages");
        for (int i = 0; i < m.length(); i++) assertTrue(m.getJSONObject(i).get("content") instanceof String);
    }

    @Test public void breakpointSkipsMessagesWithoutPlainText() {
        LlmRequest r = new LlmRequest();
        r.model = "anthropic/x";
        r.messages.add(ChatMessage.system("s"));
        r.messages.add(ChatMessage.user("u1"));
        r.messages.add(ChatMessage.assistant("", Collections.singletonList(new ToolCall("c1", "t", "{}"))));
        r.messages.add(ChatMessage.tool("c1", "t", "{\"ok\":true}"));
        r.messages.add(ChatMessage.user("u2"));
        r.cacheBreakpoint = 3;
        JSONArray m = body(cfg(true), r).getJSONArray("messages");
        assertTrue(m.getJSONObject(3).get("content") instanceof JSONArray);       // tool result has text content
        r.cacheBreakpoint = 2;                                                    // assistant with only tool_calls: walks back to u1
        m = body(cfg(true), r).getJSONArray("messages");
        assertFalse(m.getJSONObject(2).has("content") && m.getJSONObject(2).get("content") instanceof JSONArray);
        assertTrue(m.getJSONObject(1).get("content") instanceof JSONArray);
    }

    @Test public void openRouterPresetAdvertisesTheCapability() {
        assertTrue(Presets.get("openrouter").toConfig("k").quirks.cacheControl);
        assertFalse(Presets.get("deepseek").toConfig("k").quirks.cacheControl);
        assertTrue(Presets.get("openrouter").toConfig("k").copy().quirks.cacheControl);
        assertEquals(new HashSet<String>().size(), 0);
    }
}
