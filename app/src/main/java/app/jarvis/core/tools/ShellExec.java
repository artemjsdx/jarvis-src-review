package app.jarvis.core.tools;

import app.jarvis.core.util.CancelToken;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs one command through the Android shell (mksh + toybox). Captures stdout/stderr separately with size caps,
 * reports the exit code and final working directory, and on timeout/cancel kills the whole process tree.
 */
public final class ShellExec {
    private ShellExec() { }

    public static final class Result {
        public int exitCode = -1;
        public String stdout = "", stderr = "", cwd = "";
        public long durationMs;
        public boolean timedOut, cancelled, truncated;
        public boolean ok() { return exitCode == 0 && !timedOut && !cancelled; }
    }

    public interface Sink { void chunk(String text, boolean stderr); }

    public static String shellPath() {
        for (String p : new String[]{"/system/bin/sh", "/bin/sh", "/usr/bin/sh"}) if (new File(p).canExecute()) return p;
        return "sh";
    }

    private static final class Cap {
        final int headMax, tailMax;
        final StringBuilder head = new StringBuilder(), tail = new StringBuilder();
        long total;
        boolean truncated;
        Cap(int h, int t) { headMax = h; tailMax = t; }

        synchronized void add(String s) {
            total += s.length();
            if (head.length() < headMax) {
                int room = headMax - head.length();
                if (s.length() <= room) { head.append(s); return; }
                head.append(s, 0, room);
                s = s.substring(room);
            }
            truncated = true;
            tail.append(s);
            if (tail.length() > tailMax) tail.delete(0, tail.length() - tailMax);
        }

        synchronized String text() {
            if (!truncated) return head.toString();
            long skipped = total - head.length() - tail.length();
            return head + "\n…[пропущено " + skipped + " симв.]…\n" + tail;
        }
    }

    public static Result run(Sandbox sb, String command, File cwd, long timeoutMs, CancelToken cancel, final Sink sink) throws IOException {
        final String tok = "__JV" + Long.toString(System.nanoTime() & 0xffffffffL, 36) + "__";
        String script = "printf '%s\\n' \"" + tok + "PID:$$\" >&2; "
            + "cd \"$JV_CWD\" 2>/dev/null || echo \"cd: нет доступа к $JV_CWD\" >&2; "
            + "eval \"$JV_CMD\"; __jv_rc=$?; "
            + "printf '%s\\n%s\\n' \"" + tok + "CWD:$PWD\" \"" + tok + "RC:$__jv_rc\" >&2; exit $__jv_rc";
        ProcessBuilder pb = new ProcessBuilder(shellPath(), "-c", script);
        Map<String, String> env = pb.environment();
        env.put("JV_CMD", command);
        env.put("JV_CWD", cwd.getPath());
        env.put("HOME", sb.root.getPath());
        env.put("TMPDIR", sb.tmp.getPath());
        env.put("JARVIS_SANDBOX", sb.root.getPath());
        String path = env.get("PATH");
        env.put("PATH", sb.bin.getPath() + ":/system/bin:/system/xbin:/vendor/bin" + (path == null || path.isEmpty() ? "" : ":" + path));
        pb.directory(cwd.isDirectory() ? cwd : sb.root);
        File devNull = new File("/dev/null");
        if (devNull.exists()) pb.redirectInput(ProcessBuilder.Redirect.from(devNull));
        final long t0 = System.nanoTime();
        final Process p = pb.start();
        final Result res = new Result();
        res.cwd = cwd.getPath();
        final Cap out = new Cap(48 * 1024, 48 * 1024), err = new Cap(24 * 1024, 24 * 1024);
        final int[] pid = {-1};
        final String[] finalCwd = {null};
        final int[] rc = {Integer.MIN_VALUE};

        Thread to = new Thread(new Runnable() {
            @Override public void run() {
                try (InputStreamReader r = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] buf = new char[4096];
                    int n;
                    while ((n = r.read(buf)) > 0) {
                        String s = new String(buf, 0, n);
                        out.add(s);
                        if (sink != null) sink.chunk(s, false);
                    }
                } catch (IOException ignored) { }
            }
        }, "jv-sh-out");
        Thread te = new Thread(new Runnable() {
            @Override public void run() {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        int i = line.indexOf(tok);
                        String user = i < 0 ? line : line.substring(0, i);
                        if (i >= 0) {
                            String m = line.substring(i + tok.length());
                            try {
                                if (m.startsWith("PID:")) pid[0] = Integer.parseInt(m.substring(4).trim());
                                else if (m.startsWith("CWD:")) finalCwd[0] = m.substring(4);
                                else if (m.startsWith("RC:")) rc[0] = Integer.parseInt(m.substring(3).trim());
                            } catch (NumberFormatException ignored) { }
                            if (user.isEmpty()) continue;
                        }
                        String s = i < 0 ? user + "\n" : user;
                        err.add(s);
                        if (sink != null) sink.chunk(s, true);
                    }
                } catch (IOException ignored) { }
            }
        }, "jv-sh-err");
        to.setDaemon(true);
        te.setDaemon(true);
        to.start();
        te.start();

        final boolean[] killedByCancel = {false};
        Runnable unhook = cancel.onCancel(new Runnable() {
            @Override public void run() { killedByCancel[0] = true; kill(p, pid[0]); }
        });
        try {
            boolean done = p.waitFor(Math.max(500, timeoutMs), TimeUnit.MILLISECONDS);
            if (!done) {
                res.timedOut = true;
                kill(p, pid[0]);
                p.waitFor(2, TimeUnit.SECONDS);
            }
            to.join(1500);
            te.join(1500);
        } catch (InterruptedException e) {
            kill(p, pid[0]);
            Thread.currentThread().interrupt();
            res.cancelled = true;
        } finally {
            unhook.run();
        }
        if (killedByCancel[0]) res.cancelled = true;
        res.durationMs = (System.nanoTime() - t0) / 1_000_000L;
        res.stdout = out.text();
        res.stderr = err.text();
        res.truncated = out.truncated || err.truncated;
        if (finalCwd[0] != null && new File(finalCwd[0]).isDirectory()) res.cwd = finalCwd[0];
        if (rc[0] != Integer.MIN_VALUE) res.exitCode = rc[0];
        else {
            try { res.exitCode = p.exitValue(); } catch (IllegalThreadStateException e) { res.exitCode = -1; }
        }
        return res;
    }

    private static void kill(Process p, int pid) {
        if (pid > 1) ProcTree.killTree(pid);
        p.destroy();
        try {
            if (!p.waitFor(300, TimeUnit.MILLISECONDS)) p.destroyForcibly();
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
