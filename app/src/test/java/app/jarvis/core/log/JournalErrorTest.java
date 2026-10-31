package app.jarvis.core.log;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class JournalErrorTest {

    private static final class Capture implements Journal {
        final List<String> cats = new ArrayList<>();
        final List<Level> levels = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        final List<JSONObject> details = new ArrayList<>();

        @Override public void log(String category, Level level, String message, String runId, JSONObject d, long ms) {
            cats.add(category);
            levels.add(level);
            messages.add(message);
            details.add(d);
        }
    }

    private static void boom(int depth) {
        if (depth > 0) { boom(depth - 1); return; }
        throw new IllegalArgumentException("bad input");
    }

    @Test
    public void errorRowCarriesSiteThreadAndStack() throws Exception {
        Capture c = new Capture();
        Throwable t;
        try {
            boom(2);
            t = null;
        } catch (IllegalArgumentException e) {
            t = e;
        }
        c.e(Journal.Cat.AGENT, "Сбой запуска агента", t);
        assertEquals(Journal.Level.ERROR, c.levels.get(0));
        assertEquals("Сбой запуска агента", c.messages.get(0));
        JSONObject d = c.details.get(0);
        assertEquals("java.lang.IllegalArgumentException", d.getString("exception"));
        assertEquals("bad input", d.getString("cause"));
        assertTrue(d.getString("site"), d.getString("site").startsWith("JournalErrorTest.java:"));
        assertTrue(d.getString("site"), d.getString("site").endsWith(" in boom"));
        assertTrue(d.getString("fingerprint"), d.getString("fingerprint").startsWith("app.jarvis.core.log.JournalErrorTest#boom:"));
        assertTrue(d.getString("stack"), d.getString("stack").contains("at app.jarvis.core.log.JournalErrorTest.boom("));
        assertTrue(d.getString("thread").length() > 0);
    }

    @Test
    public void errorWithoutThrowableStillHasThread() throws Exception {
        Capture c = new Capture();
        c.e(Journal.Cat.SYSTEM, "без исключения", null);
        JSONObject d = c.details.get(0);
        assertFalse(d.has("site"));
        assertTrue(d.getString("thread").length() > 0);
    }

    @Test
    public void errorWithExtraKeepsTechnicalAndSite() throws Exception {
        Capture c = new Capture();
        JSONObject extra = new JSONObject().put("technical", "HTTP 503");
        c.e(Journal.Cat.LLM, "LLM: недоступна", new IllegalStateException("down"), "run-1", extra);
        assertEquals(Journal.Level.ERROR, c.levels.get(0));
        JSONObject d = c.details.get(0);
        assertEquals("HTTP 503", d.getString("technical"));
        assertTrue(d.has("site"));
        assertTrue(d.getString("stack").contains("IllegalStateException"));
    }

    @Test
    public void warnRowCarriesSiteAndExtra() throws Exception {
        Capture c = new Capture();
        Throwable t;
        try {
            boom(0);
            t = null;
        } catch (IllegalArgumentException e) {
            t = e;
        }
        c.w(Journal.Cat.STT, "Провайдер: нет ответа", t, new JSONObject().put("technical", "timeout"));
        assertEquals(Journal.Level.WARN, c.levels.get(0));
        JSONObject d = c.details.get(0);
        assertEquals("timeout", d.getString("technical"));
        assertTrue(d.getString("site"), d.getString("site").endsWith(" in boom"));
    }

    @Test
    public void actionRoundTripsThroughDetailsJson() {
        JSONObject d = new JSONObject();
        new JournalAction(JournalAction.BACKGROUND_SETTINGS, "Открыть настройки фона").putInto(d);
        JournalAction a = JournalAction.from(d.toString());
        assertNotNull(a);
        assertEquals(JournalAction.BACKGROUND_SETTINGS, a.id);
        assertEquals("Открыть настройки фона", a.label);
    }

    @Test
    public void actionAbsentOrInvalidIsNull() {
        assertNull(JournalAction.from(null));
        assertNull(JournalAction.from(""));
        assertNull(JournalAction.from("[1,2]"));
        assertNull(JournalAction.from("{\"site\":\"x\"}"));
        assertNull(JournalAction.from("{broken"));
    }
}
