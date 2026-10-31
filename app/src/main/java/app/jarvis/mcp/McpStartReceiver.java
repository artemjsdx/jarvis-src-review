package app.jarvis.mcp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import app.jarvis.core.log.Journal;
import app.jarvis.rt.Jarvis;

/**
 * Starts the bridge without any UI: {@code am broadcast -n <pkg>/app.jarvis.mcp.McpStartReceiver -a app.jarvis.mcp.START} from Termux.
 * Delivery already creates the process (JarvisApp.onCreate installs the bridge); this receiver makes sure the server runs and tries to
 * keep the process alive with the foreground service. Android never delivers broadcasts to a freshly installed or force-stopped app
 * until the user has opened it once.
 */
public class McpStartReceiver extends BroadcastReceiver {
    public static final String ACTION = "app.jarvis.mcp.START";

    @Override public void onReceive(Context c, Intent in) {
        if (in == null || !ACTION.equals(in.getAction())) return;
        Jarvis j = Jarvis.get();
        if (j == null || !McpBridge.enabled(j)) return;
        McpBridge b = McpBridge.ensure(c, j);
        if (b == null) return;
        try { McpBridge.startService(c.getApplicationContext()); }
        catch (Throwable t) { j.journal.log("mcp", Journal.Level.INFO, "Служба моста не запущена из фона (сервер работает без неё): " + t.getClass().getSimpleName(), null, null, -1); }
        setResultData("started " + b.port());
    }
}
