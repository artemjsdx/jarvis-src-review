package app.jarvis.core.tools;

import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ToolSpec;
import app.jarvis.core.llm.ToolSpec.Param;
import app.jarvis.core.util.J;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.IntSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Facts the assistant remembers about the user and the environment. Visible and deletable by the user; stored as one
 * atomic JSON file. The text injected into every prompt is bounded by a character budget: a save that would exceed it is
 * refused (never silently dropped). Style notes ({@link #STYLE}) are injected on their own line because they are the
 * user's instructions about how to answer; everything else is a declarative fact.
 */
public final class MemoryStore {
    public static final String FACT = "fact", PREFERENCE = "preference", STYLE = "style";
    public static final int MAX_ENTRIES = 500, MAX_VALUE = 2000, MAX_KEY = 80, DEFAULT_BUDGET = 2000;
    private static final int FORMAT_VERSION = 2;
    private static final String[] STYLE_HINTS = {"ответ", "стил", "точек", "точки", "короч", "длинн", "эмодзи", "смайл", "списк", "формат",
        "markdown", "маркдаун", "вежлив"};

    public static final class Entry {
        public final String key, value, kind;
        public final long updated;
        public final boolean pinned;
        Entry(String k, String v, long u, String kind, boolean pinned) {
            key = k; value = v; updated = u; this.kind = kind; this.pinned = pinned;
        }
    }

    /** The two prompt sections: facts for «ПАМЯТЬ ПОЛЬЗОВАТЕЛЯ», style notes for «СТИЛЬ ОТВЕТА ПОЛЬЗОВАТЕЛЯ». */
    public static final class PromptParts {
        public final String facts, style;
        PromptParts(String facts, String style) { this.facts = facts; this.style = style; }
    }

    /** Raised when a write would exceed the budget; carries the numbers for the tool result. */
    public static final class FullException extends IOException {
        public final int used, limit;
        FullException(int used, int limit) {
            super("память заполнена: объедините или удалите записи");
            this.used = used;
            this.limit = limit;
        }
    }

    private final File file;
    private final LinkedHashMap<String, Entry> map = new LinkedHashMap<>();
    private volatile Runnable onChange;
    private IntSupplier budget = new IntSupplier() { @Override public int getAsInt() { return DEFAULT_BUDGET; } };

    public MemoryStore(File file) {
        this.file = file;
        load();
    }

    public void setOnChange(Runnable r) { onChange = r; }

    /** Read on every write, so a changed setting applies immediately. */
    public void setBudget(IntSupplier source) { budget = source; }

    public int budget() { return budget.getAsInt(); }

    private void load() {
        if (!file.isFile()) return;
        boolean migrated = false;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] b = new byte[(int) file.length()];
            int off = 0;
            while (off < b.length) { int n = in.read(b, off, b.length - off); if (n < 0) break; off += n; }
            JSONObject root = J.parseOrNull(new String(b, 0, off, StandardCharsets.UTF_8));
            JSONArray a = root == null ? null : J.arr(root, "entries");
            migrated = root != null && root.optInt("version", 1) < FORMAT_VERSION;
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e == null || J.str(e, "key").isEmpty()) continue;
                String key = J.str(e, "key"), value = J.str(e, "value");
                String kind = J.str(e, "kind");
                if (kind.isEmpty()) kind = inferKind(key, value);
                else if (!FACT.equals(kind) && !PREFERENCE.equals(kind) && !STYLE.equals(kind)) kind = FACT;
                map.put(key, new Entry(key, value, J.lng(e, "updated", 0), kind, J.bool(e, "pinned", false)));
            }
        } catch (IOException ignored) { }
        if (migrated) {
            try { save(); } catch (IOException ignored) { }
        }
    }

    /** Entries written before kinds existed: a response-style note is recognised by its words, anything else is a fact. */
    static String inferKind(String key, String value) {
        String s = (key + " " + value).toLowerCase(Locale.ROOT);
        for (String h : STYLE_HINTS) if (s.contains(h)) return STYLE;
        return FACT;
    }

    private void save() throws IOException {
        JSONArray a = new JSONArray();
        for (Entry e : map.values()) {
            JSONObject o = new JSONObject();
            J.put(o, "key", e.key); J.put(o, "value", e.value); J.put(o, "updated", e.updated);
            J.put(o, "kind", e.kind); J.put(o, "pinned", e.pinned);
            a.put(o);
        }
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(parent, file.getName() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(J.put(J.put(new JSONObject(), "version", FORMAT_VERSION), "entries", a).toString().getBytes(StandardCharsets.UTF_8));
            os.getFD().sync();
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Characters the entry takes in the prompt: "- key: value\n". */
    private static int cost(Entry e) { return e.key.length() + e.value.length() + 5; }

    /** Characters used by all entries, compared with {@link #budget()}. */
    public synchronized int used() {
        int n = 0;
        for (Entry e : map.values()) n += cost(e);
        return n;
    }

    /** Creates or updates a record. A saved value keeps the record's pin and kind; a new record's kind is inferred from its text. */
    public synchronized void put(String key, String value) throws IOException { put(key, value, null); }

    public synchronized void put(String key, String value, String kind) throws IOException {
        key = key.trim();
        if (key.isEmpty() || key.length() > MAX_KEY) throw new IOException("Ключ должен быть от 1 до " + MAX_KEY + " символов");
        if (value.length() > MAX_VALUE) throw new IOException("Значение длиннее " + MAX_VALUE + " символов");
        if (kind != null && !FACT.equals(kind) && !PREFERENCE.equals(kind) && !STYLE.equals(kind)) throw new IOException("Вид записи: fact, preference или style");
        Entry old = map.get(key);
        if (old == null && map.size() >= MAX_ENTRIES) throw new IOException("Память заполнена (" + MAX_ENTRIES + " записей)");
        Entry e = new Entry(key, value, System.currentTimeMillis(), kind != null ? kind : old != null ? old.kind : inferKind(key, value), old != null && old.pinned);
        int after = used() - (old == null ? 0 : cost(old)) + cost(e);
        if (after > budget()) throw new FullException(used(), budget());
        map.put(key, e);
        save();
        fire();
    }

    /** Updates an existing record (its kind and pin are kept). Fails for an unknown key: a new fact goes through put. */
    public synchronized void replace(String key, String value) throws IOException {
        Entry old = map.get(key.trim());
        if (old == null) throw new IOException("Нет записи «" + key.trim() + "»: для новой записи используйте memory_save");
        put(old.key, value, old.kind);
    }

    public synchronized void pin(String key, boolean on) throws IOException {
        Entry e = map.get(key.trim());
        if (e == null) throw new IOException("Нет записи «" + key.trim() + "»");
        map.put(e.key, new Entry(e.key, e.value, e.updated, e.kind, on));
        save();
        fire();
    }

    public synchronized boolean delete(String key) throws IOException {
        boolean had = map.remove(key.trim()) != null;
        if (had) { save(); fire(); }
        return had;
    }

    public synchronized void clear() throws IOException { map.clear(); save(); fire(); }
    public synchronized String get(String key) { Entry e = map.get(key.trim()); return e == null ? null : e.value; }
    public synchronized Entry entry(String key) { return map.get(key.trim()); }
    public synchronized List<Entry> all() { return new ArrayList<>(map.values()); }
    public synchronized int size() { return map.size(); }

    public synchronized List<Entry> search(String q) {
        String s = q.toLowerCase(Locale.ROOT).trim();
        List<Entry> out = new ArrayList<>();
        for (Entry e : map.values()) if (e.key.toLowerCase(Locale.ROOT).contains(s) || e.value.toLowerCase(Locale.ROOT).contains(s)) out.add(e);
        return out;
    }

    /**
     * The prompt text, at most {@code maxChars} in total across both sections. Pinned entries come first, then the most
     * recent; an entry that does not fit is skipped and the next one is tried.
     */
    public synchronized PromptParts promptParts(int maxChars) {
        List<Entry> l = new ArrayList<>(map.values());
        Collections.sort(l, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
                return Long.compare(b.updated, a.updated);
            }
        });
        StringBuilder facts = new StringBuilder(), style = new StringBuilder();
        int room = maxChars;
        for (Entry e : l) {
            String line = "- " + e.key + ": " + e.value + "\n";
            if (line.length() > room) continue;
            room -= line.length();
            (STYLE.equals(e.kind) ? style : facts).append(line);
        }
        return new PromptParts(facts.toString().trim(), style.toString().trim());
    }

    private void fire() { Runnable r = onChange; if (r != null) r.run(); }

    private static JSONObject view(Entry e) {
        return J.put(J.put(J.put(J.put(new JSONObject(), "key", e.key), "value", e.value), "kind", e.kind), "pinned", e.pinned);
    }

    private static JSONObject usage(FullException e) {
        return J.put(new JSONObject(), "usage", J.put(J.put(new JSONObject(), "used", e.used), "limit", e.limit));
    }

    public static void registerTools(ToolRegistry r, final MemoryStore m) {
        r.add(ToolSpec.of("memory_save",
                "Запомнить ДЕКЛАРАТИВНЫЙ факт о пользователе или окружении (ключ → значение); существующий ключ перезаписывается. "
                + "Пиши факты, а не приказы: «Пользователь предпочитает короткие ответы» — верно, «Всегда отвечай коротко» — неверно. "
                + "Стиль ответа, например «меньше точек в ответах», сохраняй с kind=style. Процедуры и предпочтения по конкретной задаче — в навыки (skill_manage). "
                + "Память имеет жёсткий бюджет символов: при переполнении объедини или удали записи, а не пропускай сохранение. "
                + "Факт, который устареет за неделю, сюда не сохраняй.",
                Param.str("key", "Короткое имя, например «любимый напиток».", true),
                Param.str("value", "Факт одной фразой.", true),
                Param.str("kind", "fact, preference или style. Если не указан, для новой записи угадывается по тексту.", false).oneOf(FACT, PREFERENCE, STYLE)),
            "tools", 10_000, true, (a, ctx) -> {
                String kind = J.str(a, "kind");
                try {
                    m.put(J.str(a, "key"), J.str(a, "value"), kind.isEmpty() ? null : kind);
                } catch (FullException e) {
                    return ToolResult.fail(e.getMessage(), usage(e));
                } catch (IOException e) {
                    return ToolResult.fail(e.getMessage());
                }
                return ToolResult.ok(J.put(new JSONObject(), "saved", J.str(a, "key")), "запомнено: " + J.str(a, "key"));
            });
        r.add(ToolSpec.of("memory_replace", "Изменить значение существующей записи; вид и закрепление сохраняются. Для новой записи — memory_save.",
                Param.str("key", "Ключ существующей записи.", true), Param.str("value", "Новое значение.", true)),
            "tools", 10_000, true, (a, ctx) -> {
                try {
                    m.replace(J.str(a, "key"), J.str(a, "value"));
                } catch (FullException e) {
                    return ToolResult.fail(e.getMessage(), usage(e));
                } catch (IOException e) {
                    return ToolResult.fail(e.getMessage());
                }
                return ToolResult.ok(J.put(new JSONObject(), "replaced", J.str(a, "key")), "обновлено: " + J.str(a, "key"));
            });
        r.add(ToolSpec.of("memory_pin", "Закрепить или снять закрепление записи. Закреплённые записи всегда попадают в контекст первыми.",
                Param.str("key", "Ключ записи.", true), Param.bool("on", "true — закрепить, false — снять.", true)),
            "tools", 10_000, true, (a, ctx) -> {
                boolean on = J.bool(a, "on", true);
                try {
                    m.pin(J.str(a, "key"), on);
                } catch (IOException e) {
                    return ToolResult.fail(e.getMessage());
                }
                return ToolResult.ok(J.put(new JSONObject(), "pinned", on), (on ? "закреплено: " : "откреплено: ") + J.str(a, "key"));
            });
        r.add(ToolSpec.of("memory_get", "Память: запись по ключу или весь список.",
                Param.str("key", "Ключ записи (необязательно).", false)),
            "tools", 10_000, false, (a, ctx) -> {
                String k = J.str(a, "key");
                if (!k.isEmpty()) {
                    MemoryStore.Entry e = m.entry(k);
                    if (e == null) return ToolResult.fail("Нет записи «" + k + "»");
                    return ToolResult.ok(view(e), k);
                }
                JSONArray arr = new JSONArray();
                for (Entry e : m.all()) arr.put(view(e));
                JSONObject o = J.put(new JSONObject(), "entries", arr);
                J.put(o, "usage", J.put(J.put(new JSONObject(), "used", m.used()), "limit", m.budget()));
                return ToolResult.ok(o, m.size() + " записей");
            });
        r.add(ToolSpec.of("memory_search", "Поиск в памяти по подстроке.", Param.str("query", "Что искать.", true)),
            "tools", 10_000, false, (a, ctx) -> {
                JSONArray arr = new JSONArray();
                for (Entry e : m.search(J.str(a, "query"))) arr.put(view(e));
                return ToolResult.ok(J.put(new JSONObject(), "entries", arr), arr.length() + " найдено");
            });
        r.add(ToolSpec.of("memory_delete", "Забыть запись памяти по ключу.", Param.str("key", "Ключ.", true)),
            "tools", 10_000, true, (a, ctx) -> {
                if (!m.delete(J.str(a, "key"))) return ToolResult.fail("Нет записи «" + J.str(a, "key") + "»");
                return ToolResult.ok(J.put(new JSONObject(), "deleted", J.str(a, "key")), "удалено: " + J.str(a, "key"));
            });
    }
}
