package app.jarvis.audio;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.SoundPool;
import android.app.NotificationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.os.VibrationEffect;
import app.jarvis.core.audio.CueManifest;
import app.jarvis.core.audio.CuePolicy;
import app.jarvis.core.audio.CuePolicy.Kind;
import app.jarvis.core.audio.CuePackCatalog;
import app.jarvis.core.audio.CueSettings;
import app.jarvis.core.log.Journal;
import app.jarvis.data.Settings;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Listening cues: start (the microphone is open for a command), stop (the command was taken), error (nothing usable was
 * heard). Built-in sets live in assets/sounds; the user's own start/stop files live in {@link CueImport#dir}. Playback
 * goes through a SoundPool on the media volume stream, so the user can hear preview and runtime cues. play* returns the
 * clip length in milliseconds (0 when silent) so the caller can keep the microphone deaf for exactly that long.
 */
public final class Cues {
    private static final String ASSET_DIR = "sounds/";
    private static final String MANIFEST = ASSET_DIR + "sounds.json";
    private static final String JOURNAL_CAT = Journal.Cat.WAKE;

    private final Context ctx;
    private final Settings settings;
    private final Journal journal;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private SoundPool pool;
    private List<CueManifest.Set> manifest;
    private final Map<String, Integer> sampleByKey = new HashMap<>();
    private final Map<Integer, AssetFileDescriptor> pendingAssets = new HashMap<>();
    private final java.util.Set<Integer> ready = new java.util.HashSet<>();
    private final Map<Integer, Float> playWhenReady = new HashMap<>();

    public Cues(Context ctx, Settings settings, Journal journal) {
        this.ctx = ctx;
        this.settings = settings;
        this.journal = journal;
    }

    /** The start cue. speaking: the assistant is talking now (the start cue is then the barge-in and still plays). */
    public long playStart(boolean speaking) { return play(Kind.START, speaking); }

    public long playStop() { return play(Kind.STOP, false); }

    public long playError() { return play(Kind.ERROR, false); }

    /** Loads the selected set in advance so the first cue after start is not skipped as "still loading". */
    public void warmUp() {
        // Small built-in OGG clips are inexpensive. Load all of them so choosing a different
        // start/stop sound does not silently drop the first real microphone cue.
        for (CueManifest.Set set : manifest()) {
            for (Kind k : Kind.values()) {
                Clip clip = setClip(set.id, k, snapshot().volume);
                if (clip != null) sampleFor(clip);
            }
        }
        CueSettings selected = snapshot();
        for (Kind k : Kind.values()) {
            Clip clip = clipFor(selected, k);
            if (clip != null) sampleFor(clip);
        }
    }

    /** Built-in sets, then the pack sets whose pack is installed: what the settings pickers offer. */
    public List<CueManifest.Set> sets() {
        List<CueManifest.Set> out = new ArrayList<>(manifest());
        if (CuePackCatalog.installed(CueImport.packDir(ctx))) out.addAll(CuePackCatalog.sets());
        return out;
    }

    /** The settings as they are now, read fresh. */
    public CueSettings current() { return snapshot(); }

    /** A user tap on one clip of a set in settings. Never called automatically. Plays once the clip has loaded. */
    public void previewSet(String setId, Kind kind) {
        Clip c = setClip(setId, kind, snapshot().volume);
        if (c != null) playClip(c, true);
    }

    /** A user tap on the play button of the own file chosen for a slot (name as stored in cue.custom.*). */
    public void previewFile(String name) {
        File f = new File(CueImport.dir(ctx), name);
        if (!f.isFile()) return;
        playClip(new Clip("custom:" + name + ":" + f.lastModified(), f.getPath(), null, customLength(f), snapshot().volume), true);
    }

    /** Frees the sound pool. Only for an instance that is no longer used (the settings page); the voice module keeps its own. */
    public void release() {
        synchronized (lock) {
            if (pool != null) pool.release();
            pool = null;
            sampleByKey.clear();
            ready.clear();
            playWhenReady.clear();
            for (AssetFileDescriptor afd : pendingAssets.values()) closeQuietly(afd);
            pendingAssets.clear();
        }
    }

    private long play(Kind kind, boolean speaking) {
        CueSettings s = snapshot();
        boolean inCall = inCall();
        CuePolicy.Decision d = CuePolicy.decide(kind, s, new CuePolicy.Env(soundAllowed(), inCall, speaking));
        if (s.haptic && !inCall && kind != Kind.ERROR) buzz(kind);
        if (!d.play) {
            journal.d(JOURNAL_CAT, "Сигнал «" + title(kind) + "» пропущен: " + d.reason);
            return 0;
        }
        Clip clip = clipFor(s, kind);
        if (clip == null) {
            journal.d(JOURNAL_CAT, "Сигнал «" + title(kind) + "»: нет файла в наборе " + s.set);
            return 0;
        }
        long ms = playClip(clip, false);
        journal.d(JOURNAL_CAT, ms > 0 ? "Сигнал «" + title(kind) + "» (" + clip.key + ", " + ms + " мс)"
            : "Сигнал «" + title(kind) + "» ещё загружается, пропущен");
        return ms;
    }

    /** @param whenReady a preview: an unloaded clip plays as soon as it has loaded instead of being skipped */
    private int playClip(Clip clip, boolean whenReady) {
        Integer id = sampleFor(clip);
        if (id == null) return 0;
        synchronized (lock) {
            ensurePool();
            if (!ready.contains(id)) {
                if (whenReady) playWhenReady.put(id, clip.volume);
                return 0;
            }
            int stream = pool.play(id, clip.volume, clip.volume, 1, 0, 1f);
            return stream == 0 ? 0 : clip.durationMs;
        }
    }

