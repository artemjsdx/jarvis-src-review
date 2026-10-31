package app.jarvis.core.tools;

import app.jarvis.core.util.CancelToken;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Shared terminal state (working directory + history) used by both the Terminal screen and the shell_exec tool. */
public final class ShellSession {
    public static final class Entry {
        public final long ts = System.currentTimeMillis();
        public final String command;
        public int exitCode = -1;
        public long durationMs;
        public String cwd = "";
        public boolean timedOut, cancelled;
        Entry(String c) { command = c; }
    }

    public interface HistoryListener { void onEntry(Entry e); }

    private final Sandbox sb;
    private volatile File cwd;
    private final List<Entry> history = new ArrayList<>();
    private volatile HistoryListener listener;

    /** The terminal starts in the sandbox root so relative paths mean the same thing in the shell and in file tools. */
    public ShellSession(Sandbox sb) { this.sb = sb; this.cwd = sb.root; }

    public File cwd() { return cwd; }
    public void setListener(HistoryListener l) { listener = l; }

    public synchronized List<Entry> history() { return new ArrayList<>(history); }

    public File resolveCwd(String arg) throws IOException {
        if (arg == null || arg.trim().isEmpty()) return cwd;
        String a = arg.trim();
        if (a.startsWith("/")) {
            File f = new File(a).getCanonicalFile();
            if (f.isDirectory()) return f;
        }
        File f = sb.resolve(a);
        if (!f.isDirectory()) throw new IOException("Каталог не найден: " + arg);
        return f;
    }

    public ShellExec.Result run(String command, String cwdArg, long timeoutMs, CancelToken cancel, ShellExec.Sink sink) throws IOException {
        File where = resolveCwd(cwdArg);
        Entry e = new Entry(command);
        ShellExec.Result r = ShellExec.run(sb, command, where, timeoutMs, cancel, sink);
        e.exitCode = r.exitCode;
        e.durationMs = r.durationMs;
        e.cwd = r.cwd;
        e.timedOut = r.timedOut;
        e.cancelled = r.cancelled;
        if (cwdArg == null || cwdArg.trim().isEmpty()) cwd = new File(r.cwd); // `cd` persists only when no explicit cwd was given
        synchronized (this) {
            history.add(e);
            while (history.size() > 300) history.remove(0);
        }
        HistoryListener l = listener;
        if (l != null) l.onEntry(e);
        return r;
    }
}
