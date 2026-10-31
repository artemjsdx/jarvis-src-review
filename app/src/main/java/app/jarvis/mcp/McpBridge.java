package app.jarvis.mcp;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import app.jarvis.BuildConfig;
import app.jarvis.core.log.Journal;
import app.jarvis.core.mcp.McpAudit;
import app.jarvis.core.mcp.McpDispatcher;
import app.jarvis.core.mcp.McpGate;
import app.jarvis.core.mcp.McpRegistry;
import app.jarvis.core.mcp.McpServer;
import app.jarvis.core.mcp.McpTool;
import app.jarvis.core.util.J;
import app.jarvis.core.util.Redactor;
import app.jarvis.data.Settings;
import app.jarvis.rt.Jarvis;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.json.JSONObject;

/**
 * Owns the MCP server of this process: token + endpoint files, registry, audit into the journal. The server is plain
 * threads on 127.0.0.1, so it works with or without {@link McpService}; the service only keeps the process alive.
 */
public final class McpBridge {
    public static final String DEFAULT_PKG = "app.jarvis";
    private static volatile McpBridge inst;

    public static McpBridge get() { return inst; }

    public final Context ctx;
    public final Jarvis j;
    public final McpRegistry registry = new McpRegistry();
    private final File dir;
    private final long startedMs = System.currentTimeMillis();
    private McpServer server;
    private volatile String token = "";
    private volatile boolean stopped;
    private final java.util.concurrent.atomic.AtomicLong calls = new java.util.concurrent.atomic.AtomicLong();
    private volatile long lastCallMs;
    private volatile String lastTool = "";

    private final McpGate gate = new McpGate() {
        @Override public boolean allowAudio() { return j.settings.getBool(Settings.MCP_ALLOW_AUDIO, false); }
        @Override public boolean allowDestructive() { return j.settings.getBool(Settings.MCP_ALLOW_DESTRUCTIVE, false); }
    };

    private McpBridge(Context app, Jarvis j) {
        this.ctx = app;
        this.j = j;
        File base = app.getExternalFilesDir(null);
        this.dir = new File(base != null ? base : app.getFilesDir(), "mcp");
    }

    public static boolean enabled(Jarvis j) { return j.settings.getBool(Settings.MCP_ENABLED, BuildConfig.DEBUG); }

    public static synchronized void installIfEnabled(Context app, Jarvis j) {
        if (inst != null || !enabled(j)) return;
        McpBridge b = new McpBridge(app.getApplicationContext(), j);
        try {
            b.start();
            inst = b;
        } catch (Throwable t) {
            j.journal.e("mcp", "Мост MCP не запустился: " + t, t);
            return;
        }
        McpService.startQuietly(app, j);
    }

    /** Starts the bridge if the setting allows it and it is not running yet (called by McpLaunchActivity / McpService). */
    public static synchronized McpBridge ensure(Context app, Jarvis j) {
        if (inst == null) installIfEnabled(app, j);
        return inst;
    }

    public String packageName() { return ctx.getPackageName(); }
    public int port() { return server == null ? 0 : server.port(); }
    public String bindAddress() { return server == null ? "" : server.bindAddress(); }
    public File dir() { return dir; }
    public McpGate gate() { return gate; }
    public boolean running() { return server != null && server.isRunning() && !stopped; }
    public long startedMs() { return startedMs; }
    public long callCount() { return calls.get(); }
    public long lastCallMs() { return lastCallMs; }
    public String lastTool() { return lastTool; }
    public String token() { return token; }

    /** Replaces the token at once (old clients get 401 and re-read it); a provisioned token is dropped so it cannot come back. */
    public synchronized String resetToken() throws IOException {
        j.secrets.remove(SECRET_TOKEN);
        File f = new File(dir, "token");
        f.delete();
        token = loadOrCreateToken();
        Redactor.addSecret(token);
        mirror();
        j.journal.log("mcp", Journal.Level.INFO, "Токен моста сброшен", null, null, -1);
        return token;
    }

    static final String SECRET_TOKEN = "mcp.token";

    /** Default port: 8767 for the main package, a stable per-package offset for side-by-side variants. */
    static int preferredPort(String pkg) {
        if (DEFAULT_PKG.equals(pkg)) return 8767;
        return 8768 + (pkg.hashCode() & 0x7fffffff) % 500;
    }

