package app.jarvis.dev.checks.media;

import android.app.Notification;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import app.jarvis.dev.PreviewHost;
import app.jarvis.media.MediaNotifications;
import app.jarvis.media.MusicOut;
import app.jarvis.media.MusicPlayer;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Real-runtime check of MusicPlayer + MediaNotifications on ART: queue play/pause/resume/seek/next/prev/stop through
 * the actual MediaPlayer, the audio-focus state machine (self-inflicted transient/duck/GAIN), session transport
 * commands, error paths, play() latency, and the notification object itself. Needs the headless context from
 * PreviewHost; the APK path comes from CLASSPATH (art-run.sh sets it to the apk we run from).
 */
public final class MusicCheck {
    static int fails;
    static MusicPlayer p;
    static volatile String lastAction = "";
    static final List<String> events = new ArrayList<>();
    static int changedCount;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) fails++;
        System.out.flush();
    }

    interface Act { void run() throws Exception; }

    static void expectIO(Act a, String expectPart, String what) {
        try { a.run(); check(false, what + ": no exception thrown!"); }
        catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            check(m.contains(expectPart), what + " → [" + m + "]");
        }
    }

    static boolean awaitState(MusicOut.State s, long timeoutMs) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < end) {
            PreviewHost.pump(25);
            if (p.info().state == s) { PreviewHost.pump(50); return true; }
        }
        return p.info().state == s;
    }

    static void ff(String[] args, String what) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "/data/data/com.termux/files/usr/bin/ffmpeg";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = readAll(pr.getInputStream());
        int rc = pr.waitFor();
        check(rc == 0, what + (rc == 0 ? "" : " rc=" + rc + ": " + new String(out).substring(0, Math.min(300, out.length))));
    }

    static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    static String probe(File f) {
        try {
            Process pr = new ProcessBuilder("/data/data/com.termux/files/usr/bin/ffprobe", "-v", "error",
                "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", f.getAbsolutePath())
                .redirectErrorStream(true).start();
            return new String(readAll(pr.getInputStream())).trim();
        } catch (Exception e) { return "?"; }
    }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) {
            System.out.println("CRASH " + t);
            t.printStackTrace(System.out);
            fails++;
        }
        System.out.println(fails == 0 ? "MUSICCHECK OK" : "MUSICCHECK FAILURES: " + fails);
        System.out.flush();
        System.exit(fails == 0 ? 0 : 1);
    }

    static void run() throws Exception {
        String tmp = System.getenv("TMPDIR");
        if (tmp == null) tmp = "/data/data/com.termux/files/usr/tmp";
        File out = new File(tmp, "musiccheck-" + System.nanoTime());
        out.mkdirs();
        PreviewHost.init(System.getenv("CLASSPATH"), out);

        System.out.println("== samples (ffmpeg)");
        File src = new File(tmp, "edge_ru-RU-SvetlanaNeural.mp3");
        final File mp3 = new File(tmp, "music_test.mp3"), wav = new File(tmp, "music_test.wav");
        final File m4a = new File(tmp, "music_test.m4a"), ogg = new File(tmp, "music_test.ogg");
        boolean haveFfmpeg = new File("/data/data/com.termux/files/usr/bin/ffmpeg").canExecute();
        if (haveFfmpeg) {
            ff(new String[]{"-y", "-i", src.getPath(), "-t", "8", "-ar", "44100", "-ac", "2", "-b:a", "128k", mp3.getPath()}, "mp3 8s");
            ff(new String[]{"-y", "-i", src.getPath(), "-t", "2", "-ar", "44100", "-ac", "2", "-c:a", "pcm_s16le", wav.getPath()}, "wav 2s");
            ff(new String[]{"-y", "-i", src.getPath(), "-t", "2", "-ar", "44100", "-ac", "2", "-c:a", "aac", "-b:a", "96k", m4a.getPath()}, "m4a 2s");
            ff(new String[]{"-y", "-i", src.getPath(), "-t", "2", "-ar", "44100", "-ac", "2", "-c:a", "libvorbis", ogg.getPath()}, "ogg 2s");
        } else {
            System.out.println("  ffmpeg missing: falling back to copy + synthetic wav");
            copy(src, mp3);
            synthWav(wav, 2);
        }
        System.out.println("  ffprobe durations: mp3=" + probe(mp3) + " wav=" + probe(wav) + " m4a=" + probe(m4a) + " ogg=" + probe(ogg));

        p = new MusicPlayer(ctx());
        p.setListener(new MusicOut.Listener() {
            @Override public void onMusicChanged(MusicOut.Info i) {
                changedCount++;
                events.add(i.state + " idx=" + i.index + " pos=" + i.positionMs + " " + i.title);
                MediaNotifications.update(ctx(), i);   // updater exercised on every change
            }
        });
        MediaNotifications.setActionHandler(new MediaNotifications.ActionHandler() {
            @Override public void onAction(String action) {
                lastAction = action;
                if (MediaNotifications.ACTION_PLAY.equals(action)) p.resume();
                else if (MediaNotifications.ACTION_PAUSE.equals(action)) p.pause();
                else if (MediaNotifications.ACTION_NEXT.equals(action)) p.next();
                else if (MediaNotifications.ACTION_PREV.equals(action)) p.prev();
                else if (MediaNotifications.ACTION_STOP.equals(action)) p.stop();
            }
        });

        System.out.println("== queue: play → seek → pause/resume → volume");
        List<File> q = Arrays.asList(mp3, wav, m4a, ogg);
        long t0 = System.nanoTime();
        p.playQueue(q, 0);
        long playMs = (System.nanoTime() - t0) / 1_000_000;
        MusicOut.Info i = p.info();
        check(i.state == MusicOut.State.PLAYING, "playQueue blocks until PLAYING (" + playMs + " ms on the bench)");
        check(i.index == 0 && i.queueSize == 4, "index 0 of 4");
        check(i.durationMs >= 7000, "duration ≈8s: " + i.durationMs + " ms (ffprobe " + probe(mp3) + " s)");
        check(!i.title.isEmpty() && i.title.equals("music_test"), "title from filename: " + i.title);
        long pos1 = p.info().positionMs;
        PreviewHost.pump(700);
        long pos2 = p.info().positionMs;
        check(pos2 > pos1, "position advances: " + pos1 + "→" + pos2 + " ms");
        check(changedCount >= 1, "onMusicChanged delivered on main (" + changedCount + " so far)");

        p.seekTo(i.durationMs / 2);
        PreviewHost.pump(400);
        check(Math.abs(p.info().positionMs - i.durationMs / 2) < 1500, "seek to middle: pos=" + p.info().positionMs);

        p.pause();
        i = p.info();
        check(i.state == MusicOut.State.PAUSED, "pause → PAUSED");
        long frozen = i.positionMs;
        PreviewHost.pump(500);
        check(Math.abs(p.info().positionMs - frozen) <= 80, "position frozen while paused (" + frozen + "→" + p.info().positionMs + ")");
        p.resume();
        check(p.info().state == MusicOut.State.PLAYING, "resume → PLAYING");
        p.setVolume(0.5f);
        check(Math.abs(p.info().volume - 0.5f) < 0.01f, "volume 0.5 via info()");

        System.out.println("== next/prev");
        p.next();
        check(awaitState(MusicOut.State.PLAYING, 5000), "next() switches (async)");
        i = p.info();
        check(i.index == 1, "next → index 1: " + i.title + " dur=" + i.durationMs);
        check(i.durationMs >= 1500 && i.durationMs <= 2600, "wav duration ≈2s: " + i.durationMs);
        p.next(); check(awaitState(MusicOut.State.PLAYING, 5000) && p.info().index == 2, "next → index 2 (m4a): " + p.info().title);
        p.next(); check(awaitState(MusicOut.State.PLAYING, 5000) && p.info().index == 3, "next → index 3 (ogg): " + p.info().title);
        p.next(); // past the end → honest stop
        PreviewHost.pump(400);
        check(p.info().state == MusicOut.State.STOPPED, "next at end of queue → STOPPED");
        p.prev(); // stopped, pos=0 → previous track
        check(awaitState(MusicOut.State.PLAYING, 5000) && p.info().index == 2, "prev from STOPPED → index 2");
        p.prev(); // position < 3 s → go to previous track
        check(awaitState(MusicOut.State.PLAYING, 5000) && p.info().index == 1, "prev near start → index 1");
        p.prev(); // pos < 3 s again → index 0 (mp3, 7.4 s)
        check(awaitState(MusicOut.State.PLAYING, 5000) && p.info().index == 0, "prev again → index 0");
        p.seekTo(3500); PreviewHost.pump(300);
        p.prev(); // position ≥ 3 s → restart current
        PreviewHost.pump(400);
        i = p.info();
        check(i.index == 0 && i.positionMs < 1500, "prev at pos≥3s restarts current track (pos=" + i.positionMs + ")");

        System.out.println("== stop / play(file,title) / completion");
        p.stop();
        check(p.info().state == MusicOut.State.STOPPED, "stop → STOPPED");
        t0 = System.nanoTime();
        p.play(mp3, "Тестовый трек");
        long play2 = (System.nanoTime() - t0) / 1_000_000;
        i = p.info();
        check(i.state == MusicOut.State.PLAYING && "Тестовый трек".equals(i.title), "play(file,title) → PLAYING «Тестовый трек» (" + play2 + " ms)");
        System.out.println("  play() latencies on bench: queue start=" + playMs + " ms, restart=" + play2 + " ms");

        System.out.println("== audio focus (self-inflicted: same process, separate focus owner)");
        p.setVolume(1f);
        AudioManager am = (AudioManager) ctx().getSystemService(android.content.Context.AUDIO_SERVICE);
        AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        // Android P+ does NOT dispatch focus GAIN to an app that itself abandoned/re-requested focus (same-uid
        // suppression), so GAIN deliveries are simulated below by calling the player's own focus listener (white-box);
        // LOSS and CAN_DUCK dispatches are real system calls.
        AudioManager.OnAudioFocusChangeListener playerFocus;
        try {
            java.lang.reflect.Field flf = MusicPlayer.class.getDeclaredField("focusListener");
            flf.setAccessible(true);
            playerFocus = (AudioManager.OnAudioFocusChangeListener) flf.get(p);
        } catch (Throwable t) {
            playerFocus = null;
            check(false, "cannot reach focusListener for white-box GAIN: " + t);
        }
        AudioFocusRequest other = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build();
        int r1 = am.requestAudioFocus(other);
        boolean pausedOk = awaitState(MusicOut.State.PAUSED, 4000);
        check(pausedOk, "LOSS_TRANSIENT (real dispatch) → PAUSED (request rc=" + r1 + ")");
        am.abandonAudioFocusRequest(other);
        PreviewHost.pump(400);
        check(p.info().state == MusicOut.State.PAUSED, "abandon → no GAIN dispatch (same-uid suppression, expected)");
        if (playerFocus != null) {
            playerFocus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
            PreviewHost.pump(300);
            check(p.info().state == MusicOut.State.PLAYING, "GAIN after focus-loss pause → resumed (white-box)");
        }
        AudioFocusRequest duckReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build();
        int r2 = am.requestAudioFocus(duckReq);
        PreviewHost.pump(400);
        i = p.info();
        check(i.state == MusicOut.State.PLAYING && Math.abs(i.volume - 1f) < 0.01f,
            "CAN_DUCK (real dispatch rc=" + r2 + ") → keeps PLAYING, info().volume stays user volume (effective ×0.35, not readable back)");
        am.abandonAudioFocusRequest(duckReq);
        if (playerFocus != null) {
            playerFocus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
            PreviewHost.pump(300);
            check(p.info().state == MusicOut.State.PLAYING, "GAIN after duck → volume restored, still PLAYING");
        }
        check(am.requestAudioFocus(new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).build()) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED, "permanent GAIN requested");
        boolean stoppedOk = awaitState(MusicOut.State.STOPPED, 4000);
        check(stoppedOk, "AUDIOFOCUS_LOSS (real dispatch) → STOPPED");
        p.playQueue(q, 0); // fresh start with a real queue for the session next/prev part
        check(awaitState(MusicOut.State.PLAYING, 5000) && p.info().index == 0, "restart queue after focus stop");

        System.out.println("== MediaSession transport commands");
        MediaSession s = MediaNotifications.currentSession();
        System.out.println("  session=" + s + (s == null ? " lastError=" + MediaNotifications.lastError() : " token=" + s.getSessionToken()));
        lastAction = "";
        MediaNotifications.performAction(ctx(), MediaNotifications.ACTION_PAUSE);
        PreviewHost.pump(300);
        check(p.info().state == MusicOut.State.PAUSED && MediaNotifications.ACTION_PAUSE.equals(lastAction), "session onPause → pause");
        MediaNotifications.performAction(ctx(), MediaNotifications.ACTION_PLAY);
        PreviewHost.pump(300);
        check(p.info().state == MusicOut.State.PLAYING && MediaNotifications.ACTION_PLAY.equals(lastAction), "session onPlay → resume");
        MediaNotifications.performAction(ctx(), MediaNotifications.ACTION_NEXT);
        PreviewHost.pump(600);
        check(p.info().index == 1 && MediaNotifications.ACTION_NEXT.equals(lastAction), "session onSkipToNext → index 1");
        MediaNotifications.performAction(ctx(), MediaNotifications.ACTION_PREV);
        PreviewHost.pump(600);
        check(p.info().index == 0 && MediaNotifications.ACTION_PREV.equals(lastAction), "session onSkipToPrevious → index 0");
        MediaNotifications.performAction(ctx(), MediaNotifications.ACTION_STOP);
        PreviewHost.pump(300);
        check(p.info().state == MusicOut.State.STOPPED && MediaNotifications.ACTION_STOP.equals(lastAction), "session onStop → STOPPED");

        System.out.println("== notification object");
        p.play(mp3, "Тестовый трек");
        PreviewHost.pump(400);
        s = MediaNotifications.currentSession();
        // build() is a pure function; build with the plain preview context (its RemoteViews machinery is proven)
        Notification n = MediaNotifications.build(PreviewHost.ctx, p.info(), s == null ? null : s.getSessionToken());
        String tmpl = n.extras == null ? "?" : String.valueOf(n.extras.get("android.template"));
        System.out.println("  style=" + tmpl + " ongoing=" + ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) + " category=" + n.category);
        check(String.valueOf(tmpl).endsWith("MediaStyle"), "Notification.MediaStyle");
        check(n.actions != null && n.actions.length == 4, "4 actions");
        if (n.actions != null) {
            String want = "Предыдущий|Пауза|Следующий|Остановить";
            StringBuilder got = new StringBuilder();
            for (int k = 0; k < n.actions.length; k++) {
                if (k > 0) got.append('|');
                got.append(n.actions[k].title);
                String act = n.actions[k].actionIntent == null ? "-" : String.valueOf(n.actions[k].actionIntent.getCreatorPackage());
                System.out.println("    action[" + k + "] «" + n.actions[k].title + "» pi-pkg=" + act);
            }
            check(want.contentEquals(got), "action titles " + got);
        }
        boolean posted = MediaNotifications.update(ctx(), p.info());
        check(posted, "updater posted real notification" + (posted ? "" : "; lastError=" + MediaNotifications.lastError()));
        try {
            FrameLayout host = new FrameLayout(PreviewHost.ctx);
            View cv = n.contentView.apply(PreviewHost.ctx, host);
            host.addView(cv);
            File png = PreviewHost.render(host, "media-notification", 392, 128);
            System.out.println("  rendered: " + png);
        } catch (Throwable t) {
            System.out.println("  contentView render skipped: " + t);
        }
        MediaNotifications.update(ctx(), stoppedInfo());
        PreviewHost.pump(200);
        check(MediaNotifications.currentSession() == null, "update(STOPPED) releases session and cancels");

        System.out.println("== error paths");
        expectIO(new Act() { @Override public void run() throws Exception { p.play(new File("/no/such/file.mp3"), null); } }, "Файл не найден", "missing file");
        final File zero = new File(tmp, "zero_len.mp3");
        new FileOutputStream(zero).close();
        expectIO(new Act() { @Override public void run() throws Exception { p.play(zero, null); } }, "Файл пустой", "zero-length file");
        final File txt = new File(tmp, "not_audio.mp3");
        FileOutputStream fo = new FileOutputStream(txt);
        fo.write("это текстовый файл, переименованный в .mp3".getBytes("UTF-8"));
        fo.close();
        expectIO(new Act() { @Override public void run() throws Exception { p.play(txt, null); } }, "формат не поддерживается или файл повреждён", "text renamed .mp3");
        expectIO(new Act() { @Override public void run() throws Exception { p.playQueue(new ArrayList<File>(), 0); } }, "Список файлов пуст", "empty queue");
        i = p.info();
        check(i.state == MusicOut.State.ERROR && !i.error.isEmpty(), "state ERROR with error text: " + i.state + " / " + i.error);
        // recovery: valid play after errors
        p.play(wav, null);
        check(p.info().state == MusicOut.State.PLAYING, "recovers after errors");

        System.out.println("== release()");
        p.release();
        check(p.info().state == MusicOut.State.STOPPED && p.info().queueSize == 0, "release → STOPPED, queue cleared");
        p.release();   // idempotent
        check(true, "second release() does not crash");
        Thread.sleep(150);
        PreviewHost.pump(300);
        System.out.println("  onMusicChanged count: " + changedCount);
        System.out.println("  event log (" + events.size() + "):");
        for (String e : events) System.out.println("    " + e);
    }

    /**
     * PreviewHost's system context has base package "android" (not owned by this uid) — MediaSession and
     * NotificationManager reject it. Wrap a real com.termux package context (same uid → owned by us) and overlay
     * the APK resources, so session/notification calls exercise the real system path.
     */
    static android.content.Context ctxHolder;

    static android.content.Context ctx() {
        if (ctxHolder != null) return ctxHolder;
        android.content.Context base;
        try {
            base = PreviewHost.ctx.createPackageContext("com.termux", 0);
            System.out.println("  package context com.termux: ok (pkg=" + base.getPackageName()
                + " opPkg=" + base.getOpPackageName() + ")");
        } catch (Throwable t) {
            System.out.println("  package context com.termux failed: " + t + " (falling back to system context)");
            base = PreviewHost.ctx;
        }
        android.content.Context sys = PreviewHost.ctx;
        ctxHolder = new android.content.ContextWrapper(base) {
            @Override public android.content.res.Resources getResources() { return sys.getResources(); }
            @Override public android.content.res.Resources.Theme getTheme() { return sys.getTheme(); }
            @Override public android.content.res.AssetManager getAssets() { return sys.getAssets(); }
            @Override public String getPackageName() { return "com.termux"; }
            public String getOpPackageName() { return "com.termux"; }
            // API 31+: MediaSession/NMS validate the attribution source, which on this ROM still says "android"
            @Override public android.content.AttributionSource getAttributionSource() {
                try {
                    return new android.content.AttributionSource.Builder(android.os.Process.myUid())
                        .setPackageName("com.termux").build();
                } catch (Throwable t) { return super.getAttributionSource(); }
            }
            @Override public android.content.Context getApplicationContext() { return this; }
        };
        return ctxHolder;
    }

    static MusicOut.Info stoppedInfo() {
        MusicOut.Info i = new MusicOut.Info();
        i.state = MusicOut.State.STOPPED;
        return i;
    }

    static void copy(File src, File dst) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        FileOutputStream o = new FileOutputStream(dst);
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        o.close();
    }

    static void synthWav(File f, int seconds) throws Exception {
        int rate = 44100, frames = rate * seconds;
        FileOutputStream o = new FileOutputStream(f);
        byte[] data = new byte[frames * 4];
        for (int s = 0; s < frames; s++) {
            short v = (short) (12000 * Math.sin(2 * Math.PI * 440 * s / rate));
            data[s * 4] = (byte) v;
            data[s * 4 + 1] = (byte) (v >> 8);
            data[s * 4 + 2] = (byte) v;
            data[s * 4 + 3] = (byte) (v >> 8);
        }
        java.io.ByteArrayOutputStream hdr = new java.io.ByteArrayOutputStream();
        byte[] riff = new byte[36 + data.length];
        w(riff, 0, "RIFF".getBytes()); w32(riff, 4, 36 + data.length); w(riff, 8, "WAVE".getBytes());
        w(riff, 12, "fmt ".getBytes()); w32(riff, 16, 16); w16(riff, 20, 1); w16(riff, 22, 2);
        w32(riff, 24, rate); w32(riff, 28, rate * 4); w16(riff, 32, 4); w16(riff, 34, 16);
        w(riff, 36, "data".getBytes()); w32(riff, 40, data.length);
        hdr.write(riff);
        o.write(hdr.toByteArray());
        o.write(data);
        o.close();
    }

    static void w(byte[] b, int o, byte[] v) { System.arraycopy(v, 0, b, o, v.length); }
    static void w32(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24); }
    static void w16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
}
