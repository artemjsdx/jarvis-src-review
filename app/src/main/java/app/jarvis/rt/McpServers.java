package app.jarvis.rt;

import app.jarvis.core.mcp.client.McpHub;
import app.jarvis.core.mcp.client.ToolGate;
import app.jarvis.core.mcp.client.TransportOptions;
import app.jarvis.core.telegram.SendLimiter;
import app.jarvis.core.util.CancelToken;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Android side of the external MCP client: keeps the hub in the app's files directory, stores per-server headers in
 * Secrets, and asks the user through the visible activity before a write-capable remote tool runs.
 */
public final class McpServers {
    /** How long a confirmation waits for a tap before it counts as "no". */
    static final long ASK_TIMEOUT_S = 60;

    private final Jarvis j;
    private final McpHub hub;
    private volatile WeakReference<Host> host = new WeakReference<>(null);

    McpServers(final Jarvis j) {
        this.j = j;
        McpHub.HeaderStore headers = new McpHub.HeaderStore() {
            @Override public String get(String id) { return j.secrets.get(headerKey(id)); }
            @Override public void put(String id, String json) { j.secrets.set(headerKey(id), json); }
            @Override public void remove(String id) { j.secrets.remove(headerKey(id)); }
        };
        hub = new McpHub(new File(j.home, "mcp-servers.json"), headers, j.journal, j.tools, new ToolGate() {
            @Override public boolean confirm(String server, String tool, String argKeys, CancelToken cancel) {
                return ask(server, tool, argKeys, cancel);
            }
            @Override public boolean allowWrite(String serverId, String tool) {
                return !TgHelper.SERVER_ID.equals(serverId) || telegramWrites.tryAcquire(System.currentTimeMillis());
            }
        }, new TransportOptions());
    }

    /** At most 20 write actions per minute on the Telegram account server, whatever the agent asks for. */
    private final SendLimiter telegramWrites = SendLimiter.perMinute();

    static String headerKey(String serverId) { return "mcp." + serverId + ".headers"; }

    public McpHub hub() { return hub; }

    /** Reads the server list and registers cached tools; no network. */
    public void load() { hub.load(); }

    public String promptLine() { return hub.promptLine(); }

    /** Called from the activity's resume/pause; a null host means nobody can answer a confirmation. */
    public void attachHost(Host h) { host = new WeakReference<>(h); }

    private boolean ask(final String server, final String tool, String argKeys, CancelToken cancel) {
        final Host h = host.get();
        if (h == null) return false;
        final CountDownLatch answered = new CountDownLatch(1);
        final boolean[] allowed = {false};
        final String args = argKeys == null || argKeys.isEmpty() ? "без аргументов" : "аргументы: " + argKeys;
        j.main.post(new Runnable() {
            @Override public void run() {
                Dialogs.confirm(h, "Разрешить действие?",
                    "Агент хочет выполнить «" + tool + "» на внешнем сервере «" + server + "» (" + args + ").\nДействие может изменить данные.",
                    "Разрешить", true, new Runnable() {
                        @Override public void run() {
                            allowed[0] = true;
                            answered.countDown();
                        }
                    });
            }
        });
        try {
            answered.await(ASK_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return allowed[0];
    }

    public void close() { hub.close(); }
}
