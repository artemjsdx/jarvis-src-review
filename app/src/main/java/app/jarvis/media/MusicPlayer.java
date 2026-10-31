package app.jarvis.media;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local music player on {@link MediaPlayer}: queue with auto-advance, audio focus (GAIN: stop on LOSS, pause on
 * transient loss, duck volume on CAN_DUCK, resume only after a focus-loss pause) and main-looper listener delivery.
 * <p>
 * Threading: tools call from worker threads — {@link #play} blocks on the caller thread through
 * validate → setDataSource → prepare → focus request → start; the UI polls {@link #info()} (a cheap synchronized
 * snapshot) from the main thread. Listener callbacks are always posted to the main looper.
 */
public class MusicPlayer implements MusicOut {
    public static final String ERR_FOCUS_DENIED = "Не удалось получить аудиофокус: другое приложение использует звук";
    private static final float DUCK_FACTOR = 0.35f;

    private final Context app;
    private final Object lock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger(1);
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "jarvis-music-" + n.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY + 1);
            return t;
        }
    });

    private final AudioManager audio;
    private final AudioFocusRequest focusRequest;

    private final ArrayList<File> queue = new ArrayList<>();
    private final ArrayList<String> titles = new ArrayList<>();
    private MediaPlayer player;          // published only after prepare(); never touched by other threads before
    private int index = -1;
    private State state = State.IDLE;
    private String title = "", path = "", error = "";
    private float volume = 1f;
    private long durationMs, storedPositionMs;
    private int gen = 0;                 // bumped by stop()/release() to invalidate in-flight starts
    private boolean released;
    private boolean pausedByFocus, ducked;
    private volatile Listener listener;

    public MusicPlayer(Context ctx) {
        app = ctx.getApplicationContext();
        audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        AudioFocusRequest fr = null;
        try {
            fr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs())
                .setOnAudioFocusChangeListener(focusListener, main)
                .build();
        } catch (Throwable ignored) { } // degrade: legacy API below / play without focus
        focusRequest = fr;
    }

    @Override public void setListener(Listener l) { listener = l; }

    @Override public void play(File f, String t) throws IOException {
        if (f == null) throw new IOException("Файл не указан");
        ArrayList<File> q = new ArrayList<>(1);
        q.add(f);
        synchronized (lock) {
            queue.clear();
            queue.addAll(q);
            titles.clear();
            titles.add(t == null || t.trim().isEmpty() ? baseName(f) : t.trim());
            index = -1;
        }
        startIndex(0, true);
    }

    @Override public void playQueue(List<File> files, int startIndex) throws IOException {
        if (files == null || files.isEmpty()) throw new IOException("Список файлов пуст");
        if (startIndex < 0 || startIndex >= files.size()) throw new IOException("Неверный индекс трека: " + startIndex);
        synchronized (lock) {
            queue.clear();
            queue.addAll(files);
            titles.clear();
            for (File f : files) titles.add(f == null ? "" : baseName(f));
            this.index = -1;
        }
        startIndex(startIndex, true);
    }

    @Override public void pause() {
        boolean fire = false;
        synchronized (lock) {
            if (state == State.PLAYING && player != null) {
                try { player.pause(); state = State.PAUSED; pausedByFocus = false; fire = true; }
                catch (Throwable ignored) { }
            }
        }
        if (fire) fireChanged();
    }

    @Override public void resume() {
        boolean fire = false;
        synchronized (lock) {
            if (state == State.PAUSED && player != null) {
                try { player.start(); state = State.PLAYING; pausedByFocus = false; fire = true; }
                catch (Throwable ignored) { }
            }
        }
        if (fire) fireChanged();
    }

    @Override public void stop() {
        synchronized (lock) {
            gen++;
            closePlayerLocked();
            abandonFocus();
            state = State.STOPPED;
            error = "";
            storedPositionMs = 0;
            pausedByFocus = false;
            ducked = false;
        }
        fireChanged();
    }

    @Override public void next() {
        final int target;
        final boolean advance;
        synchronized (lock) {
            target = index + 1;
            advance = target < queue.size();
            if (!advance) {                 // end of queue: stop honestly instead of looping
                gen++;
                closePlayerLocked();
                abandonFocus();
                state = State.STOPPED;
                storedPositionMs = 0;
            }
        }
        if (advance) startAsync(target); else fireChanged();
    }

    @Override public void prev() {
        final int target;
        synchronized (lock) {
            long pos = positionLocked();
            if (index > 0 && pos >= 3000) { // near the start of the current track: restart it instead of switching
                seekLocked(0);
                if (state == State.PAUSED) { try { player.start(); state = State.PLAYING; } catch (Throwable ignored) { } }
                fireChanged();
                return;
            }
            target = index - 1;
            if (target < 0) {
                seekLocked(0);
                if (state == State.PAUSED && player != null) { try { player.start(); state = State.PLAYING; } catch (Throwable ignored) { } }
                fireChanged();
                return;
            }
        }
        startAsync(target);
    }

    @Override public void seekTo(long ms) {
        synchronized (lock) { seekLocked(ms); }
        fireChanged();
    }

    @Override public void setVolume(float v) {
        synchronized (lock) {
            volume = clamp(v);
            applyVolumeLocked();
        }
        fireChanged();
    }

    @Override public Info info() {
        synchronized (lock) {
            Info i = new Info();
            i.state = state;
            i.title = title;
            i.path = path;
            i.error = error;
            i.index = index;
            i.queueSize = queue.size();
            i.volume = volume;
            if (player != null) {
                try { durationMs = Math.max(0, player.getDuration()); } catch (Throwable ignored) { }
                if (state == State.PLAYING || state == State.PAUSED) {
                    try { storedPositionMs = Math.max(0, player.getCurrentPosition()); } catch (Throwable ignored) { }
                }
            }
            i.durationMs = durationMs;
            i.positionMs = storedPositionMs;
            return i;
        }
    }

    @Override public void release() {
        synchronized (lock) {
            released = true;
            gen++;
            closePlayerLocked();
            abandonFocus();
            state = State.STOPPED;
            queue.clear();
            titles.clear();
            index = -1;
        }
        fireChanged();
        worker.shutdownNow();
    }

    // ---- internals ----

    /**
     * Blocking track start: validate → prepare on the current thread → focus → start → verify playing.
     * blocking=true (from play/playQueue) throws on every failure; async paths (next/prev/auto-advance) turn
     * failures into state ERROR.
     */
    private void startIndex(final int idx, final boolean blocking) throws IOException {
        final File f;
        synchronized (lock) {
            if (blocking && released) throw new IOException("Плеер освобождён");
            if (idx < 0 || idx >= queue.size()) return;
            f = queue.get(idx);
        }
        validate(f);
        final boolean hadPlayer;
        final int myGen;
        synchronized (lock) {
            hadPlayer = player != null;
            gen++;               // invalidate anything older than this start
            closePlayerLocked();
            abandonFocus();
            state = State.STOPPED;
            storedPositionMs = 0;
            myGen = gen;         // captured after our own bump: only external stop()/release() can invalidate now
        }
        if (hadPlayer) fireChanged();

        final MediaPlayer mp;
        try {
            mp = createPlayer(f);
        } catch (IOException e) {
            failStart(myGen, e.getMessage());
            throw e;
        }

        try {
            synchronized (lock) {
                if (gen != myGen || released) {   // stopped/released while we were preparing
                    release(mp);
                    if (blocking) throw new IOException("Воспроизведение остановлено");
                    return;
                }
                player = mp;
                index = idx;
                title = titles.get(idx);
                path = f.getAbsolutePath();
                error = "";
                state = State.PAUSED;
            }
            int res = requestFocusNow();
            if (res != AudioManager.AUDIOFOCUS_REQUEST_GRANTED && res != AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
                synchronized (lock) {
                    release(mp);
                    if (player == mp) player = null;
                    if (gen != myGen || released) {
                        if (blocking) throw new IOException("Воспроизведение остановлено");
                        return;
                    }
                    state = State.ERROR;
                    error = ERR_FOCUS_DENIED;
                }
                fireChanged();
                throw new IOException(ERR_FOCUS_DENIED);
            }
            mp.start();
            waitReallyPlaying(mp);
            synchronized (lock) {
                if (gen != myGen || released) {
                    release(mp);
                    if (blocking) throw new IOException("Воспроизведение остановлено");
                    return;
                }
                pausedByFocus = false;
                state = State.PLAYING;
                applyVolumeLocked();
            }
            fireChanged();
        } catch (IOException e) {
            if (player == mp) {   // start() was accepted but playing never confirmed: clean up honestly
                release(mp);
                synchronized (lock) {
                    if (player == mp) player = null;
                    if (gen != myGen || released) return;
                    state = State.ERROR;
                    error = e.getMessage();
                }
                fireChanged();
            }
            throw e;
        } catch (Throwable t) {
            release(mp);
            synchronized (lock) {
                if (player == mp) player = null;
                if (released || gen != myGen) return;
                state = State.ERROR;
                error = "Не удалось начать воспроизведение";
            }
            fireChanged();
            throw new IOException("Не удалось начать воспроизведение: " + t, t);
        }
    }

    private void failStart(int myGen, String msg) {
        synchronized (lock) {
            if (gen == myGen && !released) { state = State.ERROR; error = msg; }
        }
        fireChanged();
    }

    private static void release(MediaPlayer mp) {
        if (mp != null) { try { mp.release(); } catch (Throwable ignored) { } }
    }

    private void startAsync(final int idx) {
        worker.execute(new Runnable() {
            @Override public void run() {
                try {
                    startIndex(idx, false);
                } catch (IOException e) {
                    synchronized (lock) {
                        if (!released) { state = State.ERROR; error = e.getMessage(); }
                    }
                    fireChanged();
                } catch (Throwable t) {
                    synchronized (lock) {
                        if (!released) { state = State.ERROR; error = "Ошибка переключения трека: " + t; }
                    }
                    fireChanged();
                }
            }
        });
    }

    private static void validate(File f) throws IOException {
        String p = f == null ? "null" : f.getAbsolutePath();
        if (f == null || !f.exists()) throw new IOException("Файл не найден: " + p);
        if (f.isDirectory()) throw new IOException("Это папка, а не аудиофайл: " + p);
        if (f.length() == 0) throw new IOException("Файл пустой: " + p);
        if (!f.isFile() || !f.canRead()) throw new IOException("Файл недоступен для чтения: " + p);
    }

    private MediaPlayer createPlayer(File f) throws IOException {
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(attrs());
            mp.setOnCompletionListener(onCompletion);
            mp.setOnErrorListener(onError);
            mp.setDataSource(f.getAbsolutePath());
            mp.prepare();
            return mp;
        } catch (Throwable t) {
            try { mp.release(); } catch (Throwable ignored) { }
            throw new IOException("Не удалось воспроизвести файл: формат не поддерживается или файл повреждён ("
                + String.valueOf(t.getMessage()) + ")", t);
        }
    }

    private static AudioAttributes attrs() {
        return new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
    }

    private static void waitReallyPlaying(MediaPlayer mp) throws IOException {
        long deadline = SystemClock.elapsedRealtime() + 2000;
        while (SystemClock.elapsedRealtime() < deadline) {
            try { if (mp.isPlaying()) return; } catch (Throwable ignored) { }
            try { Thread.sleep(10); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        try { if (mp.isPlaying()) return; } catch (Throwable ignored) { }
        throw new IOException("Не удалось начать воспроизведение: плеер не перешёл в состояние воспроизведения");
    }

    private void closePlayerLocked() {
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
        if (state == State.PLAYING || state == State.PAUSED) state = State.STOPPED;
    }

    private long positionLocked() {
        if (player == null) return storedPositionMs;
        try { return Math.max(0, player.getCurrentPosition()); } catch (Throwable t) { return storedPositionMs; }
    }

    private void seekLocked(long ms) {
        if (player == null) { storedPositionMs = Math.max(0, ms); return; }
        try {
            long d = player.getDuration();
            if (d > 0) ms = Math.min(ms, d - 50);
            player.seekTo((int) Math.max(0, ms));
            storedPositionMs = Math.max(0, ms);
        } catch (Throwable ignored) { }
    }

    private void applyVolumeLocked() {
        if (player == null) return;
        float eff = volume * (ducked ? DUCK_FACTOR : 1f);
        try { player.setVolume(eff, eff); } catch (Throwable ignored) { }
    }

    private int requestFocusNow() {
        if (audio == null) return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        try {
            if (focusRequest != null) return audio.requestAudioFocus(focusRequest);
            @SuppressWarnings("deprecation")
            int r = audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            return r;
        } catch (Throwable t) {
            return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        }
    }

    private void abandonFocus() {
        if (audio == null) return;
        try {
            if (focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
            else audio.abandonAudioFocus(focusListener);
        } catch (Throwable ignored) { }
    }

    private final AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override public void onAudioFocusChange(int fc) {
            boolean fire = false;
            synchronized (lock) {
                switch (fc) {
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                        if (state == State.PLAYING && player != null) {
                            try { player.pause(); state = State.PAUSED; pausedByFocus = true; fire = true; } catch (Throwable ignored) { }
                        }
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                        if (state == State.PLAYING) { ducked = true; applyVolumeLocked(); fire = true; }
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS:
                        if (state == State.PLAYING || state == State.PAUSED) {
                            gen++;
                            closePlayerLocked();
                            abandonFocus();
                            state = State.STOPPED;
                            storedPositionMs = 0;
                            pausedByFocus = false;
                            ducked = false;
                            fire = true;
                        }
                        break;
                    case AudioManager.AUDIOFOCUS_GAIN:
                        if (ducked) { ducked = false; applyVolumeLocked(); fire = true; }
                        if (pausedByFocus && player != null && state == State.PAUSED) {
                            try { player.start(); state = State.PLAYING; pausedByFocus = false; fire = true; } catch (Throwable ignored) { }
                        }
                        break;
                }
            }
            if (fire) fireChanged();
        }
    };

    private final MediaPlayer.OnCompletionListener onCompletion = new MediaPlayer.OnCompletionListener() {
        @Override public void onCompletion(MediaPlayer mp) {
            final int target;
            synchronized (lock) {
                if (mp != player || released) return;
                durationMs = Math.max(0, mp.getDuration());
                storedPositionMs = durationMs;
                target = index + 1;
                if (target >= queue.size()) {
                    closePlayerLocked();
                    abandonFocus();
                    state = State.STOPPED;
                }
            }
            if (target < queue.size()) startAsync(target);
            else fireChanged();
        }
    };

    private final MediaPlayer.OnErrorListener onError = new MediaPlayer.OnErrorListener() {
        @Override public boolean onError(MediaPlayer mp, int what, int extra) {
            synchronized (lock) {
                if (mp != player) return true;
                closePlayerLocked();
                abandonFocus();
                state = State.ERROR;
                error = "Ошибка воспроизведения (код " + what + "/" + extra + ")";
            }
            fireChanged();
            return true;
        }
    };

    /** Snapshot + main-looper delivery; safe from any thread (synchronized is reentrant). */
    private void fireChanged() {
        final Info i = info();
        final Listener l = listener;
        if (l != null) main.post(new Runnable() {
            @Override public void run() {
                Listener ll = listener;
                if (ll != null) ll.onMusicChanged(i);
            }
        });
    }

    private static float clamp(float v) { return v < 0f ? 0f : v > 1f ? 1f : v; }

    private static String baseName(File f) {
        String n = f.getName();
        int d = n.lastIndexOf('.');
        return d > 0 ? n.substring(0, d) : n;
    }
}
