package app.jarvis.wake;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.vosk.Model;

/**
 * One shared native {@link Model} per model directory, refcounted across consumers ({@link LocalWake} and
 * {@link LocalStt} both need the same ~90 MB of native memory — a second copy would be pure waste on a 3 GB phone).
 *
 * <p>All touches of Vosk classes are guarded here: a missing native library (UnsatisfiedLinkError) or a
 * missing/corrupt model directory becomes {@link #loadError()} — a user-safe Russian message — instead of a crash.
 * When the last consumer releases and nothing re-acquires within {@link #IDLE_MS}, the model is closed on a
 * background task so the RAM returns to the system.</p>
 */
public final class VoskModel {
    static final long IDLE_MS = 120_000L;

    private static final Map<String, VoskModel> INSTANCES = new HashMap<>();
    private static final ScheduledExecutorService IDLER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jv-wake-idle"); t.setDaemon(true); return t;
    });

    private final File dir;
    private final ScheduledFuture<?> idleTask;
    private int refs;                      // guarded by this
    private Model model;                   // guarded by this
    private String error = "";             // guarded by this; Russian, "" when the model loaded or not yet tried

    private VoskModel(File dir) {
        this.dir = dir;
        idleTask = IDLER.scheduleWithFixedDelay(this::idleCheck, IDLE_MS, IDLE_MS, TimeUnit.MILLISECONDS);
    }

    /** Returns the shared instance for a model directory (canonical path keyed). */
    public static VoskModel get(File dir) {
        String key;
        try { key = dir.getCanonicalPath(); } catch (IOException e) { key = dir.getAbsolutePath(); }
        synchronized (INSTANCES) {
            VoskModel m = INSTANCES.get(key);
            if (m == null) { m = new VoskModel(new File(key)); INSTANCES.put(key, m); }
            return m;
        }
    }

    /**
     * Increments the use count and returns the loaded model, loading it on first use.
     *
     * @throws IllegalStateException with a user-safe Russian message when the native library or the model
     *         cannot be loaded (the message is also available via {@link #loadError()})
     */
    public synchronized Model acquire() {
        refs++;
        idleTask.cancel(false);
        if (model != null) return model;
        try {
            model = new Model(dir.getPath());
            error = "";
            return model;
        } catch (LinkageError e) {
            error = "Библиотека офлайн-распознавания Vosk не загрузилась на этом устройстве. Обновите приложение или переустановите пакет модели.";
            release();
            throw new IllegalStateException(error, e);
        } catch (IOException e) {
            error = "Модель офлайн-распознавания не читается. Переустановите её в настройках.";
            release();
            throw new IllegalStateException(error, e);
        } catch (RuntimeException e) {
            error = "Не удалось загрузить модель офлайн-распознавания.";
            release();
            throw new IllegalStateException(error, e);
        }
    }

    /** Decrements the use count; the model closes itself after the idle timeout once the count reaches zero. */
    public synchronized void release() {
        refs = Math.max(0, refs - 1);
    }

    /** Closes the native model now (no-op while somebody still holds a reference). */
    public synchronized void unload() {
        if (refs > 0) return;
        closeLocked();
    }

    /** Last load error (user-safe Russian), or "" when the model is loaded or never tried. */
    public synchronized String loadError() { return error; }

    public synchronized boolean isLoaded() { return model != null; }

    private void idleCheck() {
        synchronized (this) {
            if (refs > 0) return;
            closeLocked();
        }
    }

    private void closeLocked() {
        if (model != null) {
            try { model.close(); } catch (RuntimeException ignored) { }
            model = null;
        }
        error = "";   // a failed load may succeed later (pack reinstalled, libs fixed)
    }
}
