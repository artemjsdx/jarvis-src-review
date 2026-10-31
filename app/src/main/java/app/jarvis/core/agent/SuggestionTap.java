package app.jarvis.core.agent;

/**
 * A tap on a home chip is an ordinary user message: the chip text goes to the agent exactly as if it were typed.
 * Nothing else is sent (no attachments, no direct-command shortcut).
 */
public final class SuggestionTap {
    public static final String SOURCE = "text";

    public interface Sink {
        void submit(String text, String source);
    }

    private SuggestionTap() { }

    public static void tap(String chip, Sink sink) {
        if (chip == null || chip.trim().isEmpty()) return;
        sink.submit(chip, SOURCE);
    }
}
