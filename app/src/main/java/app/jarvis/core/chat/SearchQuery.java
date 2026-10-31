package app.jarvis.core.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Search terms for chat history. Words only, lower case, ё→е, so the FTS index (unicode61 with remove_diacritics) and the
 * fallback scan see the same words. Pure Java: the SQL text is built here, the database is not touched.
 */
public final class SearchQuery {
    public static final int MAX_TERMS = 8;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    private SearchQuery() { }

    /** Lower case with ё→е. Length-preserving (char by char), so offsets found in the result are valid for the input. */
    public static String fold(String s) {
        char[] c = s.toCharArray();
        for (int i = 0; i < c.length; i++) {
            char x = Character.toLowerCase(c[i]);
            c[i] = x == 'ё' ? 'е' : x;
        }
        return new String(c);
    }

    /** Distinct words of the query, in order, at most {@link #MAX_TERMS}. */
    public static List<String> terms(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        Matcher m = WORD.matcher(fold(raw));
        while (m.find() && out.size() < MAX_TERMS) {
            String t = m.group();
            if (!out.contains(t)) out.add(t);
        }
        return out;
    }

    /** FTS5 MATCH expression: every word must occur, each as a prefix ("слов"* finds "словарь"). Empty for no words. */
    public static String ftsMatch(List<String> terms) {
        StringBuilder b = new StringBuilder();
        for (String t : terms) {
            if (b.length() > 0) b.append(' ');
            b.append('"').append(t).append("\"*");
        }
        return b.toString();
    }

    /** Fallback without FTS: every word occurs as a substring of the folded text. */
    public static boolean matches(String text, List<String> terms) {
        if (terms.isEmpty() || text == null) return false;
        String f = fold(text);
        for (String t : terms) if (!f.contains(t)) return false;
        return true;
    }
}
