package app.jarvis.core.agent;

import static org.junit.Assert.assertTrue;

import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.LlmConfig;
import app.jarvis.core.llm.LlmListener;
import app.jarvis.core.llm.LlmRequest;
import app.jarvis.core.llm.LlmResult;
import app.jarvis.core.llm.OpenAiClient;
import app.jarvis.core.llm.Presets;
import app.jarvis.core.util.CancelToken;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;

/** Live check against OpenRouter with the cheap model. Runs only when JV_LIVE_KEY is set; the key is never printed. */
public class SuggestionLiveTest {
    private static final String MODEL = "inception/mercury-3";

    @Test public void realModelAnswersWithUsableChips() throws Exception {
        String key = System.getenv("JV_LIVE_KEY");
        Assume.assumeTrue("set JV_LIVE_KEY to run the live check", key != null && !key.isEmpty());
        LlmConfig cfg = Presets.get("openrouter").toConfig(key);
        SuggestionPrompt.Input in = new SuggestionPrompt.Input();
        in.hour = 9;
        in.weekday = 2;
        in.recentUserLines.add("включи музыку");
        in.recentUserLines.add("какая погода завтра");
        in.memory = "пользователь предпочитает краткие ответы";
        LlmRequest req = new LlmRequest();
        req.model = MODEL;
        req.stream = false;
        req.maxTokens = 120;
        req.temperature = 0.8;
        req.reasoning = "off";
        req.realtime = true;
        req.messages.add(ChatMessage.system(SuggestionPrompt.SYSTEM));
        req.messages.add(ChatMessage.user(SuggestionPrompt.user(in)));
        LlmResult r = new OpenAiClient(null).chat(cfg, req, LlmListener.NOP, new CancelToken());
        List<String> items = SuggestionPrompt.parse(r.content);
        System.out.println("LIVE model=" + r.model + " totalMs=" + r.totalMs + " promptTokens=" + r.promptTokens
            + " completionTokens=" + r.completionTokens + " rawChars=" + r.content.length() + " valid=" + items.size());
        for (String s : items) System.out.println("LIVE chip: " + s);
        assertTrue("raw answer: " + r.content, items.size() >= SuggestionPrompt.MIN_VALID);
    }
}
