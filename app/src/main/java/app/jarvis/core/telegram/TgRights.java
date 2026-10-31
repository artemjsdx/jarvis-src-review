package app.jarvis.core.telegram;

/**
 * What the Telegram MCP server exposes. The upstream server applies one TELEGRAM_EXPOSED_TOOLS value to all accounts,
 * so the choice is global; {@link #apiValue} is what login.py accepts on POST /rights.
 */
public enum TgRights {
    READ("read", "Чтение"),
    SEND("send", "Чтение и отправка"),
    ALL("all", "Всё");

    public final String apiValue;
    public final String title;

    TgRights(String apiValue, String title) {
        this.apiValue = apiValue;
        this.title = title;
    }

    /** Reads the value the server reports back (TELEGRAM_EXPOSED_TOOLS). Unknown or empty means everything. */
    public static TgRights fromExposed(String exposed) {
        if (exposed == null || exposed.isEmpty()) return ALL;
        if (exposed.equals("read-only")) return READ;
        if (exposed.startsWith("read-only+")) return SEND;
        return ALL;
    }
}
