package app.jarvis.core.skills;

/**
 * Decides when the next typed turn carries the hidden "offer to save a skill" hint: after a run with several tool calls
 * or with a recovery from a tool error, at most once per {@link #INTERVAL_MS}.
 */
public final class SkillHint {
    public static final long INTERVAL_MS = 30 * 60_000L;
    public static final int MIN_TOOL_CALLS = 4;

    private long lastOfferedMs = Long.MIN_VALUE;
    private boolean pending, recovered;
    private int calls;

    public synchronized void noteRun(int toolCalls, boolean recoveredFromError, long nowMs) {
        if (toolCalls < MIN_TOOL_CALLS && !recoveredFromError) return;
        if (limited(nowMs)) return;
        pending = true;
        calls = toolCalls;
        recovered = recoveredFromError;
    }

    /** The hint for the next request, or "" when nothing is pending or the rate limit applies. */
    public synchronized String takeHint(long nowMs) {
        if (!pending) return "";
        pending = false;
        if (limited(nowMs)) return "";
        lastOfferedMs = nowMs;
        String s = "[подсказка] В прошлом запуске было " + calls + " " + callsWord(calls) + (recovered ? ", был восстановлен сбой" : "");
        return s + ". Если это переиспользуемая процедура, предложи пользователю сохранить её как навык (одной фразой).";
    }

    static String callsWord(int n) {
        int m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return "вызов инструментов";
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return "вызова инструментов";
        return "вызовов инструментов";
    }

    private boolean limited(long nowMs) {
        return lastOfferedMs != Long.MIN_VALUE && nowMs - lastOfferedMs < INTERVAL_MS;
    }
}
