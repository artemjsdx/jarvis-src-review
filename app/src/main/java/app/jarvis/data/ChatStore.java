package app.jarvis.data;

import android.content.ContentValues;
import android.database.Cursor;
import app.jarvis.core.agent.ContextBudget;
import app.jarvis.core.chat.SearchHit;
import app.jarvis.core.chat.SearchQuery;
import app.jarvis.core.chat.SearchSnippet;
import app.jarvis.core.chat.SessionText;
import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.ToolCall;
import app.jarvis.core.util.J;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Conversation storage. A single ordered table of items serves both the UI (bubbles + tool cards) and the model
 * protocol (assistant tool_calls, tool results). After a crash, tool items stuck in RUNNING are closed as INTERRUPTED
 * with a result that tells the model the outcome is unknown, so nothing is ever silently re-executed.
 */
public final class ChatStore {
    /** Sessions created by the MCP bridge start with this prefix; the UI treats them as hidden test sessions. */
    public static final String TEST_PREFIX = "mcp-";
    /** SYSTEM item with exactly this text separates "forgotten" history from the context sent to the model. */
    public static final String CONTEXT_CLEARED = "— контекст очищен —";
    /** Text of a stored summary of the older conversation; such system items are sent to the model as system messages. */
    public static final String SUMMARY_PREFIX = "Резюме предыдущего контекста:";

    private final Db db;

    public ChatStore(Db db) { this.db = db; }

    // ---- conversations --------------------------------------------------------------------------------------------
    private static final String DEFAULT_TITLE = SessionText.DEFAULT_TITLE;
    private static final String CONV_COLS = "id,title,created,updated,item_count,pinned,archived,hidden,last_preview,context_from_seq";
    private long active;

    /** The active session; when none is selected or it vanished, the newest visible one (or a fresh one). */
    public synchronized long currentConversation() {
        if (active > 0 && exists(active)) return active;
        long id = newestVisible();
        if (id == 0) id = newConversation(DEFAULT_TITLE);
        active = id;
        return id;
    }

    /** 0 when nothing is selected. Does not create or validate anything. */
    public synchronized long activeId() { return active; }

    public synchronized void setActive(long id) { active = id; }

