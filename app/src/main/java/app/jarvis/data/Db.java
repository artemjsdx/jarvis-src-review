package app.jarvis.data;

import android.database.sqlite.SQLiteDatabase;
import java.io.File;

/** One SQLite file for chat items, runs and the journal. WAL mode; schema versioned via PRAGMA user_version. */
public final class Db {
    public final SQLiteDatabase sql;
    /** True when the message index (item_fts, FTS5) exists; otherwise chat search scans the items. */
    public final boolean fts;

    private Db(SQLiteDatabase sql, boolean fts) { this.sql = sql; this.fts = fts; }

    public static Db open(File file) {
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null) dir.mkdirs();
        SQLiteDatabase d = SQLiteDatabase.openOrCreateDatabase(file, null);
        try { d.enableWriteAheadLogging(); } catch (Throwable ignored) { }
        int v = d.getVersion();
        if (v < 1) migrate(d, 1,
            "CREATE TABLE IF NOT EXISTS conv(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, created INTEGER, updated INTEGER)",
            "CREATE TABLE IF NOT EXISTS item(seq INTEGER PRIMARY KEY AUTOINCREMENT, conv_id INTEGER, kind TEXT, ts INTEGER, run_id TEXT, text TEXT, extra TEXT)",
            "CREATE INDEX IF NOT EXISTS item_conv ON item(conv_id, seq)",
            "CREATE TABLE IF NOT EXISTS run(id TEXT PRIMARY KEY, conv_id INTEGER, started INTEGER, ended INTEGER, status TEXT, model TEXT, provider TEXT, "
                + "steps INTEGER, tool_calls INTEGER, first_text_ms INTEGER, total_ms INTEGER, error TEXT, user_text TEXT)",
            "CREATE TABLE IF NOT EXISTS log(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, cat TEXT, level INTEGER, msg TEXT, run_id TEXT, details TEXT, dur INTEGER)",
            "CREATE INDEX IF NOT EXISTS log_cat ON log(cat, id)");
        // additive only: session columns; existing rows get defaults and their counts and previews backfilled
        if (v < 2) migrate(d, 2,
            "ALTER TABLE conv ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE conv ADD COLUMN archived INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE conv ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE conv ADD COLUMN last_preview TEXT",
            "ALTER TABLE conv ADD COLUMN item_count INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE conv ADD COLUMN context_from_seq INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE conv ADD COLUMN model_note TEXT",
            "UPDATE conv SET item_count = (SELECT COUNT(*) FROM item WHERE item.conv_id = conv.id)",
            "UPDATE conv SET last_preview = (SELECT replace(substr(item.text, 1, 120), char(10), ' ') FROM item WHERE item.conv_id = conv.id"
                + " AND item.kind IN ('USER','ASSISTANT') AND item.text <> '' ORDER BY item.seq DESC LIMIT 1)",
            "UPDATE conv SET hidden = 1 WHERE title LIKE 'mcp-%'");
        // message index for chat search: triggers keep item_fts in step with user and assistant texts (tool blobs are not indexed)
        if (v < 3) {
            if (fts5Available(d)) {
                try { migrate(d, 3, FTS_STATEMENTS); }
                catch (android.database.SQLException e) { migrate(d, 3); }
            } else migrate(d, 3);
        }
        return new Db(d, hasTable(d, "item_fts"));
    }

    private static final String[] FTS_STATEMENTS = {
        "CREATE VIRTUAL TABLE IF NOT EXISTS item_fts USING fts5(text, conv_id UNINDEXED, tokenize = 'unicode61 remove_diacritics 2')",
        "CREATE TRIGGER IF NOT EXISTS item_fts_ai AFTER INSERT ON item WHEN NEW.kind IN ('USER','ASSISTANT') AND NEW.text <> '' BEGIN"
            + " INSERT INTO item_fts(rowid, text, conv_id) VALUES (NEW.seq, NEW.text, NEW.conv_id); END",
        "CREATE TRIGGER IF NOT EXISTS item_fts_au AFTER UPDATE OF text, kind ON item BEGIN"
            + " DELETE FROM item_fts WHERE rowid = OLD.seq;"
            + " INSERT INTO item_fts(rowid, text, conv_id) SELECT NEW.seq, NEW.text, NEW.conv_id WHERE NEW.kind IN ('USER','ASSISTANT') AND NEW.text <> ''; END",
        "CREATE TRIGGER IF NOT EXISTS item_fts_ad AFTER DELETE ON item BEGIN DELETE FROM item_fts WHERE rowid = OLD.seq; END",
        "INSERT INTO item_fts(rowid, text, conv_id) SELECT seq, text, conv_id FROM item WHERE kind IN ('USER','ASSISTANT') AND text <> ''",
    };

    private static boolean fts5Available(SQLiteDatabase d) {
        try {
            d.execSQL("CREATE VIRTUAL TABLE temp.jv_fts_probe USING fts5(x)");
            d.execSQL("DROP TABLE temp.jv_fts_probe");
            return true;
        } catch (android.database.SQLException e) {
            return false;
        }
    }

    private static boolean hasTable(SQLiteDatabase d, String name) {
        try (android.database.Cursor c = d.rawQuery("SELECT 1 FROM sqlite_master WHERE name=?", new String[]{name})) {
            return c.moveToFirst();
        }
    }

    private static void migrate(SQLiteDatabase d, int to, String... statements) {
        d.beginTransaction();
        try {
            for (String s : statements) d.execSQL(s);
            d.execSQL("PRAGMA user_version = " + to);
            d.setTransactionSuccessful();
        } finally { d.endTransaction(); }
    }

    public void close() { try { sql.close(); } catch (Throwable ignored) { } }
}
