package app.jarvis.core.agent;

import app.jarvis.core.llm.Cap;
import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.ModelCaps;
import app.jarvis.core.llm.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Whether the visible history of a conversation can be sent to another model, and what the switch would lose.
 * Tokens are estimated from characters (about 3.2 characters per token for mixed Russian and English text).
 */
public final class ContextFit {
    public static final double CHARS_PER_TOKEN = 3.2;
    /** Context budget the app never exceeds, whatever the model's window. */
    public static final int MAX_BUDGET_TOKENS = 200_000;
    /** Room kept free for the answer and the system prompt when the history is checked. */
    public static final int RESERVE_TOKENS = 4_000;
    /** Character budget used when the model's window is not reported (the old fixed default). */
    public static final int DEFAULT_CHARS = 60_000;

    private ContextFit() { }

    public static final class Result {
        public final int estimatedTokens;
        /** Window of the target model in tokens; 0 = not reported. */
        public final int windowTokens;
        public final boolean fits;
        public final boolean toolsLost;
        public final boolean toolsUnknown;
        public final boolean imagesLost;
        public final boolean reasoningLost;
        public final boolean realtimeOnly;

        Result(int est, int window, boolean fits, boolean toolsLost, boolean toolsUnknown, boolean imagesLost, boolean reasoningLost, boolean realtimeOnly) {
            this.estimatedTokens = est; this.windowTokens = window; this.fits = fits; this.toolsLost = toolsLost; this.toolsUnknown = toolsUnknown;
            this.imagesLost = imagesLost; this.reasoningLost = reasoningLost; this.realtimeOnly = realtimeOnly;
        }

        /** The switch is refused: the model cannot hold a text chat at all. */
        public boolean refused() { return realtimeOnly; }

        /** The user must confirm: something will be cut or lost. Unknown tool support alone does not ask. */
        public boolean needsDialog() { return !refused() && (!fits || toolsLost || imagesLost || reasoningLost); }
    }

    public static Result check(List<ChatMessage> history, boolean historyHasImages, ModelCaps target, ModelCaps current) {
        int est = estimateTokens(history);
        int window = target.contextTokens;
        boolean fits = window <= 0 || est + RESERVE_TOKENS <= Math.min(window, MAX_BUDGET_TOKENS);
        boolean usesTools = false;
        for (ChatMessage m : history) if (ChatMessage.TOOL.equals(m.role) || m.hasToolCalls()) { usesTools = true; break; }
        boolean toolsLost = usesTools && target.tools == Cap.NO;
        boolean toolsUnknown = usesTools && target.tools == Cap.UNKNOWN;
        boolean imagesLost = historyHasImages && target.vision == Cap.NO;
        boolean reasoningLost = current != null && current.reasoning == Cap.YES && target.reasoning == Cap.NO;
        return new Result(est, window, fits, toolsLost, toolsUnknown, imagesLost, reasoningLost, target.realtimeOnly());
    }

    /** Characters of everything a model would receive from this history (content, tool calls and their arguments). */
    public static int historyChars(List<ChatMessage> history) {
        long n = 0;
        for (ChatMessage m : history) {
            if (m.content != null) n += m.content.length();
            if (m.toolCalls != null) for (ToolCall t : m.toolCalls) n += t.name.length() + t.arguments.length();
        }
        return (int) Math.min(Integer.MAX_VALUE, n);
    }

    /** Reasons for a switch dialog or an MCP warning, in plain Russian. Empty when nothing is lost. */
    public static List<String> describe(Result r, String model) {
        List<String> out = new ArrayList<>();
        if (!r.fits) out.add("Переписка ≈ " + tokens(r.estimatedTokens) + " токенов, окно «" + model + "» — "
            + tokens(r.windowTokens) + " токенов. Модель увидит только последние сообщения; старые останутся в чате, но в запрос не попадут.");
        if (r.toolsLost) out.add("У модели нет инструментов: команды и поиск перестанут работать, а вызовы из переписки станут непонятными.");
        if (r.imagesLost) out.add("В переписке есть картинки, а модель их не принимает.");
        if (r.reasoningLost) out.add("У модели нет режима рассуждений: выбранный уровень не будет применяться.");
        return out;
    }

    /** 1 048 576 → "1,0 млн", 131 072 → "131 тыс.", 512 → "512". */
    public static String tokens(long n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1f", n / 1_000_000.0).replace('.', ',') + " млн";
        if (n >= 1_000) return (n / 1000) + " тыс.";
        return String.valueOf(n);
    }

    public static int estimateTokens(List<ChatMessage> history) {
        return (int) Math.ceil(historyChars(history) / CHARS_PER_TOKEN);
    }

    /**
     * The "Авто" context size in characters: the model's window, capped at {@link #MAX_BUDGET_TOKENS}, minus the room kept for
     * the answer. Unknown windows fall back to {@link #DEFAULT_CHARS}.
     */
    public static int autoContextChars(int windowTokens, int answerTokens) {
        if (windowTokens <= 0) return DEFAULT_CHARS;
        int usable = Math.min(windowTokens, MAX_BUDGET_TOKENS) - Math.max(0, answerTokens);
        return (int) Math.max(8_000, Math.min(usable * CHARS_PER_TOKEN, 800_000));
    }
}
