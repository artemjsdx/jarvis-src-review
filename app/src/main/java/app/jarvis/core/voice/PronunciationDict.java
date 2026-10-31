package app.jarvis.core.voice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ordered user pronunciation overrides, applied by {@link SpeechText} after all built-in normalization.
 * A rule is either a literal (case-insensitive by default) or a regular expression.
 * Persistence is a small JSON document; invalid rules are skipped, never thrown.
 */
public final class PronunciationDict {

    public static final class Rule {
        public final String pattern;
        public final String replacement;
        public final boolean regex;
        public final boolean caseInsensitive;

        public Rule(String pattern, String replacement, boolean regex, boolean caseInsensitive) {
            this.pattern = pattern;
            this.replacement = replacement == null ? "" : replacement;
            this.regex = regex;
            this.caseInsensitive = caseInsensitive;
        }
    }

    private final List<Rule> rules = new ArrayList<>();

    public PronunciationDict() { }

    public int size() { return rules.size(); }

    public List<Rule> rules() { return new ArrayList<>(rules); }

    /** Appends a rule; later rules see the output of earlier ones. */
    public PronunciationDict add(String pattern, String replacement, boolean regex, boolean caseInsensitive) {
        if (pattern != null && !pattern.isEmpty()) rules.add(new Rule(pattern, replacement, regex, caseInsensitive));
        return this;
    }

    /** Literal, case-insensitive convenience. */
    public PronunciationDict add(String pattern, String replacement) {
        return add(pattern, replacement, false, true);
    }

    /** Applies every rule in order and returns the result. */
    public String apply(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        for (Rule r : rules) {
            try {
                int flags = r.caseInsensitive ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
                Pattern p = Pattern.compile(r.regex ? r.pattern : Pattern.quote(r.pattern), flags);
                Matcher m = p.matcher(text);
                StringBuffer sb = new StringBuffer();
                while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(r.replacement));
                m.appendTail(sb);
                text = sb.toString();
            } catch (RuntimeException ignored) {
                // A broken user rule must not break speech; skip it.
            }
        }
        return text;
    }

    public String toJson() {
        JSONObject root = new JSONObject();
        JSONArray arr = new JSONArray();
        for (Rule r : rules) {
            JSONObject o = new JSONObject();
            try {
                o.put("pattern", r.pattern);
                o.put("replacement", r.replacement);
                o.put("regex", r.regex);
                o.put("ci", r.caseInsensitive);
            } catch (Exception ignored) { }
            arr.put(o);
        }
        try { root.put("version", 1); root.put("rules", arr); } catch (Exception ignored) { }
        return root.toString();
    }

    public static PronunciationDict fromJson(String json) {
        PronunciationDict d = new PronunciationDict();
        if (json == null || json.trim().isEmpty()) return d;
        try {
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("rules");
            if (arr == null) return d;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String pattern = o.optString("pattern", "");
                if (pattern.isEmpty()) continue;
                d.rules.add(new Rule(pattern, o.optString("replacement", ""),
                        o.optBoolean("regex", false), o.optBoolean("ci", true)));
            }
        } catch (Exception ignored) { }
        return d;
    }
}
