package app.jarvis.core.log;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Where an exception happened: the root cause, the first frame in our own code (file and exact line), a short list of our
 * frames and a stable fingerprint. Pure Java: the stack trace elements come straight from the throwable, so line numbers
 * are the ones the running build was compiled with (d8 keeps LineNumberTable; see docs/LOGGING.md).
 */
public final class ErrorSite {
    public static final int MAX_FRAMES = 12;
    public static final int MAX_STACK_FRAMES = 60;
    public static final int MAX_CAUSES = 4;
    static final String APP_PREFIX = "app.jarvis.";

    public final String exception;
    public final String message;
    public final String className;
    public final String method;
    public final String file;
    public final int line;
    public final String fingerprint;
    public final List<String> frames;
    public final String stack;

    private ErrorSite(String exception, String message, String className, String method, String file, int line,
                      List<String> frames, String stack) {
        this.exception = exception;
        this.message = message;
        this.className = className;
        this.method = method;
        this.file = file;
        this.line = line;
        this.fingerprint = className + "#" + method + ":" + line;
        this.frames = frames;
        this.stack = stack;
    }

    /** "MainActivity.java:123 in onResume"; unknown parts are shown as "?". */
    public String where() {
        return (file == null ? "?" : file) + ":" + (line < 0 ? "?" : String.valueOf(line)) + " in " + method;
    }

    public static ErrorSite from(Throwable t) {
        if (t == null) return new ErrorSite("", "", "", "", null, -1, new ArrayList<String>(), "");
        Throwable root = rootCause(t);
        StackTraceElement[] rootStack = root.getStackTrace();
        StackTraceElement[] outerStack = t.getStackTrace();
        StackTraceElement first = firstAppFrame(rootStack);
        if (first == null) first = firstAppFrame(outerStack);
        if (first == null && rootStack.length > 0) first = rootStack[0];
        List<String> frames = appFrames(rootStack, MAX_FRAMES);
        if (frames.isEmpty()) frames = appFrames(outerStack, MAX_FRAMES);
        if (frames.isEmpty()) frames = plainFrames(rootStack, MAX_FRAMES);
        String cls = first == null ? "" : first.getClassName();
        String method = first == null ? "" : first.getMethodName();
        String file = first == null ? null : first.getFileName();
        int line = first == null ? -1 : first.getLineNumber();
        return new ErrorSite(root.getClass().getName(), String.valueOf(root.getMessage()), cls, method, file, line, frames,
            stackText(t));
    }

    /** Writes the site fields into an entry's details object (keys: site, fingerprint, exception, cause, frames, stack). */
    public void putInto(JSONObject details) {
        try {
            details.put("site", where());
            details.put("fingerprint", fingerprint);
            details.put("exception", exception);
            details.put("cause", message);
            JSONArray fs = new JSONArray();
            for (String f : frames) fs.put(f);
            details.put("frames", fs);
            details.put("stack", stack);
        } catch (org.json.JSONException ignored) { /* keys are plain strings: cannot fail */ }
    }

    static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < 32 && cur.getCause() != null && cur.getCause() != cur; i++) cur = cur.getCause();
        return cur;
    }

    private static StackTraceElement firstAppFrame(StackTraceElement[] st) {
        for (StackTraceElement e : st) if (e.getClassName().startsWith(APP_PREFIX)) return e;
        return null;
    }

    private static List<String> appFrames(StackTraceElement[] st, int max) {
        List<String> out = new ArrayList<>();
        for (StackTraceElement e : st) {
            if (out.size() >= max) break;
            if (e.getClassName().startsWith(APP_PREFIX)) out.add(frameText(e));
        }
        return out;
    }

    private static List<String> plainFrames(StackTraceElement[] st, int max) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < st.length && i < max; i++) out.add(frameText(st[i]));
        return out;
    }

    static String frameText(StackTraceElement e) {
        return e.getClassName() + "." + e.getMethodName() + "(" + (e.getFileName() == null ? "Unknown Source" : e.getFileName())
            + (e.getLineNumber() >= 0 ? ":" + e.getLineNumber() : "") + ")";
    }

    /** The whole chain: each throwable with up to MAX_STACK_FRAMES frames, then "Caused by:" blocks (at most MAX_CAUSES). */
    static String stackText(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        for (int c = 0; cur != null && c <= MAX_CAUSES; c++) {
            sb.append(c == 0 ? "" : "Caused by: ").append(cur).append('\n');
            StackTraceElement[] st = cur.getStackTrace();
            for (int i = 0; i < st.length && i < MAX_STACK_FRAMES; i++) sb.append("\tat ").append(frameText(st[i])).append('\n');
            if (st.length > MAX_STACK_FRAMES) sb.append("\t... ").append(st.length - MAX_STACK_FRAMES).append(" more\n");
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        return sb.toString();
    }
}
