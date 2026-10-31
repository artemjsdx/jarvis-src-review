package app.jarvis.rt;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import app.jarvis.core.tools.TermuxCommand;
import app.jarvis.core.tools.TermuxTool;
import app.jarvis.core.util.CancelToken;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs commands in Termux through its RUN_COMMAND service (see docs/TERMUX_BRIDGE.md). Each request carries a
 * PendingIntent to {@link TermuxResultReceiver}; Termux sends the result bundle back there, and it is matched to the
 * waiting caller by the request id in the intent data URI.
 */
public final class TermuxBridge implements TermuxTool.Transport {
    public static final String PACKAGE = "com.termux";
    public static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    static final String SERVICE = "com.termux.app.RunCommandService";
    static final String ACTION_RUN = "com.termux.RUN_COMMAND";
    static final String EXTRA_PATH = "com.termux.RUN_COMMAND_PATH";
    static final String EXTRA_ARGS = "com.termux.RUN_COMMAND_ARGUMENTS";
    static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    static final String EXTRA_PENDING = "com.termux.RUN_COMMAND_PENDING_INTENT";
    static final String EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL";
    static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    static final String HOME_DIR = "/data/data/com.termux/files/home";
    static final String RESULT_SCHEME = "jarvis-termux";

    private static final Map<String, Waiter> WAITING = new ConcurrentHashMap<>();

    private static final class Waiter {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile TermuxTool.Reply reply;
    }

    private final Context ctx;

    public TermuxBridge(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    public static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static boolean permissionGranted(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public boolean ready() { return installed(ctx) && permissionGranted(ctx); }

    @Override public TermuxTool.Reply exec(String id, String wrapper, long waitMs, CancelToken cancel) throws IOException {
        Waiter w = new Waiter();
        WAITING.put(id, w);
        try {
            Intent run = runIntent(new String[]{"-lc", wrapper});
            run.putExtra(EXTRA_PENDING, resultPending(id));
            run.putExtra(EXTRA_LABEL, "Jarvis");
            send(run);
            Runnable unhook = cancel.onCancel(new Runnable() { @Override public void run() { w.latch.countDown(); } });
            try {
                w.latch.await(waitMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                unhook.run();
            }
            return w.reply;
        } finally {
            WAITING.remove(id);
        }
    }

    @Override public void kill(String id) {
        try {
            send(runIntent(new String[]{"-lc", TermuxCommand.killScript(id)}));
        } catch (IOException ignored) {
            // nothing was started, so there is nothing to kill
        }
    }

    /** Called by the result receiver on the main thread. A reply for an unknown or finished id is dropped. */
    static void deliver(String id, Bundle b) {
        Waiter w = id == null ? null : WAITING.get(id);
        if (w == null) return;
        if (b == null) {
            w.reply = new TermuxTool.Reply(-1, "", "", 1, "Termux прислал пустой ответ");
        } else {
            w.reply = new TermuxTool.Reply(b.getInt("exitCode", -1), b.getString("stdout"), b.getString("stderr"),
                b.getInt("err", 0), b.getString("errmsg"));
        }
        w.latch.countDown();
    }

    static String requestId(Intent result) {
        Uri u = result.getData();
        if (u == null || !RESULT_SCHEME.equals(u.getScheme())) return null;
        return u.getLastPathSegment();
    }

    private Intent runIntent(String[] args) {
        Intent i = new Intent(ACTION_RUN);
        i.setComponent(new ComponentName(PACKAGE, SERVICE));
        i.putExtra(EXTRA_PATH, BASH);
        i.putExtra(EXTRA_ARGS, args);
        i.putExtra(EXTRA_WORKDIR, HOME_DIR);
        i.putExtra(EXTRA_BACKGROUND, true);
        return i;
    }

    private PendingIntent resultPending(String id) {
        Intent res = new Intent(ctx, TermuxResultReceiver.class);
        res.setData(new Uri.Builder().scheme(RESULT_SCHEME).authority("result").appendPath(id).build());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        return PendingIntent.getBroadcast(ctx, id.hashCode(), res, flags);
    }

    private void send(Intent run) throws IOException {
        ComponentName started;
        try {
            started = ctx.startService(run);
        } catch (IllegalStateException e) {
            throw new IOException("Android не разрешил запуск Termux из фона: откройте Jarvis и повторите", e);
        } catch (SecurityException e) {
            throw new IOException("Нет разрешения com.termux.permission.RUN_COMMAND: выдайте его в настройках песочницы", e);
        }
        if (started == null) throw new IOException("Termux не установлен или его служба недоступна");
    }
}
