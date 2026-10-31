package app.jarvis.core.net;

import app.jarvis.core.util.J;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Rolling latency statistics per key (for example "llm:openrouter" = time to the first model token): an EWMA plus the last
 * {@link #KEEP} raw samples, persisted as one small JSON file (written at most every few seconds, atomically).
 * Used to pick the confirmation mode of direct commands and to decide how patient the app is with the primary LLM route.
 */
public final class LatencyBook {
    public static final int KEEP = 8;
    private static final double ALPHA = 0.3;
    private static final long WRITE_EVERY_MS = 4000;

    private static final class Entry {
        double ewma = -1;
        final List<Long> recent = new ArrayList<>();
        int total;
    }

    private final File file;
    private final Map<String, Entry> map = new LinkedHashMap<>();
    private long lastWrite;

    /** file may be null: memory only. */
    public LatencyBook(File file) {
        this.file = file;
        load();
    }

    public synchronized void record(String key, long ms) {
        if (ms < 0) return;
        Entry e = map.get(key);
        if (e == null) { e = new Entry(); map.put(key, e); }
        e.ewma = e.ewma < 0 ? ms : ALPHA * ms + (1 - ALPHA) * e.ewma;
        e.recent.add(ms);
        while (e.recent.size() > KEEP) e.recent.remove(0);
        e.total++;
        persistLocked(false);
    }

    /** EWMA in ms, -1 when nothing was recorded. */
    public synchronized long ewma(String key) {
        Entry e = map.get(key);
        return e == null || e.ewma < 0 ? -1 : Math.round(e.ewma);
    }

    public synchronized int count(String key) { Entry e = map.get(key); return e == null ? 0 : e.recent.size(); }

    /** Median of the last n samples (fewer if fewer exist), -1 when there are none. */
    public synchronized long median(String key, int n) {
        Entry e = map.get(key);
        if (e == null || e.recent.isEmpty()) return -1;
        List<Long> l = new ArrayList<>(e.recent.subList(Math.max(0, e.recent.size() - n), e.recent.size()));
        Collections.sort(l);
        int m = l.size();
        return m % 2 == 1 ? l.get(m / 2) : (l.get(m / 2 - 1) + l.get(m / 2)) / 2;
    }

    /** True when at least {@code minSamples} recent samples exist and their median over the last n is above the limit. */
    public synchronized boolean medianAbove(String key, int n, long limitMs, int minSamples) {
        Entry e = map.get(key);
        if (e == null || e.recent.size() < minSamples) return false;
        return median(key, n) > limitMs;
    }

    public synchronized void flush() { persistLocked(true); }

    private void load() {
        if (file == null || !file.isFile()) return;
        try {
            JSONObject o = J.parseOrNull(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (o == null) return;
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject v = o.optJSONObject(k);
                if (v == null) continue;
                Entry e = new Entry();
                e.ewma = v.optDouble("ewma", -1);
                e.total = v.optInt("n", 0);
                JSONArray a = v.optJSONArray("recent");
                if (a != null) for (int i = 0; i < a.length(); i++) e.recent.add(a.optLong(i));
                while (e.recent.size() > KEEP) e.recent.remove(0);
                map.put(k, e);
            }
        } catch (IOException | RuntimeException ignored) { }
    }

    private void persistLocked(boolean force) {
        if (file == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastWrite < WRITE_EVERY_MS) return;
        lastWrite = now;
        JSONObject o = new JSONObject();
        for (Map.Entry<String, Entry> me : map.entrySet()) {
            JSONObject v = new JSONObject();
            J.put(v, "ewma", me.getValue().ewma);
            J.put(v, "n", me.getValue().total);
            JSONArray a = new JSONArray();
            for (Long l : me.getValue().recent) a.put(l.longValue());
            J.put(v, "recent", a);
            J.put(o, me.getKey(), v);
        }
        try {
            File dir = file.getAbsoluteFile().getParentFile();
            if (dir != null) dir.mkdirs();
            File tmp = new File(dir, file.getName() + ".tmp");
            try (FileOutputStream os = new FileOutputStream(tmp)) { os.write(o.toString().getBytes(StandardCharsets.UTF_8)); }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { }
    }
}
