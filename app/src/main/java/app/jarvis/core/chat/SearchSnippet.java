package app.jarvis.core.chat;

import java.util.List;

/** Short excerpt of a message around the first matched word, for search results. */
public final class SearchSnippet {
    public static final int RADIUS = 60;

    private SearchSnippet() { }

    /** ±radius characters around the earliest term occurrence; the start of the text when nothing is found. Ellipses mark cuts. */
    public static String around(String text, List<String> terms, int radius) {
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) return "";
        String folded = SearchQuery.fold(clean);
        int at = -1, end = -1;
        for (String t : terms) {
            int i = folded.indexOf(t);
            if (i >= 0 && (at < 0 || i < at)) { at = i; end = i + t.length(); }
        }
        if (at < 0) { at = 0; end = 0; }
        while (end < folded.length() && Character.isLetterOrDigit(folded.charAt(end))) end++;
        int from = Math.max(0, at - radius);
        int to = Math.min(clean.length(), end + radius);
        if (from > 0 && Character.isLowSurrogate(clean.charAt(from))) from++;
        if (to < clean.length() && Character.isLowSurrogate(clean.charAt(to))) to++;
        StringBuilder out = new StringBuilder();
        if (from > 0) out.append('…');
        out.append(clean, from, to);
        if (to < clean.length()) out.append('…');
        return out.toString();
    }
}
