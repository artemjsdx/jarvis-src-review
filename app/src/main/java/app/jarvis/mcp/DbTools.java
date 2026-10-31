package app.jarvis.mcp;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import app.jarvis.core.mcp.McpRegistry;
import app.jarvis.core.mcp.McpResult;
import app.jarvis.core.mcp.McpTools;
import app.jarvis.core.mcp.SqlGuard;
import app.jarvis.core.util.J;
import app.jarvis.core.util.Redactor;
import org.json.JSONArray;
import org.json.JSONObject;

/** db_schema, db_query (read-only, guarded by SqlGuard) and db_exec (destructive). */
final class DbTools {
    private DbTools() { }

    static void register(McpRegistry r, final McpBridge b) {
        final SQLiteDatabase db = b.j.db.sql;

        r.register(McpTools.tool("db_schema", "Таблицы базы jarvis.db: столбцы, число строк, версия схемы.", (a, c) -> {
            JSONObject o = new JSONObject();
            JSONArray tables = new JSONArray();
            try (Cursor t = db.rawQuery("SELECT name, sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null)) {
                while (t.moveToNext()) {
                    String name = t.getString(0);
                    JSONObject ti = new JSONObject();
                    J.put(ti, "name", name);
                    JSONArray cols = new JSONArray();
                    try (Cursor ci = db.rawQuery("PRAGMA table_info(" + quoteIdent(name) + ")", null)) {
                        while (ci.moveToNext()) cols.put(J.put(J.put(new JSONObject(), "name", ci.getString(1)), "type", ci.getString(2)));
                    }
                    J.put(ti, "columns", cols);
                    try (Cursor cc = db.rawQuery("SELECT COUNT(*) FROM " + quoteIdent(name), null)) { if (cc.moveToFirst()) J.put(ti, "rows", cc.getLong(0)); }
                    tables.put(ti);
                }
            }
            J.put(o, "tables", tables);
            J.put(o, "userVersion", db.getVersion());
            J.put(o, "path", db.getPath() == null ? "" : new java.io.File(db.getPath()).getName());
            return McpResult.json(o);
        }).readOnly());

        r.register(McpTools.tool("db_query", "Только чтение: SELECT / WITH … SELECT / безопасные PRAGMA. Строки как массивы.",
            McpTools.Schema.obj().str("sql", "SQL-запрос", true).array("params", "Параметры для ? (строки или числа)", false).integer("limit", "Максимум строк (1..1000, по умолчанию 100)", false),
            (a, c) -> {
                String sql = J.str(a, "sql");
                String bad = SqlGuard.check(sql);
                if (bad != null) return Tl.err("FORBIDDEN", "Разрешены только запросы на чтение.", bad);
                int limit = Tl.clamp(Tl.intArg(a, "limit", 100), 1, 1000);
                JSONArray p = a.optJSONArray("params");
                String[] args = new String[p == null ? 0 : p.length()];
                for (int i = 0; i < args.length; i++) args[i] = String.valueOf(p.opt(i));
                long t0 = System.currentTimeMillis();
                JSONObject o = new JSONObject();
                JSONArray rows = new JSONArray();
                try (Cursor cur = db.rawQuery(sql.trim().replaceAll(";+\\s*$", ""), args)) {
                    JSONArray cols = new JSONArray();
                    for (String n : cur.getColumnNames()) cols.put(n);
                    J.put(o, "columns", cols);
                    boolean more = false;
                    while (cur.moveToNext()) {
                        if (rows.length() >= limit) { more = true; break; }
                        JSONArray row = new JSONArray();
                        for (int i = 0; i < cols.length(); i++) row.put(cell(cur, i));
                        rows.put(row);
                    }
                    J.put(o, "truncated", more);
                }
                J.put(o, "rows", rows);
                J.put(o, "count", rows.length());
                J.put(o, "ms", System.currentTimeMillis() - t0);
                return McpResult.json(o);
            }).readOnly());

        r.register(McpTools.tool("db_exec", "Запись в базу (нужен mcp.allowDestructive): INSERT/UPDATE/DELETE/DDL одной командой.",
            McpTools.Schema.obj().str("sql", "SQL-команда", true).array("params", "Параметры для ?", false), (a, c) -> {
                String sql = J.str(a, "sql").trim();
                if (sql.isEmpty()) return Tl.err("INVALID_ARGS", "Пустая команда.", "");
                JSONArray p = a.optJSONArray("params");
                Object[] args = new Object[p == null ? 0 : p.length()];
                for (int i = 0; i < args.length; i++) args[i] = p.opt(i) instanceof Number ? p.opt(i) : String.valueOf(p.opt(i));
                db.execSQL(sql, args);
                long changes = 0;
                try (Cursor cur = db.rawQuery("SELECT changes()", null)) { if (cur.moveToFirst()) changes = cur.getLong(0); }
                return McpResult.json(J.put(new JSONObject(), "changes", changes));
            }).destructive());
    }

    private static Object cell(Cursor cur, int i) {
        switch (cur.getType(i)) {
            case Cursor.FIELD_TYPE_NULL: return JSONObject.NULL;
            case Cursor.FIELD_TYPE_INTEGER: return cur.getLong(i);
            case Cursor.FIELD_TYPE_FLOAT: return cur.getDouble(i);
            case Cursor.FIELD_TYPE_BLOB: return "<blob " + cur.getBlob(i).length + " bytes>";
            default: return Tl.clip(Redactor.redact(cur.getString(i)), 4000);
        }
    }

    private static String quoteIdent(String n) { return "\"" + n.replace("\"", "\"\"") + "\""; }
}
