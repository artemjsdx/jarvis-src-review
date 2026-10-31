package app.jarvis.core.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Kills a process and all its descendants using /proc (no native code, works on Android and desktop Linux). */
public final class ProcTree {
    private ProcTree() { }

    public static List<Integer> descendants(int root) {
        Map<Integer, List<Integer>> kids = new HashMap<>();
        File[] procs = new File("/proc").listFiles();
        if (procs != null) for (File p : procs) {
            String n = p.getName();
            if (n.isEmpty() || !Character.isDigit(n.charAt(0))) continue;
            try (BufferedReader r = new BufferedReader(new FileReader(new File(p, "stat")))) {
                String s = r.readLine();
                if (s == null) continue;
                int close = s.lastIndexOf(')');
                String[] f = s.substring(close + 2).split(" ");
                int ppid = Integer.parseInt(f[1]);
                List<Integer> l = kids.get(ppid);
                if (l == null) { l = new ArrayList<>(); kids.put(ppid, l); }
                l.add(Integer.parseInt(n));
            } catch (Exception ignored) { }
        }
        List<Integer> out = new ArrayList<>();
        List<Integer> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            int cur = stack.remove(stack.size() - 1);
            List<Integer> k = kids.get(cur);
            if (k != null) for (int c : k) { out.add(c); stack.add(c); }
        }
        return out;
    }

    /**
     * Kills the process and everything below it.
     * Order matters: the root (the shell) is frozen FIRST. With a plain "kill -9 children... root" the shell wakes up as soon as its
     * foreground child dies and can run the next command of the script ("sleep 32; echo not-reached") before the signal reaches it
     * (seen on a loaded CI machine). Everything found is frozen with SIGSTOP (so nothing can fork or run on), the tree is re-scanned for
     * processes created in the meantime, and only then is the whole set killed. SIGKILL works on stopped processes.
     */
    public static void killTree(int root) {
        if (root <= 1) return;
        run("kill -STOP " + root);
        List<Integer> all = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            List<Integer> fresh = descendants(root);
            fresh.removeAll(all);
            if (fresh.isEmpty()) break;
            all.addAll(fresh);
            run("kill -STOP " + join(fresh));
        }
        all.add(root);
        run("kill -9 " + join(all));
    }

    private static String join(List<Integer> pids) {
        StringBuilder sb = new StringBuilder();
        for (int p : pids) sb.append(sb.length() == 0 ? "" : " ").append(p);
        return sb.toString();
    }

    private static void run(String cmd) {
        try {
            new ProcessBuilder(ShellExec.shellPath(), "-c", cmd).redirectErrorStream(true).start().waitFor();
        } catch (Exception ignored) { }
    }
}
