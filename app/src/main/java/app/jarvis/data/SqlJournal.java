package app.jarvis.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import app.jarvis.BuildConfig;
import app.jarvis.core.log.Journal;
import app.jarvis.core.util.Redactor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Persistent structured journal. Writes are queued and batched on a background thread (never blocks the caller),
 * everything is redacted before it is stored, the table is trimmed to a maximum row count, and live listeners get new
 * rows on the main thread. Summary rows are keyed: each update rewrites the newest row with that key in place.
 */
public final class SqlJournal implements Journal {
    private static final String SITE_KEY = "site";

    public static final class Row {
        public long id, ts, durationMs;
        public String category, message, runId, details;
        public Level level;
        private boolean upsert;
        private String site;
        private boolean siteLoaded;

        public String time() { return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(ts)); }

        /** "File.java:123 in method" for ERROR rows that carry a site, else null. */
        public String site() {
            if (!siteLoaded) {
                siteLoaded = true;
                site = level == Level.ERROR ? siteOf(details) : null;
            }
            return site;
        }
    }

    public interface LiveListener { void onRows(List<Row> rows); }

    private final Db db;
    private final LinkedBlockingQueue<Row> queue = new LinkedBlockingQueue<>(5000);
    private final CopyOnWriteArrayList<LiveListener> live = new CopyOnWriteArrayList<>();
    private final Handler main;
    private volatile int maxRows = 20000;
    private volatile Level minLevel = Level.DEBUG;
    private volatile boolean closed;

    public SqlJournal(Db db) {
        this.db = db;
        Handler h = null;
        try { h = new Handler(Looper.getMainLooper()); } catch (Throwable ignored) { }
        main = h;
        Thread t = new Thread(new Runnable() { @Override public void run() { pump(); } }, "jv-journal");
        t.setDaemon(true);
        t.start();
    }

    public void setMaxRows(int n) { maxRows = Math.max(1000, n); }
    public void setMinLevel(Level l) { minLevel = l; }
    public void addLiveListener(LiveListener l) { live.addIfAbsent(l); }
    public void removeLiveListener(LiveListener l) { live.remove(l); }

    @Override public void log(String category, Level level, String message, String runId, JSONObject details, long durationMs) {
        enqueue(category, level, message, runId, details, durationMs, false);
    }

    @Override public void summary(String category, String key, String message) {
        enqueue(category, Level.INFO, message, key, null, -1, true);
    }

    private void enqueue(String category, Level level, String message, String runId, JSONObject details, long durationMs, boolean upsert) {
        if (level.ordinal() < minLevel.ordinal()) return;
        try {
            Row r = new Row();
            r.ts = System.currentTimeMillis();
            r.category = category == null ? Cat.SYSTEM : category;
            r.level = level;
            r.message = Redactor.redact(message == null ? "" : message);
            r.runId = runId;
            r.upsert = upsert && runId != null;
            r.durationMs = durationMs;
            if (level == Level.ERROR) details = withEnvironment(details);
            r.details = details == null ? "" : Redactor.redact(details.toString());
            if (r.message.length() > 4000) r.message = r.message.substring(0, 4000) + "…";
            if (r.details.length() > 16000) r.details = r.details.substring(0, 16000) + "…";
            queue.offer(r);
        } catch (Throwable ignored) { /* the journal must never throw */ }
    }

    /**
     * Writes one row synchronously on the calling thread. Only for the crash handler: the background writer may never
     * get to run before the process dies.
     */
    public void logNow(String category, Level level, String message, JSONObject details) {
        try {
            Row r = new Row();
            r.ts = System.currentTimeMillis();
            r.category = category == null ? Cat.SYSTEM : category;
            r.level = level;
            r.message = Redactor.redact(message == null ? "" : message);
            r.details = details == null ? "" : Redactor.redact(withEnvironment(details).toString());
            db.sql.insert("log", null, values(r));
        } catch (Throwable ignored) { /* the crash handler must not throw */ }
    }

    private void pump() {
        int sinceTrim = 0;
        while (!closed) {
            try {
                Row first = queue.take();
                List<Row> batch = new ArrayList<>();
                batch.add(first);
                Thread.sleep(60);
                queue.drainTo(batch, 200);
                db.sql.beginTransaction();
                try {
                    for (Row r : batch) r.id = r.upsert ? upsertSummary(r) : db.sql.insert("log", null, values(r));
                    db.sql.setTransactionSuccessful();
                } finally { db.sql.endTransaction(); }
                notifyLive(batch);
                sinceTrim += batch.size();
                if (sinceTrim > 500) { sinceTrim = 0; trim(); }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
        }
    }

    private long upsertSummary(Row r) {
        long existing = -1;
        try (Cursor c = db.sql.rawQuery("SELECT id FROM log WHERE cat=? AND run_id=? ORDER BY id DESC LIMIT 1",
            new String[]{r.category, r.runId})) {
            if (c.moveToFirst()) existing = c.getLong(0);
        }
        if (existing < 0) return db.sql.insert("log", null, values(r));
        db.sql.update("log", values(r), "id=?", new String[]{String.valueOf(existing)});
        return existing;
    }

    private static ContentValues values(Row r) {
        ContentValues v = new ContentValues();
        v.put("ts", r.ts); v.put("cat", r.category); v.put("level", r.level.ordinal()); v.put("msg", r.message);
        v.put("run_id", r.runId); v.put("details", r.details); v.put("dur", r.durationMs);
        return v;
    }

    /** Where and on what the error happened: app build, Android, device, free heap. Added to every ERROR row. */
    private static JSONObject withEnvironment(JSONObject details) {
        JSONObject d = details == null ? new JSONObject() : details;
        try {
            d.put("app", BuildConfig.VERSION_NAME + " " + BuildConfig.GIT_REV);
            d.put("android", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
            d.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            Runtime rt = Runtime.getRuntime();
            long freeMb = (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) >> 20;
            d.put("free_mb", freeMb);
        } catch (JSONException ignored) { /* plain values: cannot fail */ }
        return d;
    }

    static String siteOf(String detailsJson) {
        if (detailsJson == null || !detailsJson.startsWith("{")) return null;
        try {
            String s = new JSONObject(detailsJson).optString(SITE_KEY, "");
            return s.isEmpty() ? null : s;
        } catch (JSONException e) {
            return null;
        }
    }

    private void notifyLive(final List<Row> rows) {
        if (live.isEmpty()) return;
        Runnable r = new Runnable() { @Override public void run() { for (LiveListener l : live) { try { l.onRows(rows); } catch (Throwable ignored) { } } } };
        if (main != null) main.post(r); else r.run();
    }

    private void trim() {
        long n = count();
        if (n > maxRows) db.sql.execSQL("DELETE FROM log WHERE id <= (SELECT id FROM log ORDER BY id DESC LIMIT 1 OFFSET ?)", new Object[]{(long) (maxRows * 0.9)});
    }

    public long count() {
        try (Cursor c = db.sql.rawQuery("SELECT COUNT(*) FROM log", null)) { return c.moveToFirst() ? c.getLong(0) : 0; }
    }

    /** Newest first. category null/"" = all; minLevel null = all; text = substring filter. beforeId 0 = from the newest. */
    public List<Row> query(String category, Level min, String text, long beforeId, int limit) {
        StringBuilder w = new StringBuilder("1=1");
        List<String> args = new ArrayList<>();
        if (category != null && !category.isEmpty()) { w.append(" AND cat=?"); args.add(category); }
        if (min != null) { w.append(" AND level>=").append(min.ordinal()); }
        if (text != null && !text.isEmpty()) { w.append(" AND (msg LIKE ? OR details LIKE ?)"); args.add("%" + text + "%"); args.add("%" + text + "%"); }
        if (beforeId > 0) { w.append(" AND id<").append(beforeId); }
        List<Row> out = new ArrayList<>();
        try (Cursor c = db.sql.rawQuery("SELECT id,ts,cat,level,msg,run_id,details,dur FROM log WHERE " + w + " ORDER BY id DESC LIMIT " + Math.max(1, limit),
            args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                Row r = new Row();
                r.id = c.getLong(0); r.ts = c.getLong(1); r.category = c.getString(2);
                r.level = Level.values()[Math.max(0, Math.min(3, c.getInt(3)))];
                r.message = c.getString(4); r.runId = c.getString(5); r.details = c.getString(6) == null ? "" : c.getString(6); r.durationMs = c.getLong(7);
                out.add(r);
            }
        }
        return out;
    }

    public static String format(Row r) {
        return r.time() + " " + r.level.name().charAt(0) + "/" + r.category + (r.runId != null ? " [" + r.runId + "]" : "") + " " + r.message
            + (r.durationMs >= 0 ? " (" + r.durationMs + " мс)" : "") + (r.details.isEmpty() ? "" : "\n    " + r.details);
    }

    /** Writes the (filtered) journal as text, oldest first. Returns the number of rows. */
    public int export(File out, String category, Level min, int maxRowsToExport) throws IOException {
        List<Row> rows = query(category, min, null, 0, maxRowsToExport);
        try (FileOutputStream os = new FileOutputStream(out)) {
            for (int i = rows.size() - 1; i >= 0; i--) os.write((format(rows.get(i)) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return rows.size();
    }

    public void clear() { try { db.sql.execSQL("DELETE FROM log"); } catch (Throwable ignored) { } }

    public void close() { closed = true; }
}
