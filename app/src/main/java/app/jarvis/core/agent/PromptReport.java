package app.jarvis.core.agent;

import app.jarvis.core.util.J;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What the next model request contains, by part: characters, estimated tokens and the share of the context window.
 * Pure Java; the caller supplies the texts (see AgentRunner.promptReport). Token estimate: Cyrillic ≈ 3.2 characters per
 * token, everything else ≈ 4, so it is an estimate for the settings page, not a tokenizer.
 */
public final class PromptReport {
    public static final class Row {
        public final String key, title, note;
        public final int chars, tokens;

        Row(String key, String title, int chars, int tokens, String note) {
            this.key = key;
            this.title = title;
            this.chars = chars;
            this.tokens = tokens;
            this.note = note == null ? "" : note;
        }
    }

    public final List<Row> rows;
    /** Characters the history budget allows (the context window used for the bar). */
    public final int windowChars;
    public final int historyKept, historyDropped;

    private PromptReport(List<Row> rows, int windowChars, int historyKept, int historyDropped) {
        this.rows = Collections.unmodifiableList(rows);
        this.windowChars = windowChars;
        this.historyKept = historyKept;
        this.historyDropped = historyDropped;
    }

    public static int tokens(String s) {
        if (s == null || s.isEmpty()) return 0;
        double cyrillic = 0, other = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u0400' && c <= '\u04FF') cyrillic++; else other++;
        }
        return (int) Math.ceil(cyrillic * 0.3125 + other * 0.25);
    }

    public static Row row(String key, String title, String text, String note) {
        String t = text == null ? "" : text;
        return new Row(key, title, t.length(), tokens(t), note);
    }

    /** A row whose size is already known in characters (tool schemas, history). */
    public static Row sized(String key, String title, int chars, int tokens, String note) {
        return new Row(key, title, chars, tokens, note);
    }

    public static PromptReport of(List<Row> rows, int windowChars, int historyKept, int historyDropped) {
        return new PromptReport(new ArrayList<>(rows), Math.max(1, windowChars), historyKept, historyDropped);
    }

    public int totalChars() {
        int n = 0;
        for (Row r : rows) n += r.chars;
        return n;
    }

    public int totalTokens() {
        int n = 0;
        for (Row r : rows) n += r.tokens;
        return n;
    }

    /** Share of the window in percent, may exceed 100 when the request does not fit. */
    public int percentOfWindow() { return (int) Math.round(100.0 * totalChars() / windowChars); }

    /** Fraction of the window for a single row, 0..1 (bar length). */
    public double fraction(Row r) { return Math.min(1.0, (double) r.chars / windowChars); }

    public JSONObject toJson() {
        JSONArray arr = new JSONArray();
        for (Row r : rows) {
            arr.put(J.put(J.put(J.put(J.put(J.put(new JSONObject(), "key", r.key), "title", r.title), "chars", r.chars), "tokens", r.tokens), "note", r.note));
        }
        JSONObject o = new JSONObject();
        J.put(o, "rows", arr);
        J.put(o, "totalChars", totalChars());
        J.put(o, "totalTokens", totalTokens());
        J.put(o, "windowChars", windowChars);
        J.put(o, "percentOfWindow", percentOfWindow());
        J.put(o, "historyKept", historyKept);
        J.put(o, "historyDropped", historyDropped);
        return o;
    }

    /** Rows for the system prompt: the stable core text, memory, skills, and the per-session extras. */
    public static List<Row> systemRows(Prompts.Info full) {
        Prompts.Info bare = new Prompts.Info();
        bare.nowText = full.nowText;
        String core = Prompts.system(bare);
        List<Row> out = new ArrayList<>();
        out.add(row("core", "Правила и окружение", core, "стабильная часть, кэшируется провайдером"));
        out.add(row("memory", "Память и стиль", style(full) + full.memory, "бюджет memory.budget"));
        out.add(row("skills", "Навыки (индекс)", full.skills, "полный текст — по skill_view"));
        out.add(row("extra", "Сведения о пользователе и музыке", full.extra, ""));
        return out;
    }

    private static String style(Prompts.Info i) {
        return i.memoryStyle == null || i.memoryStyle.isEmpty() ? "" : "СТИЛЬ ОТВЕТА ПОЛЬЗОВАТЕЛЯ:\n" + i.memoryStyle + "\n";
    }
}