    private synchronized void start() throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        token = loadOrCreateToken();
        Redactor.addSecret(token);
        McpDispatcher d = new McpDispatcher(registry, gate, new McpAudit() {
            @Override public void record(Entry e) { auditToJournal(e); }
        }, "jarvis", BuildConfig.VERSION_NAME + "+" + BuildConfig.GIT_REV,
            "Jarvis Android voice assistant bridge. Tools are grouped by prefix (app_, journal_, settings_, chat_, ui_ ...). "
            + "Errors come as isError=true with {error:{code,message,technical}}.");
        Tools.registerAll(registry, this);
        server = new McpServer(d, new McpServer.TokenSource() { @Override public String token() { return token; } },
            new McpServer.HealthSource() { @Override public JSONObject health() { return healthJson(); } });
        int port = server.start(preferredPort(ctx.getPackageName()), 20);
        writeEndpoint(port);
        mirror();
        j.journal.log("mcp", Journal.Level.INFO, "Мост MCP слушает " + server.bindAddress() + " (пакет " + ctx.getPackageName() + ", инструментов " + registry.tools().size() + ")", null, null, -1);
    }

    public synchronized void stop() {
        stopped = true;
        if (server != null) server.close();
        new File(dir, "endpoint.json").delete();
        j.journal.log("mcp", Journal.Level.INFO, "Мост MCP остановлен", null, null, -1);
        inst = null;
    }

    JSONObject healthJson() {
        JSONObject o = new JSONObject();
        J.put(o, "app", ctx.getPackageName());
        J.put(o, "version", BuildConfig.VERSION_NAME);
        J.put(o, "rev", BuildConfig.GIT_REV);
        J.put(o, "flavor", BuildConfig.DEBUG ? "debug" : "release");
        J.put(o, "pid", android.os.Process.myPid());
        return o;
    }

    private String loadOrCreateToken() throws IOException {
        String pre = j.secrets.get(SECRET_TOKEN).trim();
        if (pre.matches("[0-9a-f]{64}")) { writeAtomic(new File(dir, "token"), pre); return pre; }
        File f = new File(dir, "token");
        if (f.isFile()) {
            try {
                String t = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
                if (t.matches("[0-9a-f]{64}")) return t;
            } catch (IOException ignored) { }
        }
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        writeAtomic(f, sb.toString());
        return sb.toString();
    }

    private void writeEndpoint(int port) throws IOException {
        JSONObject o = new JSONObject();
        J.put(o, "host", "127.0.0.1");
        J.put(o, "port", port);
        J.put(o, "pid", android.os.Process.myPid());
        J.put(o, "started", startedMs);
        J.put(o, "package", ctx.getPackageName());
        J.put(o, "version", BuildConfig.VERSION_NAME + "+" + BuildConfig.GIT_REV);
        writeAtomic(new File(dir, "endpoint.json"), o.toString());
    }

    /** Optional copy of token + endpoint into Download/jarvis-mcp (setting mcp.mirrorToSharedStorage, off by default: that folder is readable by other apps). */
    private void mirror() {
        if (!j.settings.getBool(Settings.MCP_MIRROR, false)) return;
        try {
            File d = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "jarvis-mcp");
            if (!d.isDirectory() && !d.mkdirs()) return;
            writeAtomic(new File(d, "mcp-" + ctx.getPackageName() + ".json"), new JSONObject().put("token", token).put("port", port()).toString());
        } catch (Throwable t) {
            j.journal.log("mcp", Journal.Level.WARN, "Копия токена в общую память не записана: " + t.getClass().getSimpleName(), null, null, -1);
        }
    }

    private static void writeAtomic(File f, String text) throws IOException {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) { os.write(text.getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(f)) throw new IOException("rename failed: " + f);
    }

    private void auditToJournal(McpAudit.Entry e) {
        calls.incrementAndGet();
        lastCallMs = System.currentTimeMillis();
        lastTool = e.tool;
        boolean quiet = e.ok && (e.flags & McpTool.READ_ONLY) != 0;
        Journal.Level lv = !e.ok ? Journal.Level.WARN : quiet ? Journal.Level.DEBUG : Journal.Level.INFO;
        JSONObject d = new JSONObject();
        J.put(d, "tool", e.tool);
        J.put(d, "args", e.args);
        J.put(d, "ok", e.ok);
        if (!e.ok) { J.put(d, "code", e.errorCode); J.put(d, "error", e.errorMessage); }
        j.journal.log("mcp", lv, "MCP " + e.tool + (e.ok ? " ok" : " ошибка " + e.errorCode), null, d, e.durationMs);
    }

    static void startService(Context c) {
        Intent i = new Intent(c, McpService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }
}
