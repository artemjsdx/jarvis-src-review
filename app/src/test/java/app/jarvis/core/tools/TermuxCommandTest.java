package app.jarvis.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/** Runs the exact wrapper line that Termux receives (bash -lc <wrapper>) on this host, so quoting is tested for real. */
public class TermuxCommandTest {
    private File dir;

    @Before public void setUp() throws Exception {
        Assume.assumeTrue("bash, base64, timeout needed", onPath("bash") && onPath("base64") && onPath("timeout"));
        dir = Files.createTempDirectory("jv-termux").toFile();
    }

    private static boolean onPath(String bin) {
        for (String p : System.getenv("PATH").split(":")) if (new File(p, bin).canExecute()) return true;
        return false;
    }

    private static final class Run {
        int exit;
        String out, err;
    }

    private Run runWrapper(String wrapper, long waitS) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", wrapper);
        pb.directory(dir);
        Process p = pb.start();
        final ByteArrayOutputStream eo = new ByteArrayOutputStream();
        final InputStream es = p.getErrorStream();
        Thread te = new Thread(() -> copy(es, eo));
        te.start();
        ByteArrayOutputStream oo = new ByteArrayOutputStream();
        copy(p.getInputStream(), oo);
        assertTrue("wrapper did not finish", p.waitFor(waitS, TimeUnit.SECONDS));
        te.join(2000);
        Run r = new Run();
        r.exit = p.exitValue();
        r.out = new String(oo.toByteArray(), StandardCharsets.UTF_8);
        r.err = new String(eo.toByteArray(), StandardCharsets.UTF_8);
        return r;
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        try {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } catch (java.io.IOException ignored) { }
    }

    @Test public void commandWithQuotesDollarsAndCyrillicSurvivesIntact() throws Exception {
        String cmd = "printf '%s\\n' \"привет\" 'it''s' $HOME_NOT_SET_X `echo tick`\necho \"two\" | tr a-z A-Z";
        Run r = runWrapper(TermuxCommand.wrapper("q1", cmd, null, null, 20), 20);
        assertEquals("привет\nits\ntick\nTWO\n", r.out);
        assertEquals(0, r.exit);
    }

    @Test public void stdinReachesCommandAndCwdIsApplied() throws Exception {
        String cmd = "pwd; cat; echo done";
        Run r = runWrapper(TermuxCommand.wrapper("s1", cmd, "строка 1\nline 'two' $X\n", dir.getPath(), 20), 20);
        assertTrue(r.out, r.out.startsWith(dir.getCanonicalPath() + "\n") || r.out.startsWith(dir.getPath() + "\n"));
        assertTrue(r.out, r.out.contains("строка 1\nline 'two' $X\n"));
        assertTrue(r.out, r.out.endsWith("done\n"));
    }

    @Test public void exitCodeIsReportedUnchanged() throws Exception {
        Run r = runWrapper(TermuxCommand.wrapper("e1", "echo err >&2; exit 3", null, null, 20), 20);
        assertEquals(3, r.exit);
        assertEquals("err\n", r.err);
    }

    @Test public void timeoutReturns124AndKillsTheCommand() throws Exception {
        long t0 = System.nanoTime();
        Run r = runWrapper(TermuxCommand.wrapper("t1", "sleep 60; echo never", null, null, 1), 20);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertEquals(124, r.exit);
        assertFalse(r.out.contains("never"));
        assertTrue("took " + ms + " ms", ms < 10_000);
    }

    @Test public void killScriptStopsRunningWrapper() throws Exception {
        String id = "k" + System.nanoTime();
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", TermuxCommand.wrapper(id, "sleep 60", null, null, 120));
        pb.directory(dir);
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File(dir, "out.txt"));
        Process p = pb.start();
        Thread.sleep(700);
        assertTrue(p.isAlive());
        Process k = new ProcessBuilder("bash", "-c", TermuxCommand.killScript(id)).redirectErrorStream(true).start();
        assertTrue(k.waitFor(15, TimeUnit.SECONDS));
        assertTrue("wrapper survived kill", p.waitFor(10, TimeUnit.SECONDS));
    }

    @Test public void markerAndKillPatternDoNotMatchEachOther() {
        String id = "abc123";
        String wrapper = TermuxCommand.wrapper(id, "true", null, null, 5);
        assertTrue(wrapper.contains(TermuxCommand.marker(id)));
        assertFalse(TermuxCommand.killScript(id).contains("'" + TermuxCommand.marker(id) + "'"));
        assertTrue(TermuxCommand.killScript(id).contains("[j]v-req-" + id));
    }

    @Test public void stdinIsDevNullWhenAbsent() {
        assertTrue(TermuxCommand.wrapper("n", "true", "", null, 5).endsWith("< /dev/null"));
    }
}
