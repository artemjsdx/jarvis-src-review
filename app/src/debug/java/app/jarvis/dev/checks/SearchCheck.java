package app.jarvis.dev.checks;

import android.database.Cursor;
import android.os.Looper;
import app.jarvis.core.chat.SearchHit;
import app.jarvis.data.ChatItem;
import app.jarvis.data.ChatStore;
import app.jarvis.data.Db;
import java.io.File;
import java.util.List;

/** Chat search on the phone's SQLite: FTS5 index with triggers, migration backfill, LIKE-scan fallback, timings. */
public final class SearchCheck {
    static int fails;

    static void check(boolean ok, String what) { System.out.println((ok ? "  ok   " : "  FAIL ") + what); if (!ok) fails++; System.out.flush(); }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { System.out.println("CRASH " + t); t.printStackTrace(System.out); fails++; }
        System.out.println(fails == 0 ? "SEARCHCHECK OK" : "SEARCHCHECK FAILURES: " + fails);
        System.out.flush();
        System.exit(fails == 0 ? 0 : 1);
    }

    static String str(Db db, String sql) {
        try (Cursor c = db.sql.rawQuery(sql, null)) { return c.moveToFirst() ? c.getString(0) : "?"; }
    }

    static long scalar(Db db, String sql) {
        try (Cursor c = db.sql.rawQuery(sql, null)) { return c.moveToFirst() ? c.getLong(0) : -1; }
    }

    static void add(ChatStore chat, long conv, ChatItem.Kind kind, String text, long ts) {
        ChatItem it = new ChatItem();
        it.convId = conv; it.kind = kind; it.text = text; it.ts = ts;
        if (kind == ChatItem.Kind.TOOL) { it.callId = "c" + ts; it.toolName = "x"; }
        chat.add(it);
    }

    static boolean hasIndex(Db db) {
        try (Cursor c = db.sql.rawQuery("SELECT 1 FROM sqlite_master WHERE name='item_fts'", null)) { return c.moveToFirst(); }
    }

    static void dropIndex(Db db) {
        db.sql.execSQL("DROP TRIGGER IF EXISTS item_fts_ai");
        db.sql.execSQL("DROP TRIGGER IF EXISTS item_fts_au");
        db.sql.execSQL("DROP TRIGGER IF EXISTS item_fts_ad");
        db.sql.execSQL("DROP TABLE IF EXISTS item_fts");
    }

    static void run() throws Exception {
        Looper.prepareMainLooper();
        File dir = new File(System.getenv("TMPDIR"), "searchcheck-" + System.nanoTime());
        dir.mkdirs();
        long now = System.currentTimeMillis();
        long day = 86_400_000L;

        System.out.println("== runtime");
        Db db = Db.open(new File(dir, "a.db"));
        System.out.println("  sqlite " + str(db, "SELECT sqlite_version()") + ", user_version " + scalar(db, "PRAGMA user_version")
            + ", index " + (db.fts ? "FTS5 (item_fts)" : "none (LIKE scan)"));
        try {
            db.sql.execSQL("CREATE VIRTUAL TABLE temp.probe_fts5 USING fts5(x)");
            db.sql.execSQL("DROP TABLE temp.probe_fts5");
            System.out.println("  fts5 module: available");
        } catch (Exception e) {
            System.out.println("  fts5 module: NOT available: " + e.getMessage());
        }
        System.out.println("  fts index used by this database: " + db.fts);

        ChatStore chat = new ChatStore(db);
        long trip = chat.newConversation("Поездка в Минск");
        long yolki = chat.newConversation("Новый диалог");
        long hidden = chat.newConversation("mcp-тест");
        add(chat, trip, ChatItem.Kind.USER, "Купи билет на поезд в Минск на пятницу", now - 1000);
        add(chat, trip, ChatItem.Kind.ASSISTANT, "Билет на поезд: 18:40, вагон 3, 24 рубля.", now - 900);
        add(chat, trip, ChatItem.Kind.TOOL, "{\"ok\":true,\"text\":\"поезд поезд поезд\"}", now - 800);
        add(chat, trip, ChatItem.Kind.USER, "Старый разговор про поезд", now - 10 * day);
        add(chat, yolki, ChatItem.Kind.USER, "Какая сегодня погода в Ёлках?", now - 500);
        add(chat, hidden, ChatItem.Kind.USER, "поезд в тестовом чате", now - 400);

        System.out.println("== search");
        List<SearchHit> r = chat.search("поезд", 20, 0);
        check(r.size() == 3, "«поезд»: 2 recent messages + 1 old, tool output and hidden chat excluded (got " + r.size() + ")");
        check(r.size() > 1 && r.get(0).ts >= r.get(1).ts, "newest first");
        check(!r.isEmpty() && r.get(0).title.equals("Поездка в Минск"), "chat title on the hit");
        check(!r.isEmpty() && r.get(0).snippet.contains("поезд"), "snippet contains the match: «" + (r.isEmpty() ? "" : r.get(0).snippet) + "»");
        check(chat.search("поез", 20, 0).size() == 3, "prefix «поез» finds the same messages");
        check(chat.search("минск поезд", 20, 0).size() == 1, "two words must both occur (only the first message has both)");
        check(chat.search("поезд", 20, 1).size() == 2, "days=1 drops the 10-day-old message");
        check(chat.search("ёлк", 20, 0).size() == 1, "ё folds: «ёлк» finds «Ёлках»");
        check(chat.search("елках", 20, 0).size() == 1, "е finds ё in «Ёлках»");
        check(chat.search("сбер", 20, 0).isEmpty(), "no match gives an empty list");
        check(chat.search("  ?! ", 20, 0).isEmpty(), "punctuation-only query gives an empty list");

        System.out.println("== triggers keep the index in step");
        ChatItem answer = chat.items(trip, 0, 10).get(1);
        answer.text = "Билет оформлен.";
        chat.update(answer);
        check(chat.search("поезд", 20, 0).size() == 2, "edited assistant text leaves the index");
        check(chat.search("оформлен", 20, 0).size() == 1, "edited text enters the index");
        chat.deleteConversation(yolki);
        check(chat.search("ёлк", 20, 0).isEmpty(), "deleting a chat removes its messages from the index");

        System.out.println("== migration from version 2 (existing messages get indexed)");
        File v2 = new File(dir, "v2.db");
        Db old = Db.open(v2);
        dropIndex(old);
        old.sql.execSQL("PRAGMA user_version = 2");
        old.sql.execSQL("INSERT INTO conv(title, created, updated, item_count) VALUES ('Старый', 1, 1, 2)");
        old.sql.execSQL("INSERT INTO item(conv_id, kind, ts, run_id, text, extra) VALUES (1, 'USER', 1, '', 'вчера обсуждали Сбербанк и кредит', '{}')");
        old.sql.execSQL("INSERT INTO item(conv_id, kind, ts, run_id, text, extra) VALUES (1, 'ASSISTANT', 2, '', 'кредит одобрен', '{}')");
        old.close();
        Db up = Db.open(v2);
        check(scalar(up, "PRAGMA user_version") == 3, "migration 2→3 sets user_version 3");
        check(up.fts == hasIndex(up), "index flag matches the schema");
        if (up.fts) check(scalar(up, "SELECT COUNT(*) FROM item_fts") == 2, "existing messages are backfilled into the index");
        ChatStore upChat = new ChatStore(up);
        check(upChat.search("сбер", 5, 0).size() == 1, "backfilled text is searchable (Сбер → Сбербанк), index=" + up.fts);
        up.close();

        System.out.println("== fallback without FTS (LIKE scan with the same results)");
        Db nf = Db.open(new File(dir, "nofts.db"));
        ChatStore nfChat = new ChatStore(nf);
        long c = nfChat.newConversation("Поездка");
        add(nfChat, c, ChatItem.Kind.USER, "Купи билет на поезд в Минск", now - 1000);
        add(nfChat, c, ChatItem.Kind.ASSISTANT, "Билет на поезд: 18:40", now - 900);
        add(nfChat, c, ChatItem.Kind.TOOL, "поезд", now - 800);
        add(nfChat, c, ChatItem.Kind.USER, "Ёлки и погода", now - 700);
        dropIndex(nf);
        nf.close();
        Db scan = Db.open(new File(dir, "nofts.db"));
        check(!scan.fts, "no index → fallback path");
        ChatStore sc = new ChatStore(scan);
        check(sc.search("поезд", 20, 0).size() == 2, "fallback: «поезд» finds 2 messages, not the tool output");
        check(sc.search("елки", 20, 0).size() == 1, "fallback: ё folds");
        check(sc.search("поезд", 1, 0).size() == 1, "fallback: limit respected");
        scan.close();

        System.out.println("== timings (bench, this phone)");
        Db big = Db.open(new File(dir, "big.db"));
        ChatStore bc = new ChatStore(big);
        long cid = bc.newConversation("Большой");
        String[] words = {"погода", "маршрут", "музыка", "поезд", "кредит", "рецепт", "будильник", "файл", "память", "сбербанк"};
        long t0 = System.nanoTime();
        big.sql.beginTransaction();
        try {
            for (int i = 0; i < 2000; i++) {
                add(bc, cid, i % 2 == 0 ? ChatItem.Kind.USER : ChatItem.Kind.ASSISTANT,
                    "сообщение " + i + ": " + words[i % 10] + " и " + words[(i * 7) % 10] + " про " + words[(i * 3) % 10] + " в обычном разговоре", now - (2000 - i) * 60_000L);
            }
            big.sql.setTransactionSuccessful();
        } finally { big.sql.endTransaction(); }
        System.out.println("  insert 2000 messages (one transaction, with triggers): " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        long best = Long.MAX_VALUE; int n = 0;
        for (int k = 0; k < 5; k++) {
            long s = System.nanoTime();
            n = bc.search("сбер", 8, 0).size();
            best = Math.min(best, System.nanoTime() - s);
        }
        System.out.println("  search «сбер» over 2000 messages, index=" + big.fts + " (LIKE scan): best " + best / 1000 + " µs, hits " + n + " (limit 8)");
        check(n == 8, "limit applied on the big set");
        big.close();

        System.out.println("== results (for the report)");
        for (SearchHit h : new ChatStore(Db.open(new File(dir, "a.db"))).search("поезд", 5, 0)) {
            System.out.println("  hit chat=" + h.convId + " seq=" + h.seq + " role=" + h.role + " snippet=«" + h.snippet + "»");
        }
    }
}
