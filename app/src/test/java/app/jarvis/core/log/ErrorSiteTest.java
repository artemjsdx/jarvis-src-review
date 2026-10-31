package app.jarvis.core.log;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ErrorSiteTest {

    private static Throwable wrappedParseError() {
        try {
            parse("zz");
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static void parse(String s) {
        try {
            Integer.parseInt(s);
        } catch (NumberFormatException nfe) {
            throw new IllegalStateException("outer", nfe);
        }
    }

    @Test
    public void rootCauseAndFirstAppFrameWithExactLine() {
        Throwable t = wrappedParseError();
        int expectedLine = -1;
        for (StackTraceElement e : t.getCause().getStackTrace()) {
            if (e.getMethodName().equals("parse")) { expectedLine = e.getLineNumber(); break; }
        }
        assertTrue(expectedLine > 0);
        ErrorSite s = ErrorSite.from(t);
        assertEquals("java.lang.NumberFormatException", s.exception);
        assertEquals("app.jarvis.core.log.ErrorSiteTest", s.className);
        assertEquals("parse", s.method);
        assertEquals("ErrorSiteTest.java", s.file);
        assertEquals(expectedLine, s.line);
        assertEquals("ErrorSiteTest.java:" + expectedLine + " in parse", s.where());
        assertEquals("app.jarvis.core.log.ErrorSiteTest#parse:" + expectedLine, s.fingerprint);
    }

    @Test
    public void framesAreOnlyOursAndCapped() {
        ErrorSite s = ErrorSite.from(wrappedParseError());
        assertFalse(s.frames.isEmpty());
        assertTrue(s.frames.size() <= ErrorSite.MAX_FRAMES);
        for (String f : s.frames) assertTrue(f, f.startsWith("app.jarvis."));
        assertTrue(s.frames.get(0).startsWith("app.jarvis.core.log.ErrorSiteTest.parse("));
    }

    @Test
    public void stackKeepsCauseChain() {
        ErrorSite s = ErrorSite.from(wrappedParseError());
        assertTrue(s.stack, s.stack.startsWith("java.lang.IllegalStateException: outer\n"));
        assertTrue(s.stack, s.stack.contains("Caused by: java.lang.NumberFormatException"));
    }

    @Test
    public void deepStackIsTrimmed() {
        Throwable t = deep(80);
        ErrorSite s = ErrorSite.from(t);
        assertTrue(s.stack.contains("\t... "));
        assertTrue(s.stack.split("\n").length <= ErrorSite.MAX_STACK_FRAMES + 8);
    }

    private static Throwable deep(int n) {
        if (n == 0) return new RuntimeException("deep");
        return deep(n - 1);
    }

    @Test
    public void putIntoWritesSiteFields() throws Exception {
        ErrorSite s = ErrorSite.from(wrappedParseError());
        JSONObject d = new JSONObject();
        s.putInto(d);
        assertEquals(s.where(), d.getString("site"));
        assertEquals(s.fingerprint, d.getString("fingerprint"));
        assertEquals("java.lang.NumberFormatException", d.getString("exception"));
        assertEquals(s.stack, d.getString("stack"));
        JSONArray fs = d.getJSONArray("frames");
        assertEquals(s.frames.size(), fs.length());
    }

    @Test
    public void jdkOnlyRootFallsBackToTheCallerInOurCode() {
        Throwable t;
        try {
            Integer.parseInt("q");
            t = null;
        } catch (NumberFormatException e) {
            t = e;
        }
        ErrorSite s = ErrorSite.from(t);
        assertEquals("java.lang.NumberFormatException", s.exception);
        assertTrue(s.where(), s.where().startsWith("ErrorSiteTest.java:"));
        assertTrue(s.where(), s.where().endsWith(" in jdkOnlyRootFallsBackToTheCallerInOurCode"));
        assertFalse(s.frames.isEmpty());
    }

    @Test
    public void nullThrowableIsUnknownNotCrash() {
        ErrorSite s = ErrorSite.from(null);
        assertEquals("?:? in ", s.where());
        assertEquals("", s.exception);
    }
}
