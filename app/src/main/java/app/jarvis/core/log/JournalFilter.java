package app.jarvis.core.log;

/**
 * Level segments of the journal screen. DEBUG rows (wake fragments and similar noise) are hidden under the "all" segment
 * and shown only under the debug segment or while a text search is typed.
 */
public final class JournalFilter {
    public static final int ALL = 0, WARNINGS = 1, ERRORS = 2, DEBUG = 3;

    private JournalFilter() { }

    /** Smallest level shown for the segment; the search text widens "all" to include debug rows. */
    public static Journal.Level minLevel(int segment, String search) {
        switch (segment) {
            case WARNINGS: return Journal.Level.WARN;
            case ERRORS: return Journal.Level.ERROR;
            case DEBUG: return Journal.Level.DEBUG;
            default: return search == null || search.isEmpty() ? Journal.Level.INFO : Journal.Level.DEBUG;
        }
    }
}
