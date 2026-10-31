package app.jarvis.core.agent;

import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.LlmRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds the one-shot request that condenses the older part of a conversation before a model switch. */
public final class ContextSummarizer {
    public static final int SUMMARY_MAX_CHARS = 1500;
    public static final int MAX_ANSWER_TOKENS = 600;
    public static final String SYSTEM_PROMPT = "Ты сжимаешь переписку для памяти ассистента. Напиши по-русски краткое резюме: "
        + "о чём договорились, какие факты и числа важны, что осталось открытым. Не больше 1200 знаков. Без вступления и без списка извинений.";

    private ContextSummarizer() { }

    /**
     * A single non-streaming request for {@code model}. The transcript keeps the newest messages that fit in {@code maxInputChars};
     * older ones are dropped and the transcript says so.
     */
    public static LlmRequest buildRequest(String model, List<ChatMessage> older, int maxInputChars) {
        LlmRequest r = new LlmRequest();
        r.model = model;
        r.stream = false;
        r.temperature = 0.2;
        r.maxTokens = MAX_ANSWER_TOKENS;
        r.reasoning = "off";
        r.messages = new ArrayList<>();
        r.messages.add(ChatMessage.system(SYSTEM_PROMPT));
        r.messages.add(ChatMessage.user(transcript(older, maxInputChars)));
        return r;
    }

    /** "Пользователь: …" / "Ассистент: …" / "Инструмент …: …" lines, newest kept when the budget is short. */
    public static String transcript(List<ChatMessage> messages, int maxChars) {
        List<String> lines = new ArrayList<>();
        int used = 0;
        boolean cut = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            String line = line(messages.get(i));
            if (line == null) continue;
            if (used + line.length() > maxChars) { cut = true; break; }
            lines.add(line);
            used += line.length() + 1;
        }
        Collections.reverse(lines);
        StringBuilder sb = new StringBuilder();
        if (cut) sb.append("[начало переписки опущено]\n");
        for (String s : lines) sb.append(s).append('\n');
        return sb.toString().trim();
    }

    /** At most {@link #SUMMARY_MAX_CHARS} characters, cut at a word boundary when possible. */
    public static String clip(String summary) {
        String s = summary == null ? "" : summary.trim();
        if (s.length() <= SUMMARY_MAX_CHARS) return s;
        String head = s.substring(0, SUMMARY_MAX_CHARS - 1);
        int sp = head.lastIndexOf(' ');
        if (sp > SUMMARY_MAX_CHARS / 2) head = head.substring(0, sp);
        return head.trim() + "…";
    }

    private static String line(ChatMessage m) {
        if (ChatMessage.USER.equals(m.role)) return text("Пользователь: ", m.content, 2000);
        if (ChatMessage.ASSISTANT.equals(m.role)) return m.content == null || m.content.trim().isEmpty() ? null : text("Ассистент: ", m.content, 2000);
        if (ChatMessage.TOOL.equals(m.role)) return text("Инструмент " + (m.name == null ? "" : m.name) + ": ", m.content, 300);
        return null;
    }

    private static String text(String prefix, String s, int max) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        if (t.length() > max) t = t.substring(0, max) + "…";
        return prefix + t;
    }
}
