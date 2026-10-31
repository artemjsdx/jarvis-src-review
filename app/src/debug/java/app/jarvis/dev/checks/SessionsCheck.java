package app.jarvis.dev.checks;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.ToolCall;
import app.jarvis.data.ChatItem;
import app.jarvis.data.ChatStore;
import app.jarvis.data.Db;
import app.jarvis.data.Settings;
import app.jarvis.rt.Sessions;
import java.io.File;
import java.util.List;
import org.json.JSONArray;

/** Real SQLite on the phone's runtime: schema migration from v1, sessions, clear context, cascade, concurrency. */
public final class SessionsCheck {
    static int fails;

    static void check(boolean ok, String what) { System.out.println((ok ? "  ok   " : "  FAIL ") + what); if (!ok) fails++; System.out.flush(); }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { System.out.println("CRASH " + t); t.printStackTrace(System.out); fails++; }
        System.out.println(fails == 0 ? "SESSIONSCHECK OK" : "SESSIONSCHECK FAILURES: " + fails);
        System.out.flush();
        System.exit(fails == 0 ? 0 : 1);
    }

    static long scalar(Db db, String sql, String... args) {
        try (Cursor c = db.sql.rawQuery(sql, args)) { return c.moveToFirst() ? c.getLong(0) : -1; }
    }

    static void run() throws Exception {
        Looper.prepareMainLooper();
        File dir = new File(System.getenv("TMPDIR"), "sessionscheck-" + System.nanoTime());
        dir.mkdirs();

        System.out.println("== migration from the schema of version 1 (existing data)");
        File f = new File(dir, "old.db");
        SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(f, null);
        old.execSQL("CREATE TABLE conv(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, created INTEGER, updated INTEGER)");
        old.execSQL("CREATE TABLE item(seq INTEGER PRIMARY KEY AUTOINCREMENT, conv_id INTEGER, kind TEXT, ts INTEGER, run_id TEXT, text TEXT, extra TEXT)");
        old.execSQL("CREATE INDEX item_conv ON item(conv_id, seq)");
        old.execSQL("CREATE TABLE run(id TEXT PRIMARY KEY, conv_id INTEGER, started INTEGER, ended INTEGER, status TEXT, model TEXT, provider TEXT, "
            + "steps INTEGER, tool_calls INTEGER, first_text_ms INTEGER, total_ms INTEGER, error TEXT, user_text TEXT)");
        old.execSQL("INSERT INTO conv(id,title,created,updated) VALUES (1,'Старый чат',1,1)");
        old.execSQL("INSERT INTO conv(id,title,created,updated) VALUES (2,'mcp-abc',1,1)");
        old.execSQL("INSERT INTO item(conv_id,kind,ts,text,extra) VALUES (1,'USER',1,'привет','{}')");
        old.execSQL("INSERT INTO item(conv_id,kind,ts,text,extra) VALUES (1,'ASSISTANT',2,'Здравствуйте\nдруг','{}')");
        old.execSQL("INSERT INTO run(id,conv_id,started,status) VALUES ('r0',1,1,'DONE')");
        old.setVersion(1);
        old.close();

        Db db = Db.open(f);
        check(db.sql.getVersion() == 2, "user_version 1 -> 2");
        List<String> cols = new java.util.ArrayList<>();
        try (Cursor c = db.sql.rawQuery("PRAGMA table_info(conv)", null)) { while (c.moveToNext()) cols.add(c.getString(1)); }
        check(cols.containsAll(java.util.Arrays.asList("pinned", "archived", "hidden", "last_preview", "item_count", "context_from_seq", "model_note")), "new conv columns present: " + cols);
        check(scalar(db, "SELECT COUNT(*) FROM item") == 2 && scalar(db, "SELECT COUNT(*) FROM run") == 1, "items and runs survive the migration");
        check(scalar(db, "SELECT item_count FROM conv WHERE id=1") == 2, "item_count backfilled");
        check(scalar(db, "SELECT COUNT(*) FROM conv WHERE id=1 AND last_preview='Здравствуйте друг' AND title='Старый чат'") == 1, "preview backfilled, title kept");
        check(scalar(db, "SELECT hidden FROM conv WHERE id=2") == 1, "mcp-* sessions marked hidden");
        db.close();
        Db again = Db.open(f);
        check(again.sql.getVersion() == 2 && scalar(again, "SELECT COUNT(*) FROM item") == 2, "reopen is idempotent");
        db = again;

        ChatStore cs = new ChatStore(db);
        Settings settings = new Settings(new File(dir, "settings.json"));
        Sessions ss = new Sessions(cs, settings, new java.util.concurrent.Executor() { @Override public void execute(Runnable r) { r.run(); } });
        final int[] events = {0};
        ss.addListener(new Sessions.Listener() { @Override public void onSessionsChanged(long activeId) { events[0]++; } });

        System.out.println("== active session and hidden sessions");
        check(ss.active() == 1, "active falls back to the newest visible session (" + ss.active() + ")");
        check(ss.list("", false, false).size() == 1, "hidden MCP session not listed by default");
        check(ss.list("", true, false).size() == 2, "hidden MCP session listed when asked");

        System.out.println("== create, select, restore after restart");
        long a = ss.create(null);
        check(ss.active() == a, "create makes the session active");
        long b = ss.create("Тест Б");
        check(ss.select(b) && ss.active() == b, "select switches the active session");
        Sessions restarted = new Sessions(new ChatStore(db), settings, new java.util.concurrent.Executor() { @Override public void execute(Runnable r) { r.run(); } });
        check(restarted.active() == b, "last active session restored from ui.lastConv");
        check(!ss.select(987654), "select of an unknown id is refused");
        check(ss.newChat() == b, "newChat reuses the active empty session");

        System.out.println("== items, counters, preview");
        ChatItem u = new ChatItem(); u.convId = a; u.kind = ChatItem.Kind.USER; u.text = "Расскажи\nпро погоду в Минске"; cs.add(u);
        ss.autoTitle(a, "## Расскажи про погоду в Минске завтра утром");
        check("Расскажи про погоду в Минске завтра утро".equals(cs.conversation(a).title), "autoTitle takes the first 40 chars: '" + cs.conversation(a).title + "'");
        ss.autoTitle(a, "другой текст");
        check(!"другой текст".equals(cs.conversation(a).title), "autoTitle does not rename a named session");
        ChatItem as = new ChatItem(); as.convId = a; as.kind = ChatItem.Kind.ASSISTANT; as.text = ""; as.runId = "r1"; cs.add(as);
        check(cs.conversation(a).items == 2, "item_count increments per add");
        check("Расскажи про погоду в Минске".equals(cs.conversation(a).preview), "preview ignores empty assistant text: '" + cs.conversation(a).preview + "'");
        as.text = "В Минске завтра облачно,\nтемпература +5."; cs.update(as);
        check(cs.conversation(a).preview.startsWith("В Минске завтра облачно, температура"), "preview refreshed on update: '" + cs.conversation(a).preview + "'");
        check(cs.conversation(a).items == 2, "update does not change item_count");

        System.out.println("== clear context keeps history visible, hides it from the model");
        cs.add(newUser(a, "вопрос до очистки"));
        cs.add(newAssistant(a, "ответ до очистки"));
        int shownBefore = cs.items(a, 0, 100).size();
        ss.clearContext(a);
        check(cs.items(a, 0, 100).size() == shownBefore + 1, "divider is added, old items remain visible");
        check(cs.history(a, 100000).isEmpty(), "model context is empty right after clear");
        cs.add(newUser(a, "вопрос после очистки"));
        List<ChatMessage> h = cs.history(a, 100000);
        check(h.size() == 1 && "вопрос после очистки".equals(h.get(0).content), "model sees only what comes after the divider: " + h.size());
        check(cs.contextFrom(a) > 0, "context_from_seq set");

        System.out.println("== tool pairing at the clear boundary");
        ChatItem call = newAssistant(a, "");
        JSONArray arr = new JSONArray();
        arr.put(new ToolCall("c9", "note_create", "{}").toJson());
        call.toolCallsJson = arr.toString();
        cs.add(call);
        ss.clearContext(a);
        ChatItem res = new ChatItem(); res.convId = a; res.kind = ChatItem.Kind.TOOL; res.runId = "r9"; res.callId = "c9"; res.toolName = "note_create";
        res.text = "{\"ok\":true}"; res.toolStatus = ChatItem.ToolStatus.DONE; cs.add(res);
        List<ChatMessage> hb = cs.history(a, 100000);
        check(hb.isEmpty() || !ChatMessage.TOOL.equals(hb.get(0).role), "model context never starts with a tool result (size " + hb.size() + ")");
        ChatItem call2 = newAssistant(a, "");
        JSONArray arr2 = new JSONArray(); arr2.put(new ToolCall("c10", "note_create", "{}").toJson()); call2.toolCallsJson = arr2.toString();
        cs.add(call2);
        ChatItem res2 = new ChatItem(); res2.convId = a; res2.kind = ChatItem.Kind.TOOL; res2.runId = "r10"; res2.callId = "c10"; res2.toolName = "note_create";
        res2.text = "{\"ok\":true}"; res2.toolStatus = ChatItem.ToolStatus.DONE; cs.add(res2);
        List<ChatMessage> hc = cs.history(a, 100000);
        check(hc.size() == 2 && ChatMessage.ASSISTANT.equals(hc.get(0).role) && hc.get(0).hasToolCalls() && ChatMessage.TOOL.equals(hc.get(1).role), "complete call+result after the divider is kept");

        System.out.println("== pin, archive, search");
        long p1 = ss.create("Альфа"), p2 = ss.create("Бета");
        ss.pin(p2, true);
        check(ss.list("", false, false).get(0).id == p2, "pinned session first");
        ss.archive(p1, true);
        check(!containsId(ss.list("", false, false), p1) && containsId(ss.list("", false, true), p1), "archived hidden from default list");
        check(ss.list("Альфа", false, true).size() == 1, "search by title");
        cs.add(newUser(p2, "квартальный отчёт готов"));
        check(containsId(ss.list("квартальн", false, false), p2), "search by message text");
        check(ss.list("100%_", false, false).isEmpty(), "LIKE wildcards in the query are literal");

        System.out.println("== delete cascades and re-activates");
        cs.runStarted("rA", a, "m", "p", "t");
        check(scalar(db, "SELECT COUNT(*) FROM run WHERE conv_id=" + a) == 1, "run of the active session exists before delete");
        ss.delete(a);
        check(scalar(db, "SELECT COUNT(*) FROM item WHERE conv_id=" + a) == 0 && scalar(db, "SELECT COUNT(*) FROM run WHERE conv_id=" + a) == 0 && !cs.exists(a), "items, runs and session removed");
        check(ss.active() != a && ss.active() > 0 && cs.exists(ss.active()), "deleting the active session activates another one (" + ss.active() + ")");
        ss.delete(1);
        check(scalar(db, "SELECT COUNT(*) FROM run WHERE conv_id=1") == 0 && !cs.exists(1), "run of an inactive session removed with it");
        check(scalar(db, "SELECT COUNT(*) FROM conv WHERE id=2") == 1, "hidden session untouched by the delete");

        System.out.println("== concurrency: two threads add items");
        final long shared = ss.create("Параллельный");
        Thread t1 = writer(cs, shared, "t1"), t2 = writer(cs, shared, "t2");
        long t0 = System.currentTimeMillis();
        t1.start(); t2.start(); t1.join(); t2.join();
        long ms = System.currentTimeMillis() - t0;
        check(scalar(db, "SELECT COUNT(*) FROM item WHERE conv_id=" + shared) == 600, "600 rows written by 2 threads in " + ms + " ms");
        check(scalar(db, "SELECT item_count FROM conv WHERE id=" + shared) == 600, "item_count consistent (600)");
        check(scalar(db, "SELECT COUNT(DISTINCT seq) FROM item WHERE conv_id=" + shared) == 600, "seq values unique");

        System.out.println("== deleting every visible session creates a fresh one");
        for (ChatStore.Conv c : cs.conversations("", false, true)) ss.delete(c.id);
        long fresh = ss.active();
        check(cs.exists(fresh) && cs.itemCount(fresh) == 0 && !cs.conversation(fresh).hidden, "fresh empty session active (" + fresh + ")");
        check(scalar(db, "SELECT COUNT(*) FROM conv WHERE id=2") == 1, "hidden MCP session still exists");
        db.close();
    }

    static boolean containsId(List<ChatStore.Conv> l, long id) { for (ChatStore.Conv c : l) if (c.id == id) return true; return false; }

    static ChatItem newUser(long conv, String text) { ChatItem u = new ChatItem(); u.convId = conv; u.kind = ChatItem.Kind.USER; u.text = text; u.runId = "r"; return u; }

    static ChatItem newAssistant(long conv, String text) { ChatItem x = new ChatItem(); x.convId = conv; x.kind = ChatItem.Kind.ASSISTANT; x.text = text; x.runId = "r"; return x; }

    static Thread writer(final ChatStore cs, final long conv, final String tag) {
        return new Thread(new Runnable() { @Override public void run() {
            for (int i = 0; i < 300; i++) cs.add(newUser(conv, tag + " " + i));
        } });
    }
}
