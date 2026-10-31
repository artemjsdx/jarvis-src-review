package app.jarvis.core.log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Structured event sink. Implementations must redact secrets and must never throw. */
public interface Journal {
    enum Level { DEBUG, INFO, WARN, ERROR }

    /** Categories required by the product spec. */
    final class Cat {
        private Cat() { }
        public static final String SYSTEM = "system", AGENT = "agent", LLM = "llm", TOOLS = "tools", TERMINAL = "terminal",
            FILES = "files", DOWNLOADS = "downloads", WAKE = "wake", STT = "stt", TTS = "tts", PLAYER = "player",
            ADB = "adb", PERMISSIONS = "permissions", PERFORMANCE = "performance";
    }

    void log(String category, Level level, String message, String runId, JSONObject details, long durationMs);

    default void i(String cat, String msg) { log(cat, Level.INFO, msg, null, null, -1); }
    default void d(String cat, String msg) { log(cat, Level.DEBUG, msg, null, null, -1); }
    default void w(String cat, String msg) { log(cat, Level.WARN, msg, null, null, -1); }
    default void e(String cat, String msg, Throwable t) { e(cat, msg, t, null, null); }

    /** An ERROR row with the throwable's site; the keys of {@code extra} (for example "technical") are merged in. */
    default void e(String cat, String msg, Throwable t, String runId, JSONObject extra) {
        log(cat, Level.ERROR, msg, runId, withExtra(errorDetails(t, Thread.currentThread().getName()), extra), -1);
    }

    /** A WARN row with the throwable's site; the keys of {@code extra} are merged in. */
    default void w(String cat, String msg, Throwable t, JSONObject extra) {
        log(cat, Level.WARN, msg, null, withExtra(errorDetails(t, Thread.currentThread().getName()), extra), -1);
    }

    /** Details of an ERROR row: thread name and, when there is a throwable, its {@link ErrorSite}. */
    static JSONObject errorDetails(Throwable t, String thread) {
        JSONObject d = new JSONObject();
        try {
            d.put("thread", thread);
            if (t != null) ErrorSite.from(t).putInto(d);
        } catch (Exception ignored) { /* plain strings: cannot fail */ }
        return d;
    }

    static JSONObject withExtra(JSONObject details, JSONObject extra) {
        if (extra == null) return details;
        JSONArray names = extra.names();
        if (names == null) return details;
        for (int i = 0; i < names.length(); i++) {
            String k = names.optString(i);
            try { details.put(k, extra.opt(k)); } catch (JSONException ignored) { /* string keys: cannot fail */ }
        }
        return details;
    }
    default void i(String cat, String msg, String runId, JSONObject details, long ms) { log(cat, Level.INFO, msg, runId, details, ms); }

    /** A rolling status row keyed by {@code key}; implementations that keep one row update it in place. */
    default void summary(String cat, String key, String msg) { log(cat, Level.INFO, msg, key, null, -1); }

    Journal NOP = (c, l, m, r, d, ms) -> { };

    /** Prints to stdout; used by JVM tests. */
    Journal STDOUT = (c, l, m, r, d, ms) ->
        System.out.println("[" + l + "][" + c + "] " + m + (ms >= 0 ? " (" + ms + " ms)" : "") + (d != null ? " " + d : ""));
}
