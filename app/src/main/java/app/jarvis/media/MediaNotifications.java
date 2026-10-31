package app.jarvis.media;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import app.jarvis.R;
import app.jarvis.svc.JarvisService;

/**
 * Media-style notification (lock screen / Bluetooth controls) backed by a MediaSession.
 *
 * Action buttons are PendingIntents to {@link JarvisService} with the ACTION_* constants below (the host service
 * translates them into MusicPlayer calls). MediaSession transport callbacks take the same actions: first through an
 * optional {@link ActionHandler} (direct in-process path), otherwise a service intent. The MediaSession is optional:
 * if it cannot be created (headless/odd environments) everything degrades to a plain notification and playback keeps
 * working. {@link #update} is safe to call as often as every state change.
 */
public final class MediaNotifications {
    public static final String ACTION_PLAY = "app.jarvis.music.PLAY";
    public static final String ACTION_PAUSE = "app.jarvis.music.PAUSE";
    public static final String ACTION_PREV = "app.jarvis.music.PREV";
    public static final String ACTION_NEXT = "app.jarvis.music.NEXT";
    public static final String ACTION_STOP = "app.jarvis.music.STOP";
    public static final String CHANNEL_ID = "jarvis_media";
    public static final int NOTIFICATION_ID = 18;

    /** Direct receiver of session transport commands; used instead of the service intent when set. */
    public interface ActionHandler { void onAction(String action); }

    private static volatile ActionHandler actionHandler;
    private static final Object lock = new Object();
    private static MediaSession session;
    private static volatile String lastError = "";

    private MediaNotifications() { }

    public static void setActionHandler(ActionHandler h) { actionHandler = h; }

    /** The live session, or null when unavailable (never throws). */
    public static MediaSession currentSession() { synchronized (lock) { return session; } }

    /** Why the session/notification last failed, "" if none. */
    public static String lastError() { return lastError; }

    public static void ensureChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Воспроизведение медиа", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Управление музыкой: кнопки на экране блокировки и Bluetooth");
        ch.setShowBadge(false);
        try { nm.createNotificationChannel(ch); } catch (Throwable ignored) { }
    }

    /** Pure builder: reads only its arguments, returns the notification (token may be null → no MediaStyle session). */
    public static Notification build(Context c, MusicOut.Info i, MediaSession.Token token) {
        boolean playing = i.state == MusicOut.State.PLAYING;
        Notification.Builder b = new Notification.Builder(c, CHANNEL_ID);
        b.setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle(i.title == null || i.title.isEmpty() ? "Музыка" : i.title)
            .setContentText(playing ? "Воспроизведение" : i.state == MusicOut.State.PAUSED ? "Пауза" : "Остановлено")
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setDeleteIntent(service(c, ACTION_STOP, 105))
            .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_skip_prev), "Предыдущий", service(c, ACTION_PREV, 103)).build())
            .addAction(new Notification.Action.Builder(Icon.createWithResource(c, playing ? R.drawable.ic_pause : R.drawable.ic_play),
                playing ? "Пауза" : "Играть", playing ? service(c, ACTION_PAUSE, 102) : service(c, ACTION_PLAY, 101)).build())
            .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_skip_next), "Следующий", service(c, ACTION_NEXT, 104)).build())
            .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stop), "Остановить", service(c, ACTION_STOP, 105)).build());
        Notification.MediaStyle st = new Notification.MediaStyle();
        if (token != null) st.setMediaSession(token);
        st.setShowActionsInCompactView(0, 1, 2);
        b.setStyle(st);
        return b.build();
    }

    /**
     * Rebuilds and posts the media notification for the given state, keeping the MediaSession metadata/playback state
     * in sync. Frequent calls are fine (one reused session, idempotent posting). Never throws.
     *
     * @return false if posting failed (see {@link #lastError}).
     */
    public static boolean update(Context ctx, MusicOut.Info i) {
        if (ctx == null || i == null) return false;
        final Context c = ctx.getApplicationContext();
        synchronized (lock) {
            try {
                ensureChannel(c);
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm == null) return false;
                boolean active = i.state == MusicOut.State.PLAYING || i.state == MusicOut.State.PAUSED;
                if (!active) {
                    if (session != null) {
                        try { session.setActive(false); session.release(); } catch (Throwable ignored) { }
                        session = null;
                    }
                    nm.cancel(NOTIFICATION_ID);
                    return true;
                }
                MediaSession s = session;
                if (s == null) session = s = createSession(c);
                MediaSession.Token token = null;
                if (s != null) {
                    try {
                        s.setMetadata(new MediaMetadata.Builder()
                            .putString(MediaMetadata.METADATA_KEY_TITLE, i.title)
                            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Jarvis")
                            .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0, i.durationMs))
                            .build());
                        s.setPlaybackState(new PlaybackState.Builder()
                            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
                            .setState(i.state == MusicOut.State.PLAYING ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                                Math.max(0, i.positionMs), i.state == MusicOut.State.PLAYING ? 1f : 0f)
                            .build());
                        s.setActive(true);
                        token = s.getSessionToken();
                    } catch (Throwable t) {
                        lastError = "session update: " + t; // degrade: notification without a working session
                    }
                }
                nm.notify(NOTIFICATION_ID, build(c, i, token));
                return true;
            } catch (Throwable t) {
                lastError = String.valueOf(t);
                return false;
            }
        }
    }

    /** Cancels the notification and frees the session. */
    public static void release(Context c) {
        synchronized (lock) {
            if (session != null) {
                try { session.setActive(false); session.release(); } catch (Throwable ignored) { }
                session = null;
            }
            try {
                NotificationManager nm = (NotificationManager) c.getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(NOTIFICATION_ID);
            } catch (Throwable ignored) { }
        }
    }

    private static MediaSession createSession(Context c) {
        try {
            MediaSession s = new MediaSession(c, "Jarvis");
            s.setFlags(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            s.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { performAction(c, ACTION_PLAY); }
                @Override public void onPause() { performAction(c, ACTION_PAUSE); }
                @Override public void onSkipToNext() { performAction(c, ACTION_NEXT); }
                @Override public void onSkipToPrevious() { performAction(c, ACTION_PREV); }
                @Override public void onStop() { performAction(c, ACTION_STOP); }
            });
            return s;
        } catch (Throwable t) {
            lastError = "session create: " + t;
            return null;
        }
    }

    /** Routes a transport command: direct handler if registered, otherwise the host service. */
    public static void performAction(Context c, String action) {
        ActionHandler h = actionHandler;
        if (h != null) { h.onAction(action); return; }
        try { c.startService(new Intent(c, JarvisService.class).setAction(action)); } catch (Throwable ignored) { }
    }

    private static PendingIntent service(Context c, String action, int requestCode) {
        Intent in = new Intent(c, JarvisService.class).setAction(action);
        return PendingIntent.getService(c, requestCode, in, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
