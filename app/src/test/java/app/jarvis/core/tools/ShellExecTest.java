package app.jarvis.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.util.CancelToken;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class ShellExecTest extends ToolsTestBase {
    private ShellSession session;

    @Before public void setUp() throws Exception { init(); session = new ShellSession(sb); }

    private ShellExec.Result run(String cmd, long timeoutMs) throws Exception {
        return session.run(cmd, null, timeoutMs, new CancelToken(), null);
    }

    @Test public void stdoutStderrAndExitCodeAreSeparate() throws Exception {
        ShellExec.Result r = run("echo привет; echo oops >&2; exit 3", 10_000);
        assertEquals("привет\n", r.stdout);
        assertEquals("oops\n", r.stderr);
        assertEquals(3, r.exitCode);
        assertFalse(r.ok());
    }

    @Test public void successfulCommand() throws Exception {
        ShellExec.Result r = run("echo hello", 10_000);
        assertEquals(0, r.exitCode);
        assertEquals("hello\n", r.stdout);
        assertEquals("", r.stderr);
        assertTrue(r.ok());
    }

    @Test public void missingCommandFailsHonestly() throws Exception {
        ShellExec.Result r = run("definitely_not_a_command_xyz", 10_000);
        assertTrue(r.exitCode != 0);
        assertFalse(r.stderr.isEmpty());
    }

    @Test public void stderrWithoutTrailingNewlineIsKept() throws Exception {
        ShellExec.Result r = run("printf abc >&2", 10_000);
        assertEquals("abc", r.stderr.trim());
        assertEquals(0, r.exitCode);
    }

    @Test public void terminalStartsInSandboxRootSoPathsMatchFileTools() throws Exception {
        assertEquals(sb.root, session.cwd());
        call("fs_write", "{\"path\":\"docs/a.txt\",\"content\":\"x\"}");
        assertEquals("x", run("cat docs/a.txt", 10_000).stdout);
    }

    @Test public void cdPersistsAcrossCommandsAndEnvIsSandboxed() throws Exception {
        run("mkdir -p docs/sub", 10_000);
        assertEquals(0, run("cd docs/sub", 10_000).exitCode);
        assertTrue(session.cwd().getPath(), session.cwd().getPath().endsWith("/docs/sub"));
        ShellExec.Result r = run("pwd; echo $HOME; echo $JARVIS_SANDBOX", 10_000);
        String[] l = r.stdout.trim().split("\n");
        assertTrue(l[0], l[0].endsWith("/docs/sub"));
        assertEquals(sb.root.getPath(), l[1]);
        assertEquals(sb.root.getPath(), l[2]);
    }

    @Test public void timeoutKillsTheWholeProcessTree() throws Exception {
        long t0 = System.nanoTime();
        ShellExec.Result r = run("sleep 31 & sleep 32; echo not-reached", 1200);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(r.timedOut);
        assertTrue("должно завершиться вскоре после таймаута, было " + ms + " мс", ms < 6000);
        assertFalse(r.stdout.contains("not-reached"));
        Thread.sleep(300);
        assertEquals("после таймаута не должно остаться sleep-процессов", 0, countOurSleepChildren());
    }

    @Test public void cancelStopsLongCommandQuickly() throws Exception {
        final CancelToken c = new CancelToken();
        new Thread(new Runnable() { @Override public void run() { try { Thread.sleep(600); } catch (InterruptedException ignored) { } c.cancel("user"); } }).start();
        long t0 = System.nanoTime();
        ShellExec.Result r = session.run("sleep 33; echo done", null, 30_000, c, null);
        assertTrue(r.cancelled);
        assertTrue((System.nanoTime() - t0) / 1_000_000L < 5000);
        Thread.sleep(300);
        assertEquals(0, countOurSleepChildren());
    }

    @Test public void hugeOutputIsCappedButCountedAsTruncated() throws Exception {
        ShellExec.Result r = run("yes аб | head -c 400000", 20_000);
        assertTrue(r.truncated);
        assertTrue("stdout cap, got " + r.stdout.length(), r.stdout.length() < 110_000);
        assertTrue(r.stdout.contains("пропущено"));
    }

    @Test public void streamsOutputWhileRunning() throws Exception {
        final long[] firstChunkAt = {0};
        final long t0 = System.nanoTime();
        ShellExec.Result r = session.run("echo first; sleep 1; echo second", null, 10_000, new CancelToken(), new ShellExec.Sink() {
            @Override public void chunk(String text, boolean err) { if (firstChunkAt[0] == 0 && text.contains("first")) firstChunkAt[0] = System.nanoTime(); }
        });
        assertEquals(0, r.exitCode);
        assertTrue("первый вывод должен прийти до завершения команды", firstChunkAt[0] != 0 && (firstChunkAt[0] - t0) / 1_000_000L < 800);
    }

    @Test public void historyRecordsCommandsWithExitCodes() throws Exception {
        run("true", 5000);
        run("false", 5000);
        List<ShellSession.Entry> h = session.history();
        assertEquals(2, h.size());
        assertEquals(0, h.get(0).exitCode);
        assertEquals(1, h.get(1).exitCode);
    }

    /** Counts `sleep` processes that are descendants of this JVM. */
    private static int countOurSleepChildren() {
        int me = Integer.parseInt(new File("/proc/self").getAbsoluteFile().toPath().toFile().getName().equals("self")
            ? selfPid() : "0");
        int n = 0;
        for (int pid : ProcTree.descendants(me)) {
            try (BufferedReader r = new BufferedReader(new FileReader("/proc/" + pid + "/cmdline"))) {
                String s = r.readLine();
                if (s != null && s.contains("sleep")) n++;
            } catch (Exception ignored) { }
        }
        return n;
    }

    private static String selfPid() {
        try {
            String s = new BufferedReader(new FileReader("/proc/self/stat")).readLine();
            return s.substring(0, s.indexOf(' '));
        } catch (Exception e) { return "0"; }
    }
}
