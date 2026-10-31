package app.jarvis.core.chat;

/** Session titles derived from the first user message. Pure Java, so it is covered by JVM tests. */
public final class SessionText {
    public static final String DEFAULT_TITLE = "Новый диалог";
    public static final int TITLE_CODE_POINTS = 40;

    private SessionText() { }

    /** Single line, markdown markers and surrounding spaces removed, at most 40 code points; the default title if nothing is left. */
    public static String title(String text) {
        if (text == null) return DEFAULT_TITLE;
        String t = text.replaceAll("\\s+", " ").replaceAll("^[\\s#*_`>\\-]+", "").replaceAll("[\\s*_`]+$", "").trim();
        if (t.isEmpty()) return DEFAULT_TITLE;
        int end = t.offsetByCodePoints(0, Math.min(TITLE_CODE_POINTS, t.codePointCount(0, t.length())));
        String out = t.substring(0, end).trim();
        return out.isEmpty() ? DEFAULT_TITLE : out;
    }
}
