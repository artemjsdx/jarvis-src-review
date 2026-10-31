package app.jarvis.core.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** When the home chips are regenerated, and the static chips shown without a model. Pure Java. */
public final class SuggestionPolicy {
    public static final long TTL_MS = 10 * 60_000L, AFTER_RUN_MS = 8_000L, HOUR_MS = 3_600_000L;
    public static final int MAX_CALLS_PER_HOUR = 6;

    private SuggestionPolicy() { }

    /** True when the chips must be regenerated: nothing cached, the cache is older than the TTL, or the context changed. */
    public static boolean needRefresh(long now, long cachedAt, String cachedHash, String currentHash) {
        if (cachedAt <= 0 || cachedHash == null || cachedHash.isEmpty()) return true;
        if (now < cachedAt || now - cachedAt > TTL_MS) return true;
        return !cachedHash.equals(currentHash);
    }

    /** Offline chips for the given hour (0..23): three plain agent requests, picked by part of the day. */
    public static List<String> offline(int hour) {
        String greeting;
        switch (SuggestionPrompt.timeOfDay(hour)) {
            case "утро": greeting = "Доброе утро! Что нового?"; break;
            case "вечер": greeting = "Добрый вечер! Что нового?"; break;
            case "ночь": greeting = "Не спится? Что нового?"; break;
            default: greeting = "Что нового?";
        }
        return new ArrayList<>(Arrays.asList(greeting, "Включи музыку", "Какие у тебя возможности?"));
    }
}
