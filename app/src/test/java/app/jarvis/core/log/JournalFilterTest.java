package app.jarvis.core.log;

import static org.junit.Assert.*;

import org.junit.Test;

public class JournalFilterTest {

    @Test
    public void debugRowsAreHiddenByDefault() {
        assertEquals(Journal.Level.INFO, JournalFilter.minLevel(JournalFilter.ALL, ""));
        assertEquals(Journal.Level.INFO, JournalFilter.minLevel(JournalFilter.ALL, null));
    }

    @Test
    public void searchShowsDebugRows() {
        assertEquals(Journal.Level.DEBUG, JournalFilter.minLevel(JournalFilter.ALL, "wake"));
    }

    @Test
    public void segmentsMapToLevels() {
        assertEquals(Journal.Level.WARN, JournalFilter.minLevel(JournalFilter.WARNINGS, ""));
        assertEquals(Journal.Level.ERROR, JournalFilter.minLevel(JournalFilter.ERRORS, ""));
        assertEquals(Journal.Level.DEBUG, JournalFilter.minLevel(JournalFilter.DEBUG, ""));
        assertEquals(Journal.Level.WARN, JournalFilter.minLevel(JournalFilter.WARNINGS, "x"));
    }
}
