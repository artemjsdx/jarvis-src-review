package app.jarvis.core.agent;

import app.jarvis.core.llm.ToolCall;
import app.jarvis.core.llm.ToolSpec;
import app.jarvis.core.util.CancelToken;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONObject;

public final class ToolRegistry {
    public interface Handler { ToolResult run(JSONObject args, ToolContext ctx) throws Exception; }

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(new ThreadFactory() {
        int n;
        @Override public synchronized Thread newThread(Runnable r) {
            Thread t = new Thread(r, "jv-tool-" + (++n));
            t.setDaemon(true);
            return t;
        }
    });

    public synchronized void register(Tool t) { tools.put(t.spec().name, t); }

    /** Convenience for lambda-style tools. */
    public synchronized void add(final ToolSpec spec, final String category, final long timeoutMs, final boolean sideEffects, final Handler h) {
        register(new Tool() {
            @Override public ToolSpec spec() { return spec; }
            @Override public ToolResult run(JSONObject a, ToolContext c) throws Exception { return h.run(a, c); }
            @Override public long timeoutMs() { return timeoutMs; }
            @Override public boolean sideEffects() { return sideEffects; }
            @Override public String category() { return category; }
        });
    }

    public synchronized void remove(String name) { tools.remove(name); }

    public synchronized Tool get(String name) { return tools.get(name); }
    public synchronized boolean has(String name) { return tools.containsKey(name); }

    public synchronized List<ToolSpec> specs() {
        List<ToolSpec> l = new ArrayList<>();
        for (Tool t : tools.values()) l.add(t.spec());
        return l;
    }

    public synchronized List<String> names() { return new ArrayList<>(tools.keySet()); }

    /** Execute with argument validation, timeout, cancellation. Never throws; every call yields a ToolResult. */
    public ToolResult execute(final ToolCall call, final String runId, final CancelToken runCancel,
                              final app.jarvis.core.log.Journal journal, final ToolContext.Progress progress) {
        final Tool t = get(call.name);
        if (t == null) return ToolResult.fail("Неизвестный инструмент «" + call.name + "». Доступные: " + names());
        final JSONObject args = call.args();
        if (args == null) {
            String a = call.arguments.length() > 160 ? call.arguments.substring(0, 160) + "…" : call.arguments;
            return ToolResult.fail("Аргументы не являются корректным JSON (возможно, ответ модели был обрезан): " + a);
        }
        if (runCancel.isCancelled()) return ToolResult.cancelled();
        final CancelToken tc = runCancel.child();
        final ToolContext ctx = new ToolContext(runId, call.id, tc, journal, progress);
        final Future<ToolResult> f = pool.submit(() -> t.run(args, ctx));
        Runnable unhook = runCancel.onCancel(() -> {
            // give the tool a moment to clean up (kill a process, close a socket) before interrupting
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
            f.cancel(true);
        });
        try {
            return f.get(Math.max(1000, t.timeoutMs()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            tc.cancel("timeout");
            f.cancel(true);
            return ToolResult.timeout("Таймаут инструмента «" + call.name + "» (" + (t.timeoutMs() / 1000) + " с). Результат действия неизвестен: проверьте состояние перед повтором.");
        } catch (CancellationException e) {
            return ToolResult.cancelled();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            tc.cancel("interrupted");
            return ToolResult.cancelled();
        } catch (ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            if (c instanceof CancelToken.CancelledException) return ToolResult.cancelled();
            String m = c.getMessage() == null ? "" : ": " + c.getMessage();
            return ToolResult.fail(c.getClass().getSimpleName() + m);
        } finally {
            unhook.run();
        }
    }
}
