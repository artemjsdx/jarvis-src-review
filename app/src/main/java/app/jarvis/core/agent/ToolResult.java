package app.jarvis.core.agent;

import app.jarvis.core.util.J;
import java.util.Iterator;
import org.json.JSONObject;

/** Outcome of one tool execution. {@code ok} is true only when the action really happened. */
public final class ToolResult {
    public final boolean ok;
    public final String error;
    public final JSONObject data;
    /** One-line human summary for UI cards and the journal. */
    public final String summary;
    public final boolean timedOut, cancelled;
    /** Optional short Russian sentence that is a complete, truthful answer for this result (lets fast mode skip the second model call). */
    public final String spoken;

    private ToolResult(boolean ok, String error, JSONObject data, String summary, boolean timedOut, boolean cancelled) {
        this(ok, error, data, summary, timedOut, cancelled, "");
    }

    private ToolResult(boolean ok, String error, JSONObject data, String summary, boolean timedOut, boolean cancelled, String spoken) {
        this.spoken = spoken == null ? "" : spoken;
        this.ok = ok;
        this.error = error == null ? "" : error;
        this.data = data == null ? new JSONObject() : data;
        this.summary = summary == null ? "" : summary;
        this.timedOut = timedOut;
        this.cancelled = cancelled;
    }

    public static ToolResult ok(JSONObject data, String summary) { return new ToolResult(true, "", data, summary, false, false); }
    public static ToolResult ok(String summary) { return ok(new JSONObject(), summary); }

    /** Copy of this (successful) result that also carries a ready spoken answer. */
    public ToolResult spoken(String sentence) { return new ToolResult(ok, error, data, summary, timedOut, cancelled, ok ? sentence : ""); }
    public static ToolResult fail(String error) { return new ToolResult(false, error, null, error, false, false); }
    public static ToolResult fail(String error, JSONObject data) { return new ToolResult(false, error, data, error, false, false); }
    public static ToolResult timeout(String error) { return new ToolResult(false, error, null, error, true, false); }
    public static ToolResult cancelled() { return new ToolResult(false, "Отменено пользователем", null, "Отменено", false, true); }

    /** JSON text for the model. Large string fields are shortened (head + tail) to respect maxChars. */
    public String toModelText(int maxChars) {
        JSONObject o = new JSONObject();
        J.put(o, "ok", ok);
        if (!ok) J.put(o, "error", error);
        Iterator<String> it = data.keys();
        while (it.hasNext()) { String k = it.next(); if (!o.has(k)) J.put(o, k, data.opt(k)); }
        String s = o.toString();
        if (s.length() <= maxChars) return s;
        int budget = Math.max(400, maxChars / 2);
        it = o.keys();
        java.util.List<String> keys = new java.util.ArrayList<>();
        while (it.hasNext()) keys.add(it.next());
        for (String k : keys) {
            Object v = o.opt(k);
            if (v instanceof String && ((String) v).length() > budget) {
                String t = (String) v;
                int head = (int) (budget * 0.6), tail = budget - head;
                J.put(o, k, t.substring(0, head) + "\n…[обрезано " + (t.length() - budget) + " симв.]…\n" + t.substring(t.length() - tail));
            }
        }
        s = o.toString();
        if (s.length() > maxChars + 2000) s = s.substring(0, maxChars) + "…[вывод обрезан]";
        return s;
    }
}
