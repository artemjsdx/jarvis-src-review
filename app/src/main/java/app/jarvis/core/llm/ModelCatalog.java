package app.jarvis.core.llm;

import app.jarvis.core.log.Journal;
import app.jarvis.core.util.J;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Model lists and capabilities per provider. Lists are cached in files (one per provider) for 24 hours. Reads never
 * wait for the network: {@link #models} and {@link #caps} answer from memory and start a background load when the
 * cache is old (stale-while-revalidate). After a failure the provider is retried after 10 minutes, and the failure is
 * journaled with its HTTP status. Capabilities of a model are merged across sources, see {@link #caps}.
 */
public final class ModelCatalog {
    /** Loads the model list of one provider; the app implements it with the provider's configuration (and a key when needed). */
    public interface Fetcher { List<ModelInfo> list(String providerId) throws LlmException; }

    public interface Listener { void onCatalogChanged(String providerId); }

    public static final String OPENROUTER = "openrouter";
    public static final long TTL_MS = 24L * 60 * 60 * 1000;
    public static final long RETRY_MS = 10L * 60 * 1000;

    /** Load state of one provider for the UI. */
    public static final class Status {
        public final boolean loading;
        public final long fetchedAt;
        public final String lastError;
        public final int lastHttp;
        public final int count;

        Status(boolean loading, long fetchedAt, String lastError, int lastHttp, int count) {
            this.loading = loading; this.fetchedAt = fetchedAt; this.lastError = lastError; this.lastHttp = lastHttp; this.count = count;
        }
    }

    private static final class Entry {
        List<ModelInfo> models = new ArrayList<>();
        final Map<String, ModelCaps> byId = new HashMap<>();
        /** OpenRouter only: "vendor/model" matched by its part after the slash, so "deepseek-v4-flash" finds "deepseek/deepseek-v4-flash". */
        final Map<String, ModelCaps> bySuffix = new HashMap<>();
        long fetchedAt;
        long nextAttemptAt;
        String lastError = "";
        int lastHttp;
        boolean loading;
        boolean diskLoaded;
    }

    private final File dir;
    private final Fetcher fetcher;
    private final Journal journal;
    private final LongSupplier clock;
    private final Executor pool;
    private final Map<String, Entry> entries = new HashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile ParamMemory params;

    public ModelCatalog(File dir, Fetcher fetcher, Journal journal, LongSupplier clock, Executor pool) {
        this.dir = dir;
        this.fetcher = fetcher;
        this.journal = journal == null ? Journal.NOP : journal;
        this.clock = clock;
        this.pool = pool;
    }

    public void setParamMemory(ParamMemory p) { this.params = p; }

    public void addListener(Listener l) { listeners.addIfAbsent(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    /** Cached models at once; starts a background load when the cache is missing or older than its retry time. */
    public List<ModelInfo> models(String provider) {
        schedule(provider, false);
        synchronized (this) {
            Entry e = entries.get(provider);
            return e == null ? Collections.<ModelInfo>emptyList() : new ArrayList<>(e.models);
        }
    }

    /** Forces a background reload now (the "Обновить список" button). */
    public void refresh(String provider) { schedule(provider, true); }

    public synchronized Status status(String provider) {
        Entry e = entries.get(provider);
        if (e == null) return new Status(false, 0, "", 0, 0);
        return new Status(e.loading, e.fetchedAt, e.lastError, e.lastHttp, e.models.size());
    }

    /**
     * Capabilities of one model, merged in this order: the provider's own entry, the OpenRouter catalog (matched by id or by
     * the part after "vendor/"), the builtin table, then what the provider refused in earlier requests (ParamMemory).
     * Unknown fields stay unknown. Never performs network I/O.
     */
    public ModelCaps caps(String provider, String id) {
        if (!OPENROUTER.equals(provider)) schedule(OPENROUTER, false);
        ModelCaps own, fromRouter;
        synchronized (this) {
            Entry e = entries.get(provider);
            own = e == null ? null : e.byId.get(id);
            Entry r = OPENROUTER.equals(provider) ? null : entries.get(OPENROUTER);
            fromRouter = r == null ? null : matchRouter(r, id);
        }
        ModelCaps merged = (own == null ? ModelCaps.unknown(id) : own.copy()).fill(fromRouter).fill(BuiltinModels.lookup(provider, id));
        ParamMemory p = params;
        if (p != null) {
            for (String param : p.rejected(provider, id)) {
                merged.rejectedParams.add(param);
                if (param.equals("temperature")) merged.temperature = Cap.NO;
                if (param.equals("reasoning") || param.equals("reasoning_effort") || param.equals("thinking") || param.equals("enable_thinking"))
                    merged.reasoning = Cap.NO;
                merged.source = ModelCaps.Source.LEARNED;
            }
        }
        return merged;
    }

    /** Replaces the list of a provider without the network (used by the app to show a stored snapshot and by tests). */
    public void seed(String provider, List<ModelInfo> models) {
        long now = clock.getAsLong();
        store(provider, models, now);
        writeDisk(provider, now);
        notifyChanged(provider);
    }

    private void schedule(final String provider, boolean force) {
        long now = clock.getAsLong();
        synchronized (this) {
            Entry e = entry(provider);
            if (e.loading) return;
            if (!force && e.diskLoaded && now < e.nextAttemptAt) return;
            e.loading = true;
        }
        pool.execute(new Runnable() { @Override public void run() { load(provider, force); } });
    }

    /** Runs on the pool thread. Public so that tests can run it synchronously. */
    public void load(String provider, boolean force) {
        Entry e;
        synchronized (this) { e = entry(provider); e.loading = true; }
        boolean changed = false;
        try {
            if (!e.diskLoaded) { readDisk(provider); changed = true; }
            long now = clock.getAsLong();
            boolean due;
            synchronized (this) { due = force || now >= e.nextAttemptAt; }
            if (!due) return;
            changed = true;
            try {
                List<ModelInfo> ms = fetcher.list(provider);
                long t = clock.getAsLong();
                store(provider, ms, t);
                writeDisk(provider, t);
                journal.log(Journal.Cat.LLM, Journal.Level.INFO, "Список моделей загружен: " + provider + ", моделей " + ms.size(), null, null, -1);
            } catch (LlmException ex) {
                recordError(provider, ex);
            } catch (RuntimeException ex) {
                recordError(provider, new LlmException(LlmException.Kind.PROTOCOL, 0, "Не удалось разобрать список моделей",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(), 0, null, ex));
            }
        } finally {
            synchronized (this) { e.loading = false; }
            if (changed) notifyChanged(provider);
        }
    }

    private synchronized Entry entry(String provider) {
        Entry e = entries.get(provider);
        if (e == null) { e = new Entry(); entries.put(provider, e); }
        return e;
    }

    private void store(String provider, List<ModelInfo> ms, long now) {
        List<ModelInfo> out = new ArrayList<>();
        boolean router = OPENROUTER.equals(provider);
        for (ModelInfo m : ms) {
            ModelCaps c = m.caps.copy();
            c.fetchedAt = now;
            if (router && c.hasData()) c.source = ModelCaps.Source.OPENROUTER;
            out.add(new ModelInfo(m.id, m.name, m.ownedBy, c));
        }
        synchronized (this) {
            Entry e = entry(provider);
            e.models = out;
            e.byId.clear();
            e.bySuffix.clear();
            for (ModelInfo m : out) {
                e.byId.put(m.id, m.caps);
                int slash = m.id.indexOf('/');
                if (slash >= 0 && slash < m.id.length() - 1) e.bySuffix.putIfAbsent(m.id.substring(slash + 1).toLowerCase(java.util.Locale.ROOT), m.caps);
            }
            e.fetchedAt = now;
            e.nextAttemptAt = now + TTL_MS;
            e.lastError = "";
            e.lastHttp = 0;
            e.diskLoaded = true;
        }
    }

    private void recordError(String provider, LlmException ex) {
        long now = clock.getAsLong();
        synchronized (this) {
            Entry e = entry(provider);
            e.lastError = ex.userMessage;
            e.lastHttp = ex.status;
            e.nextAttemptAt = now + RETRY_MS;
        }
        JSONObject d = new JSONObject();
        J.put(d, "provider", provider);
        J.put(d, "http", ex.status);
        J.put(d, "technical", ex.technical);
        journal.log(Journal.Cat.LLM, Journal.Level.ERROR, "Не удалось загрузить список моделей (" + provider + "): " + ex.userMessage
            + (ex.status > 0 ? " [HTTP " + ex.status + "]" : ""), null, d, -1);
    }

    private static ModelCaps matchRouter(Entry r, String id) {
        ModelCaps c = r.byId.get(id);
        if (c != null) return c;
        if (id.indexOf('/') >= 0) return null;
        return r.bySuffix.get(id.toLowerCase(java.util.Locale.ROOT));
    }

    private void notifyChanged(String provider) {
        for (Listener l : listeners) {
            try { l.onCatalogChanged(provider); } catch (RuntimeException ignored) { }
        }
    }

    private File file(String provider) { return new File(dir, provider.replaceAll("[^A-Za-z0-9._-]", "_") + ".json"); }

    private void readDisk(String provider) {
        File f = file(provider);
        List<ModelInfo> ms = new ArrayList<>();
        long fetchedAt = 0;
        if (f.isFile()) {
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] b = new byte[(int) f.length()];
                int n = 0;
                while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
                JSONObject o = new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8));
                fetchedAt = o.optLong("fetchedAt", 0);
                JSONArray arr = o.optJSONArray("models");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.optJSONObject(i);
                    if (m == null || m.optString("id", "").isEmpty()) continue;
                    JSONObject cj = m.optJSONObject("caps");
                    ModelCaps c = cj == null ? ModelCaps.unknown(m.optString("id")) : ModelCaps.fromJson(cj);
                    ms.add(new ModelInfo(m.optString("id"), m.optString("name", ""), m.optString("owned", ""), c));
                }
            } catch (Exception e) {
                journal.log(Journal.Cat.LLM, Journal.Level.WARN, "Кэш списка моделей повреждён, загружу заново: " + provider, null, null, -1);
                ms.clear();
                fetchedAt = 0;
            }
        }
        synchronized (this) {
            Entry e = entry(provider);
            if (!ms.isEmpty()) {
                e.models = ms;
                e.byId.clear();
                e.bySuffix.clear();
                for (ModelInfo m : ms) {
                    e.byId.put(m.id, m.caps);
                    int slash = m.id.indexOf('/');
                    if (slash >= 0 && slash < m.id.length() - 1) e.bySuffix.putIfAbsent(m.id.substring(slash + 1).toLowerCase(java.util.Locale.ROOT), m.caps);
                }
            }
            e.fetchedAt = fetchedAt;
            e.nextAttemptAt = fetchedAt > 0 ? fetchedAt + TTL_MS : 0;
            e.diskLoaded = true;
        }
    }

    private void writeDisk(String provider, long fetchedAt) {
        try {
            List<ModelInfo> ms;
            synchronized (this) { ms = new ArrayList<>(entry(provider).models); }
            JSONObject o = new JSONObject();
            o.put("fetchedAt", fetchedAt);
            JSONArray arr = new JSONArray();
            for (ModelInfo m : ms) {
                JSONObject j = new JSONObject();
                j.put("id", m.id);
                j.put("name", m.name);
                j.put("owned", m.ownedBy);
                j.put("caps", m.caps.toJson());
                arr.put(j);
            }
            o.put("models", arr);
            dir.mkdirs();
            File f = file(provider);
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(o.toString().getBytes(StandardCharsets.UTF_8)); }
            if (f.exists()) f.delete();
            if (!tmp.renameTo(f)) throw new java.io.IOException("rename failed");
        } catch (Exception e) {
            journal.log(Journal.Cat.LLM, Journal.Level.WARN, "Не удалось сохранить кэш списка моделей: " + provider + " (" + e.getClass().getSimpleName() + ")", null, null, -1);
        }
    }
}
