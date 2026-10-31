package app.jarvis.svc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import app.jarvis.MainActivity;
import app.jarvis.R;
import app.jarvis.rt.Jarvis;

/**
 * After a reboot (or an app update) Android does not let an app start a microphone foreground service from the background,
 * so we do not pretend: if listening was enabled we post a notification that opens Jarvis and re-arms it with one tap.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent in) {
        Jarvis j = Jarvis.get();
        if (j == null || !j.settings.getBool("wake.enabled", false) || !j.settings.getBool("svc.bootNotify", true)) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel("jarvis_boot", "Напоминания Jarvis", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(c, 2, new Intent(c, MainActivity.class).putExtra("rearm", true), PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, "jarvis_boot") : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify).setContentTitle("Jarvis не слушает после перезагрузки").setContentText("Нажмите, чтобы снова включить ожидание обращения").setContentIntent(open).setAutoCancel(true);
        nm.notify(42, b.build());
    }
}
