package app.jarvis.core.tools;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Builds the bash line that Termux runs for one request. Command, stdin and directory travel as base64, so no quoting
 * of user text is needed; the request id is passed as $0 of the inner bash, which lets {@link #killScript} find the
 * whole process group by pattern (the pattern is written as "[j]v-req-…" so pkill never matches its own command line).
 */
public final class TermuxCommand {
    private TermuxCommand() { }

    public static final int MAX_COMMAND_BYTES = 64 * 1024;

    public static String marker(String id) { return "jv-req-" + id; }

    public static String wrapper(String id, String command, String stdin, String cwd, int timeoutS) {
        String stdinPart = stdin == null || stdin.isEmpty()
            ? "< /dev/null"
            : "< <(printf %s '" + b64(stdin) + "' | base64 -d)";
        String dirPart = cwd == null || cwd.trim().isEmpty() ? "" : "cd \"$(printf %s '" + b64(cwd.trim()) + "' | base64 -d)\" 2>/dev/null; ";
        return dirPart
            + "timeout -k 5 " + timeoutS + " bash -c \"$(printf %s '" + b64(command) + "' | base64 -d)\" '" + marker(id) + "' " + stdinPart;
    }

    /** Terminates the request's process group: TERM first (timeout forwards it to its children), KILL as a fallback. */
    public static String killScript(String id) {
        String pat = "[j]v-req-" + id;
        return "pkill -TERM -f '" + pat + "'; sleep 2; pkill -KILL -f '" + pat + "'; true";
    }

    static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
