package app.jarvis.mcp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import app.jarvis.R;
import app.jarvis.core.log.Journal;
import app.jarvis.rt.Jarvis;

/** Foreground service that keeps the process (and so the MCP server) alive while the agent works. */
public class McpService extends Service {
    public static final String ACTION_STOP = "app.jarvis.mcp.STOP";
    private static final String CHANNEL = "jarvis_mcp";
    private static final int NOTE_ID = 18;

    static void startQuietly(Context app, Jarvis j) {
        try { McpBridge.startService(app); }
        catch (Throwable t) { j.journal.log("mcp", Journal.Level.INFO, "Служба моста не запущена из фона (сервер работает без неё): " + t.getClass().getSimpleName(), null, null, -1); }
    }

    @Override public int onStartCommand(Intent in, int flags, int startId) {
        Jarvis j = Jarvis.get();
        if (in != null && ACTION_STOP.equals(in.getAction())) {
            McpBridge b = McpBridge.get();
            if (b != null) b.stop();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        try { startForeground(NOTE_ID, notification()); }
        catch (Throwable t) { if (j != null) j.journal.log("mcp", Journal.Level.WARN, "startForeground отклонён: " + t.getMessage(), null, null, -1); }
        if (j != null) McpBridge.ensure(this, j);
        return START_NOT_STICKY;
    }

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Мост для агента", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        int imm = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, McpService.class).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | imm);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_notify).setContentTitle("Jarvis").setContentText("Мост для агента активен").setOngoing(true).setShowWhen(false)
            .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stop), "Остановить", stop).build());
        return b.build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
