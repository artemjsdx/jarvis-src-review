package app.jarvis.core.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import org.json.JSONObject;
import org.junit.Test;

public class ReasoningEffortBodyTest {
    private static LlmRequest request(String level) {
        LlmRequest r = new LlmRequest();
        r.model = "deepseek-flash";
        r.messages.add(ChatMessage.user("hi"));
        r.reasoning = level;
        return r;
    }

    @Test public void deepSeekSendsChosenLevelWithThinkingSwitch() {
        LlmConfig cfg = Presets.get("deepseek").toConfig("k");
        assertTrue(cfg.quirks.sendEffort);
        JSONObject b = OpenAiClient.buildBody(cfg, request("max"), new HashSet<String>(), false);
        assertEquals("enabled", b.getJSONObject("thinking").getString("type"));
        assertEquals("max", b.getString("reasoning_effort"));
    }

    @Test public void offDisablesThinkingAndSendsNoEffort() {
        LlmConfig cfg = Presets.get("deepseek").toConfig("k");
        JSONObject b = OpenAiClient.buildBody(cfg, request("off"), new HashSet<String>(), false);
        assertEquals("disabled", b.getJSONObject("thinking").getString("type"));
        assertFalse(b.has("reasoning_effort"));
    }

    @Test public void otherThinkingProvidersGetNoEffortField() {
        LlmConfig cfg = Presets.get("zai").toConfig("k");
        assertFalse(cfg.quirks.sendEffort);
        JSONObject b = OpenAiClient.buildBody(cfg, request("high"), new HashSet<String>(), false);
        assertTrue(b.has("thinking"));
        assertFalse(b.has("reasoning_effort"));
    }

    @Test public void learnedRefusalOfEffortIsRespected() {
        LlmConfig cfg = Presets.get("deepseek").toConfig("k");
        JSONObject b = OpenAiClient.buildBody(cfg, request("high"), Collections.singleton("reasoning_effort"), false);
        assertFalse(b.has("reasoning_effort"));
        assertTrue(b.has("thinking"));
    }
}
