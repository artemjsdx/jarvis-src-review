package app.jarvis.svc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import app.jarvis.MainActivity;
import app.jarvis.R;
import app.jarvis.core.log.Journal;
import app.jarvis.media.MediaNotifications;
import app.jarvis.media.MusicOut;
import app.jarvis.rt.AState;
import app.jarvis.rt.Jarvis;
import app.jarvis.rt.JarvisListener;

/**
 * Keeps the process alive and honest about it: foreground notification while the microphone listens for the wake word,
 * music plays or a task runs; wake lock only while there is work; stops itself when idle.
 */
public class JarvisService extends Service {
    public static final String ACTION_STOP_ALL = "app.jarvis.STOP_ALL", ACTION_SYNC = "app.jarvis.SYNC", ACTION_MUSIC = "app.jarvis.MUSIC";
    private static final String CHANNEL = "jarvis_service";
    private static final int NOTE_ID = 17;
    private static volatile boolean running;

    private final Handler h = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private Jarvis j;
    private boolean foreground;
    private long idleSince;
    private final Runnable tick = new Runnable() { @Override public void run() { evaluate(); if (running) h.postDelayed(this, 4000); } };
    private final JarvisListener listener = new JarvisListener() {
        @Override public void onState(AState s, String d) { evaluate(); }
        @Override public void onMusic(MusicOut.Info i) { evaluate(); }
        @Override public void onRunChanged(boolean r) { evaluate(); }
    };

    /** Call whenever something that may need the service changed (wake toggled, music started, task started). */
    public static void sync(Context c) {
        Jarvis j = Jarvis.get();
        if (j == null) return;
        if (!needed(j)) { if (running) c.startService(new Intent(c, JarvisService.class).setAction(ACTION_SYNC)); return; }
        Intent i = new Intent(c, JarvisService.class).setAction(ACTION_SYNC);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Throwable t) {
            j.journal.w(Journal.Cat.SYSTEM, "Не удалось запустить фоновую службу из фона: " + t.getMessage(), t, null);
        }
    }

    private static boolean needed(Jarvis j) {
        MusicOut m = j.music;
        boolean music = m != null && (m.info().state == MusicOut.State.PLAYING || m.info().state == MusicOut.State.PAUSED);
        return j.voice.isWakeEnabled() || music || j.agent.isRunning() || j.speaker.isSpeaking() || j.voice.isMicActive();
    }

    @Override public void onCreate() {
        super.onCreate();
        j = Jarvis.get();
        running = true;
        if (j != null) j.addListener(listener);
        h.postDelayed(tick, 4000);
    }

    @Override public int onStartCommand(Intent in, int flags, int startId) {
        if (j == null) { stopSelf(); return START_NOT_STICKY; }
        String a = in == null ? null : in.getAction();
        if (a != null && a.startsWith("app.jarvis.music.")) {
            MediaNotifications.performAction(this, a);          // routed to the player by the handler registered in Modules
            startForegroundIfNeeded();
            evaluate();
            return START_STICKY;
        }
        if (ACTION_STOP_ALL.equals(a)) {
            j.settings.setBool("wake.enabled", false);
            j.voice.setWakeEnabled(false);
            j.cancelAll();
            MusicOut m = j.music;
            if (m != null) m.stop();
            j.journal.log(Journal.Cat.SYSTEM, Journal.Level.INFO, "Остановлено из уведомления", null, null, -1);
        }
        startForegroundIfNeeded();
        evaluate();
        return START_STICKY;
    }

    private int fgId;

    private boolean musicActive() {
        MusicOut m = j == null ? null : j.music;
        if (m == null) return false;
        MusicOut.State st = m.info().state;
        return st == MusicOut.State.PLAYING || st == MusicOut.State.PAUSED;
    }

    /** Foreground notification = the media notification while music is active (lock-screen controls), else the plain status one. */
    private void showForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (musicActive()) {
            MusicOut.Info mi = j.music.info();
            MediaNotifications.update(this, mi);                // keeps the MediaSession metadata/state in sync
            android.media.session.MediaSession s = MediaNotifications.currentSession();
            Notification n = MediaNotifications.build(this, mi, s == null ? null : s.getSessionToken());
            startForeground(MediaNotifications.NOTIFICATION_ID, n);
            if (fgId != MediaNotifications.NOTIFICATION_ID && nm != null) nm.cancel(NOTE_ID);
            fgId = MediaNotifications.NOTIFICATION_ID;
        } else {
            startForeground(NOTE_ID, buildNotification());
            if (fgId == MediaNotifications.NOTIFICATION_ID) MediaNotifications.update(this, j.music == null ? new MusicOut.Info() : j.music.info());
            fgId = NOTE_ID;
        }
        foreground = true;
    }

    private void startForegroundIfNeeded() {
        if (foreground) return;
        try {
            showForeground();
        } catch (Throwable t) {
            j.journal.e(Journal.Cat.SYSTEM, "startForeground отклонён системой: " + t.getMessage(), t);
        }
    }

    private void evaluate() {
        if (j == null) return;
        boolean need = needed(j);
        if (need) {
            idleSince = 0;
            holdLocks(true);
            if (!foreground) startForegroundIfNeeded();
            if (foreground) { try { showForeground(); } catch (Throwable t) { j.journal.log(Journal.Cat.SYSTEM, Journal.Level.WARN, "Обновление уведомления: " + t.getMessage(), null, null, -1); } }
        } else {
            long now = System.currentTimeMillis();
            if (idleSince == 0) idleSince = now;
            if (now - idleSince > 15000) {
                holdLocks(false);
                stopForeground(true);
                foreground = false;
                stopSelf();
            }
        }
    }

    private void holdLocks(boolean on) {
        try {
            if (on) {
                if (wake == null) { PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE); wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:work"); wake.setReferenceCounted(false); }
                if (!wake.isHeld()) wake.acquire(10 * 60 * 1000L);   // re-acquired by tick(); never held forever
                else if (System.currentTimeMillis() % 8 == 0) wake.acquire(10 * 60 * 1000L);
                if (j.voice.isWakeEnabled()) {
                    if (wifi == null) { WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE); if (wm != null) { wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "jarvis:wifi"); wifi.setReferenceCounted(false); } }
                    if (wifi != null && !wifi.isHeld()) wifi.acquire();
                } else if (wifi != null && wifi.isHeld()) wifi.release();
            } else {
                if (wake != null && wake.isHeld()) wake.release();
                if (wifi != null && wifi.isHeld()) wifi.release();
            }
        } catch (Throwable ignored) { }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Работа Jarvis", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Показывает, что Jarvis слушает обращение, играет музыку или выполняет задачу");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        String text = j == null ? "" : j.voice.isWakeEnabled() ? "Слушаю обращение «Джарвис»" : j.statusText();
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, JarvisService.class).setAction(ACTION_STOP_ALL), PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_notify).setContentTitle("Jarvis").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stop), "Остановить", stop).build());
        return b.build();
    }

    @Override public void onDestroy() {
        running = false;
        h.removeCallbacksAndMessages(null);
        if (j != null) j.removeListener(listener);
        holdLocks(false);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
