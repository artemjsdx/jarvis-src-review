package app.jarvis.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.LlmRequest;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ContextSummarizerTest {
    private static List<ChatMessage> dialog(int turns) {
        List<ChatMessage> h = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            h.add(ChatMessage.user("вопрос номер " + i));
            h.add(ChatMessage.assistant("ответ номер " + i, null));
        }
        return h;
    }

    @Test public void requestIsOneNonStreamingCallWithSystemPromptAndTranscript() {
        LlmRequest r = ContextSummarizer.buildRequest("deepseek-flash", dialog(2), 10_000);
        assertEquals("deepseek-flash", r.model);
        assertFalse(r.stream);
        assertEquals("off", r.reasoning);
        assertEquals(Integer.valueOf(ContextSummarizer.MAX_ANSWER_TOKENS), r.maxTokens);
        assertEquals(2, r.messages.size());
        assertEquals(ChatMessage.SYSTEM, r.messages.get(0).role);
        assertTrue(r.messages.get(0).content.contains("резюме"));
        String t = r.messages.get(1).content;
        assertTrue(t, t.startsWith("Пользователь: вопрос номер 0\nАссистент: ответ номер 0\n"));
        assertTrue(t.endsWith("Ассистент: ответ номер 1"));
    }

    @Test public void transcriptKeepsNewestMessagesWhenBudgetIsShort() {
        String t = ContextSummarizer.transcript(dialog(50), 300);
        assertTrue(t, t.startsWith("[начало переписки опущено]"));
        assertTrue(t, t.endsWith("Ассистент: ответ номер 49"));
        assertTrue(t.length() <= 300 + "[начало переписки опущено]\n".length() + 2);
    }

    @Test public void toolResultsAreShortAndNamed() {
        List<ChatMessage> h = new ArrayList<>();
        h.add(ChatMessage.user("погода"));
        h.add(ChatMessage.tool("c", "web_search", new String(new char[1000]).replace('\0', 'x')));
        String t = ContextSummarizer.transcript(h, 5000);
        assertTrue(t, t.contains("Инструмент web_search: "));
        assertTrue(t.length() < 400);
    }

    @Test public void clipLimitsSummaryToFifteenHundredCharsAtAWordBoundary() {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 3000) sb.append("слово ");
        String c = ContextSummarizer.clip(sb.toString());
        assertTrue(c.length() <= ContextSummarizer.SUMMARY_MAX_CHARS);
        assertTrue(c.endsWith("…"));
        assertFalse(c.contains("  "));
        assertEquals("коротко", ContextSummarizer.clip("  коротко  "));
        assertEquals("", ContextSummarizer.clip(null));
    }
}