    private long newestVisible() {
        try (Cursor c = db.sql.rawQuery("SELECT id FROM conv WHERE hidden=0 AND archived=0 ORDER BY pinned DESC, updated DESC LIMIT 1", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    public boolean exists(long id) {
        try (Cursor c = db.sql.rawQuery("SELECT 1 FROM conv WHERE id=?", new String[]{String.valueOf(id)})) { return c.moveToFirst(); }
    }

    public long newConversation(String title) {
        ContentValues v = new ContentValues();
        long now = System.currentTimeMillis();
        v.put("title", title); v.put("created", now); v.put("updated", now);
        v.put("hidden", title.startsWith(TEST_PREFIX) ? 1 : 0);
        return db.sql.insert("conv", null, v);
    }

    public void renameConversation(long id, String title) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        db.sql.update("conv", v, "id=?", new String[]{String.valueOf(id)});
    }

    /** Sets the title only while it is still the default one (first message names the session). */
    public boolean titleIfDefault(long id, String title) {
        return db.sql.update("conv", cv("title", title), "id=? AND (title IS NULL OR title='' OR title=?)",
            new String[]{String.valueOf(id), DEFAULT_TITLE}) > 0;
    }

    public void setFlag(long id, String column, boolean on) {
        ContentValues v = new ContentValues();
        v.put(column, on ? 1 : 0);
        db.sql.update("conv", v, "id=?", new String[]{String.valueOf(id)});
    }

    private static ContentValues cv(String k, String v) { ContentValues c = new ContentValues(); c.put(k, v); return c; }

    public static final class Conv {
        public long id, created, updated, contextFrom;
        public String title = "", preview = "";
        public int items;
        public boolean pinned, archived, hidden;
    }

    /** Newest-updated first, pinned first. Hidden (MCP test) and archived sessions are excluded unless asked for. */
    public List<Conv> conversations(String query, boolean includeHidden, boolean includeArchived) {
        StringBuilder sql = new StringBuilder("SELECT " + CONV_COLS + " FROM conv WHERE 1=1");
        List<String> args = new ArrayList<>();
        if (!includeHidden) sql.append(" AND hidden=0");
        if (!includeArchived) sql.append(" AND archived=0");
        if (query != null && !query.trim().isEmpty()) {
            String like = "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            sql.append(" AND (title LIKE ? ESCAPE '\\' OR EXISTS (SELECT 1 FROM item WHERE item.conv_id=conv.id AND item.text LIKE ? ESCAPE '\\'))");
            args.add(like); args.add(like);
        }
        sql.append(" ORDER BY pinned DESC, updated DESC LIMIT 500");
        List<Conv> out = new ArrayList<>();
        try (Cursor c = db.sql.rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (c.moveToNext()) out.add(readConv(c));
        }
        return out;
    }

    public List<Conv> conversations() { return conversations("", true, true); }

    private static Conv readConv(Cursor c) {
        Conv x = new Conv();
        x.id = c.getLong(0); x.title = c.getString(1) == null ? "" : c.getString(1);
        x.created = c.getLong(2); x.updated = c.getLong(3); x.items = c.getInt(4);
        x.pinned = c.getInt(5) != 0; x.archived = c.getInt(6) != 0; x.hidden = c.getInt(7) != 0;
        x.preview = c.getString(8) == null ? "" : c.getString(8);
        x.contextFrom = c.getLong(9);
        return x;
    }

    public Conv conversation(long id) {
        try (Cursor c = db.sql.rawQuery("SELECT " + CONV_COLS + " FROM conv WHERE id=?", new String[]{String.valueOf(id)})) { return c.moveToFirst() ? readConv(c) : null; }
    }

    /** Removes the session with its items and runs. Items and runs are deleted in the same transaction. */
    public void deleteConversation(long id) {
        String[] a = {String.valueOf(id)};
        db.sql.beginTransaction();
        try {
            db.sql.delete("item", "conv_id=?", a);
            db.sql.delete("run", "conv_id=?", a);
            db.sql.delete("conv", "id=?", a);
            db.sql.setTransactionSuccessful();
        } finally { db.sql.endTransaction(); }
        synchronized (this) { if (active == id) active = 0; }
    }

    /**
     * Hides everything above a divider from the model while keeping it visible in the chat. The divider is the first
     * item of the new context, so context_from_seq is its seq and all earlier items are excluded from history().
     */
    public long clearContext(long convId) {
        db.sql.beginTransaction();
        try {
            ChatItem d = new ChatItem();
            d.convId = convId; d.kind = ChatItem.Kind.SYSTEM; d.text = CONTEXT_CLEARED;
            add(d);
            db.sql.execSQL("UPDATE conv SET context_from_seq=? WHERE id=?", new Object[]{d.seq, convId});
            db.sql.setTransactionSuccessful();
            return d.seq;
        } finally { db.sql.endTransaction(); }
    }

    /** Lowest seq that the model may see for this session (0 = everything). */
    public long contextFrom(long convId) {
        try (Cursor c = db.sql.rawQuery("SELECT context_from_seq FROM conv WHERE id=?", new String[]{String.valueOf(convId)})) { return c.moveToFirst() ? c.getLong(0) : 0; }
    }

    /** User messages of the session that the model can see (after the last context clear). */
    public int userTurns(long convId) {
        try (Cursor c = db.sql.rawQuery("SELECT COUNT(*) FROM item WHERE conv_id=? AND kind='USER' AND seq>=(SELECT context_from_seq FROM conv WHERE id=?)",
            new String[]{String.valueOf(convId), String.valueOf(convId)})) { return c.moveToFirst() ? c.getInt(0) : 0; }
    }

    public int itemCount(long convId) {
        try (Cursor c = db.sql.rawQuery("SELECT item_count FROM conv WHERE id=?", new String[]{String.valueOf(convId)})) { return c.moveToFirst() ? c.getInt(0) : 0; }
    }

    // ---- items ----------------------------------------------------------------------------------------------------
    private static ContentValues values(ChatItem it) {
        ContentValues v = new ContentValues();
        v.put("conv_id", it.convId); v.put("kind", it.kind.name()); v.put("ts", it.ts); v.put("run_id", it.runId); v.put("text", it.text);
        JSONObject x = new JSONObject();
        J.put(x, "source", it.source);
        if (!it.attachments.isEmpty()) {
            JSONArray a = new JSONArray();
            for (ChatItem.Attachment at : it.attachments) a.put(J.put(J.put(J.put(J.put(J.put(new JSONObject(), "path", at.path), "name", at.name), "mime", at.mime), "size", at.size), "dur", at.durationText));
            J.put(x, "attachments", a);
        }
        if (!it.toolCallsJson.isEmpty()) J.put(x, "tool_calls", it.toolCallsJson);
        if (!it.model.isEmpty()) J.put(x, "model", it.model);
        if (it.kind == ChatItem.Kind.TOOL) {
            J.put(x, "call_id", it.callId); J.put(x, "name", it.toolName); J.put(x, "args", it.toolArgs); J.put(x, "summary", it.summary);
            J.put(x, "ok", it.ok); J.put(x, "dur", it.durationMs); J.put(x, "status", it.toolStatus.name());
        }
        v.put("extra", x.toString());
        return v;
    }

    private static ChatItem read(Cursor c) {
        ChatItem it = new ChatItem();
        it.seq = c.getLong(0); it.convId = c.getLong(1);
        try { it.kind = ChatItem.Kind.valueOf(c.getString(2)); } catch (Exception e) { it.kind = ChatItem.Kind.SYSTEM; }
        it.ts = c.getLong(3); it.runId = c.getString(4) == null ? "" : c.getString(4); it.text = c.getString(5) == null ? "" : c.getString(5);
        JSONObject x = J.parseOrNull(c.getString(6));
        if (x != null) {
            it.source = J.str(x, "source", "text");
            JSONArray a = J.arr(x, "attachments");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                ChatItem.Attachment at = new ChatItem.Attachment();
                at.path = J.str(o, "path"); at.name = J.str(o, "name"); at.mime = J.str(o, "mime"); at.size = J.lng(o, "size", 0); at.durationText = J.str(o, "dur");
                it.attachments.add(at);
            }
            it.toolCallsJson = J.str(x, "tool_calls"); it.model = J.str(x, "model");
            it.callId = J.str(x, "call_id"); it.toolName = J.str(x, "name"); it.toolArgs = J.str(x, "args"); it.summary = J.str(x, "summary");
            it.ok = J.bool(x, "ok", false); it.durationMs = J.lng(x, "dur", 0);
            try { it.toolStatus = ChatItem.ToolStatus.valueOf(J.str(x, "status", "DONE")); } catch (Exception e) { it.toolStatus = ChatItem.ToolStatus.DONE; }
        }
        return it;
    }

    private static final String COLS = "seq,conv_id,kind,ts,run_id,text,extra";

    /** Stores the item; the session's counter, last preview and update time are maintained in the same transaction. */
    public long add(ChatItem it) {
        if (it.ts == 0) it.ts = System.currentTimeMillis();
        db.sql.beginTransaction();
        try {
            it.seq = db.sql.insert("item", null, values(it));
            db.sql.execSQL("UPDATE conv SET updated=?, item_count=item_count+1 WHERE id=?", new Object[]{it.ts, it.convId});
            refreshPreview(it);
            db.sql.setTransactionSuccessful();
        } finally { db.sql.endTransaction(); }
        return it.seq;
    }

    public void update(ChatItem it) {
        db.sql.beginTransaction();
        try {
            db.sql.update("item", values(it), "seq=?", new String[]{String.valueOf(it.seq)});
            refreshPreview(it);
            db.sql.setTransactionSuccessful();
        } finally { db.sql.endTransaction(); }
    }

    /** The preview is the newest non-empty user or assistant text, recomputed from the table so it never goes stale. */
    private void refreshPreview(ChatItem it) {
        if (it.kind != ChatItem.Kind.USER && it.kind != ChatItem.Kind.ASSISTANT) return;
        db.sql.execSQL("UPDATE conv SET last_preview=(SELECT replace(substr(text,1,120),char(10),' ') FROM item WHERE conv_id=? AND kind IN ('USER','ASSISTANT') AND text<>'' ORDER BY seq DESC LIMIT 1) WHERE id=?",
            new Object[]{it.convId, it.convId});
    }

    /** Oldest-first page ending at beforeSeq (exclusive; 0 = newest), at most limit rows. */
    public List<ChatItem> items(long convId, long beforeSeq, int limit) { return page(convId, beforeSeq, 0, limit); }

    private List<ChatItem> page(long convId, long beforeSeq, long minSeq, int limit) {
        List<ChatItem> out = new ArrayList<>();
        String sql = "SELECT " + COLS + " FROM item WHERE conv_id=? AND seq>=" + minSeq + (beforeSeq > 0 ? " AND seq<" + beforeSeq : "")
            + " ORDER BY seq DESC LIMIT " + Math.max(1, limit);
        try (Cursor c = db.sql.rawQuery(sql, new String[]{String.valueOf(convId)})) { while (c.moveToNext()) out.add(0, read(c)); }
        return out;
    }

    public ChatItem find(long seq) {
        try (Cursor c = db.sql.rawQuery("SELECT " + COLS + " FROM item WHERE seq=?", new String[]{String.valueOf(seq)})) { return c.moveToFirst() ? read(c) : null; }
    }

    /** Closes tool items left RUNNING by a crash/kill. Returns how many. */
    public int recoverInterrupted() {
        List<ChatItem> stuck = new ArrayList<>();
        try (Cursor c = db.sql.rawQuery("SELECT " + COLS + " FROM item WHERE kind='TOOL' AND extra LIKE '%\"status\":\"RUNNING\"%'", null)) { while (c.moveToNext()) stuck.add(read(c)); }
        for (ChatItem it : stuck) {
            it.toolStatus = ChatItem.ToolStatus.INTERRUPTED;
            it.ok = false;
            it.summary = "прервано перезапуском";
            it.text = J.put(J.put(new JSONObject(), "ok", false), "error",
                "Выполнение прервано перезапуском приложения. Результат неизвестен: проверьте состояние, прежде чем повторять действие с побочными эффектами.").toString();
            update(it);
        }
        db.sql.execSQL("UPDATE run SET status='INTERRUPTED', ended=" + System.currentTimeMillis() + " WHERE status='RUNNING'");
        return stuck.size();
    }

    // ---- search ---------------------------------------------------------------------------------------------------
    private static final int FALLBACK_SCAN_ROWS = 3000;

    /**
     * Matches in user and assistant texts of all visible chats (archived included), newest first. Tool output is never
     * searched. days > 0 limits the search to the last N days. Uses the FTS index when the database has one.
     */
    public List<SearchHit> search(String query, int limit, int days) {
        List<String> terms = SearchQuery.terms(query);
        List<SearchHit> out = new ArrayList<>();
        if (terms.isEmpty() || limit <= 0) return out;
        long since = days > 0 ? System.currentTimeMillis() - days * 86_400_000L : 0;
        if (db.fts) {
            StringBuilder sql = new StringBuilder("SELECT i.seq, i.conv_id, c.title, i.ts, i.kind, i.text FROM item_fts"
                + " JOIN item i ON i.seq = item_fts.rowid JOIN conv c ON c.id = i.conv_id WHERE item_fts MATCH ? AND c.hidden = 0");
            List<String> args = new ArrayList<>();
            args.add(SearchQuery.ftsMatch(terms));
            if (since > 0) { sql.append(" AND i.ts >= ?"); args.add(String.valueOf(since)); }
            sql.append(" ORDER BY i.ts DESC, i.seq DESC LIMIT ").append(limit);
            try (Cursor c = db.sql.rawQuery(sql.toString(), args.toArray(new String[0]))) {
                while (c.moveToNext()) out.add(hit(c, terms));
            }
            return out;
        }
        StringBuilder sql = new StringBuilder("SELECT i.seq, i.conv_id, c.title, i.ts, i.kind, i.text FROM item i"
            + " JOIN conv c ON c.id = i.conv_id WHERE c.hidden = 0 AND i.kind IN ('USER','ASSISTANT') AND i.text <> ''");
        List<String> args = new ArrayList<>();
        if (since > 0) { sql.append(" AND i.ts >= ?"); args.add(String.valueOf(since)); }
        sql.append(" ORDER BY i.ts DESC, i.seq DESC LIMIT ").append(FALLBACK_SCAN_ROWS);
        try (Cursor c = db.sql.rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (c.moveToNext() && out.size() < limit) {
                if (SearchQuery.matches(c.getString(5), terms)) out.add(hit(c, terms));
            }
        }
        return out;
    }

    private static SearchHit hit(Cursor c, List<String> terms) {
        String text = c.getString(5) == null ? "" : c.getString(5);
        return new SearchHit(c.getLong(1), c.getString(2), c.getLong(0), c.getLong(3),
            "USER".equals(c.getString(4)) ? "user" : "assistant", SearchSnippet.around(text, terms, SearchSnippet.RADIUS));
    }

    // ---- model protocol -------------------------------------------------------------------------------------------
    /** Messages for the next model request: oldest→newest, tool pairs repaired, trimmed to maxChars (system prompt not included). */
    public List<ChatMessage> history(long convId, int maxChars) {
        List<ChatItem> items = page(convId, 0, contextFrom(convId), 400);
        List<ChatMessage> msgs = new ArrayList<>();
        Set<String> declared = new HashSet<>();
        for (ChatItem it : items) {
            switch (it.kind) {
                case USER: {
                    StringBuilder t = new StringBuilder(it.text);
                    for (ChatItem.Attachment a : it.attachments)
                        t.append("\n[Вложение: ").append(a.path).append(" — ").append(a.mime).append(", ").append(humanSize(a.size)).append(a.durationText.isEmpty() ? "" : ", " + a.durationText).append("]");
                    msgs.add(ChatMessage.user(t.toString()));
                    break;
                }
                case ASSISTANT: {
                    List<ToolCall> calls = new ArrayList<>();
                    if (!it.toolCallsJson.isEmpty()) {
                        try {
                            JSONArray a = new JSONArray(it.toolCallsJson);
                            for (int i = 0; i < a.length(); i++) { ToolCall tc = ToolCall.fromJson(a.getJSONObject(i)); calls.add(tc); declared.add(tc.id); }
                        } catch (Exception ignored) { }
                    }
                    if (!it.text.isEmpty() || !calls.isEmpty()) msgs.add(ChatMessage.assistant(it.text, calls));
                    break;
                }
                case TOOL: {
                    String body = it.text.isEmpty() ? "{\"ok\":false,\"error\":\"результат неизвестен\"}" : it.text;
                    msgs.add(ChatMessage.tool(it.callId, it.toolName, body));
                    break;
                }
                case SYSTEM:
                    if (it.text.startsWith(SUMMARY_PREFIX)) msgs.add(ChatMessage.system(it.text));
                    break;
                default: break;
            }
        }
        return ContextBudget.trim(ContextBudget.repairPairing(msgs), maxChars);
    }

    public static String humanSize(long b) {
        if (b < 1024) return b + " Б";
        if (b < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f КБ", b / 1024.0);
        return String.format(java.util.Locale.US, "%.1f МБ", b / 1048576.0);
    }

    // ---- runs -----------------------------------------------------------------------------------------------------
    public void runStarted(String id, long convId, String model, String provider, String userText) {
        ContentValues v = new ContentValues();
        v.put("id", id); v.put("conv_id", convId); v.put("started", System.currentTimeMillis()); v.put("status", "RUNNING"); v.put("model", model);
        v.put("provider", provider); v.put("user_text", userText);
        db.sql.insertWithOnConflict("run", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void runFinished(String id, String status, int steps, int toolCalls, long firstTextMs, long totalMs, String error) {
        ContentValues v = new ContentValues();
        v.put("ended", System.currentTimeMillis()); v.put("status", status); v.put("steps", steps); v.put("tool_calls", toolCalls);
        v.put("first_text_ms", firstTextMs); v.put("total_ms", totalMs); v.put("error", error);
        db.sql.update("run", v, "id=?", new String[]{id});
    }
}