    /** The sample id for a clip, loading it on first use. Null when the file cannot be read. */
    private Integer sampleFor(Clip clip) {
        synchronized (lock) {
            ensurePool();
            Integer have = sampleByKey.get(clip.key);
            if (have != null) return have;
            int id;
            try {
                if (clip.path != null) {
                    id = pool.load(clip.path, 1);
                } else {
                    AssetFileDescriptor afd = ctx.getAssets().openFd(clip.assetPath);
                    id = pool.load(afd, 1);
                    pendingAssets.put(id, afd);
                }
            } catch (IOException | RuntimeException e) {
                journal.w(JOURNAL_CAT, "Сигнал не загрузился (" + clip.key + "): " + e.getClass().getSimpleName(), e, null);
                return null;
            }
            sampleByKey.put(clip.key, id);
            return id;
        }
    }

    private void ensurePool() {
        if (pool != null) return;
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
        pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build();
        pool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override public void onLoadComplete(SoundPool sp, int sampleId, int status) {
                synchronized (lock) {
                    if (status == 0) ready.add(sampleId);
                    else journal.w(JOURNAL_CAT, "SoundPool не смог декодировать сигнал: status=" + status, null, null);
                    Float volume = playWhenReady.remove(sampleId);
                    if (volume != null && status == 0) sp.play(sampleId, volume, volume, 1, 0, 1f);
                    AssetFileDescriptor afd = pendingAssets.remove(sampleId);
                    if (afd != null) closeQuietly(afd);
                }
            }
        });
    }

    /** The clip for this kind: the user's own file when set and present, else the selected set. */
    private Clip clipFor(CueSettings s, Kind kind) {
        String custom = kind == Kind.START ? s.customStart : kind == Kind.STOP ? s.customStop : "";
        if (!custom.isEmpty()) {
            File f = new File(CueImport.dir(ctx), custom);
            if (f.isFile()) return new Clip("custom:" + custom + ":" + f.lastModified(), f.getPath(), null, customLength(f), s.volume);
        }
        return setClip(kind == Kind.START ? s.startSet : kind == Kind.STOP ? s.stopSet : s.set, kind, s.volume);
    }

    /** A built-in set, else an installed pack set, else the default set: an unknown or missing id still sounds. */
    private Clip setClip(String setId, Kind kind, float volume) {
        CueManifest.Set builtIn = setById(setId);
        if (builtIn != null) return new Clip("set:" + builtIn.id + ":" + kind, null, ASSET_DIR + builtIn.fileName(kind), builtIn.durationMs(kind), volume);
        CueManifest.Set pack = CuePackCatalog.setById(setId);
        File packDir = CueImport.packDir(ctx);
        if (pack != null && CuePackCatalog.installed(packDir)) {
            return new Clip("pack:" + pack.id + ":" + kind, new File(packDir, pack.fileName(kind)).getPath(), null, pack.durationMs(kind), volume);
        }
        CueManifest.Set fallback = CueManifest.find(manifest(), setId);
        if (fallback == null) return null;
        return new Clip("set:" + fallback.id + ":" + kind, null, ASSET_DIR + fallback.fileName(kind), fallback.durationMs(kind), volume);
    }

    private int customLength(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getPath());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? 300 : (int) Math.min(3000, Long.parseLong(d));
        } catch (RuntimeException e) {
            return 300;
        } finally {
            try { r.release(); } catch (IOException ignored) { }
        }
    }

    private List<CueManifest.Set> manifest() {
        if (manifest != null) return manifest;
        try (InputStream in = ctx.getAssets().open(MANIFEST)) {
            manifest = CueManifest.parse(readAll(in));
        } catch (Exception e) {
            journal.e(JOURNAL_CAT, "Список наборов сигналов не прочитан", e);
            manifest = new ArrayList<>();
        }
        return manifest;
    }

    private CueManifest.Set setById(String id) {
        for (CueManifest.Set s : manifest()) if (s.id.equals(id)) return s;
        return null;
    }

    private CueSettings snapshot() {
        return CueSettings.resolve(new CueSettings.Source() {
            @Override public String get(String key) { return settings.get(key, null); }
        });
    }

    private boolean soundAllowed() {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return false;
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm == null || nm.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_ALL;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private boolean inCall() {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;
            int mode = am.getMode();
            return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The short "I hear you" tick: one pulse when listening starts, a double tick when it ends. */
    private void buzz(Kind kind) {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (kind == Kind.START) v.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(VibrationEffect.createWaveform(new long[]{0, 14, 70, 14}, -1));
        } catch (RuntimeException ignored) { }
    }

    private static String title(Kind kind) {
        switch (kind) {
            case START: return "начало";
            case STOP: return "конец";
            default: return "ошибка";
        }
    }

    private static void closeQuietly(AssetFileDescriptor afd) {
        try { afd.close(); } catch (IOException ignored) { }
    }

    private static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** One loadable sound: an asset (assetPath) or a file (path), with its length and output volume. */
    private static final class Clip {
        final String key, path, assetPath;
        final int durationMs;
        final float volume;

        Clip(String key, String path, String assetPath, int durationMs, float volume) {
            this.key = key;
            this.path = path;
            this.assetPath = assetPath;
            this.durationMs = durationMs;
            this.volume = volume;
        }
    }
}
