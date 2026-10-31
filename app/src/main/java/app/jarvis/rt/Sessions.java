package app.jarvis.rt;

import app.jarvis.core.chat.SearchHit;
import app.jarvis.core.chat.SessionText;
import app.jarvis.data.ChatStore;
import app.jarvis.data.Settings;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * Chat sessions: the active one receives text and voice input, the choice survives restarts (ui.lastConv).
 * Listeners run on the executor given at construction (the UI passes the main thread).
 */
public final class Sessions {
    public interface Listener { void onSessionsChanged(long activeId); }

    static final String LAST_KEY = "ui.lastConv";
    private static final int MAX_TITLE = 80;

    private final ChatStore chat;
    private final Settings settings;
    private final Executor ui;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public Sessions(ChatStore chat, Settings settings, Executor ui) {
        this.chat = chat;
        this.settings = settings;
        this.ui = ui;
        long last = parse(settings.get(LAST_KEY, ""));
        if (last > 0 && chat.exists(last)) chat.setActive(last);
    }

    public void addListener(Listener l) { listeners.addIfAbsent(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public long active() { return chat.currentConversation(); }

    private volatile long focusSeq;

    /** The next load of the chat screen scrolls to this item (set when a search hit is opened). */
    public void focusItem(long seq) { focusSeq = seq; }

    public boolean hasFocusItem() { return focusSeq > 0; }

    /** Returns and clears the item to scroll to, 0 when none. */
    public long takeFocusItem() {
        long s = focusSeq;
        focusSeq = 0;
        return s;
    }

    public List<SearchHit> search(String query, int limit) { return chat.search(query, limit, 0); }

    /** A new empty session becomes active. Returns its id. */
    public synchronized long create(String title) {
        long id = chat.newConversation(title == null || title.trim().isEmpty() ? SessionText.DEFAULT_TITLE : clip(title));
        select(id);
        return id;
    }

    /** Reuses the active session when it has no messages yet, otherwise creates one. Used by "new chat" actions. */
    public synchronized long newChat() {
        long cur = active();
        if (chat.itemCount(cur) == 0) return cur;
        return create(null);
    }

    public synchronized boolean select(long id) {
        if (!chat.exists(id)) return false;
        chat.setActive(id);
        settings.set(LAST_KEY, String.valueOf(id));
        fire();
        return true;
    }

    public synchronized boolean rename(long id, String title) {
        if (title == null || title.trim().isEmpty() || !chat.exists(id)) return false;
        chat.renameConversation(id, clip(title));
        fire();
        return true;
    }

    public synchronized void pin(long id, boolean on) { chat.setFlag(id, "pinned", on); fire(); }

    public synchronized void archive(long id, boolean on) { chat.setFlag(id, "archived", on); fire(); }

    /** Deleting the active session activates the newest other visible one, or a fresh session if there is none. */
    public synchronized void delete(long id) {
        boolean wasActive = id == chat.activeId();
        chat.deleteConversation(id);
        if (wasActive) {
            List<ChatStore.Conv> rest = chat.conversations("", false, false);
            if (rest.isEmpty()) create(null);
            else select(rest.get(0).id);
        } else fire();
    }

    /** Hides everything above a divider from the model; the chat keeps showing it. Returns the divider's seq. */
    public synchronized long clearContext(long id) {
        long seq = chat.clearContext(id);
        fire();
        return seq;
    }

    /** Names a session from the first message, only while it still has the default title. */
    public void autoTitle(long id, String firstUserText) {
        if (chat.titleIfDefault(id, SessionText.title(firstUserText))) fire();
    }

    public List<ChatStore.Conv> list(String query, boolean includeHidden, boolean includeArchived) {
        return chat.conversations(query, includeHidden, includeArchived);
    }

    public ChatStore.Conv info(long id) { return chat.conversation(id); }

    private void fire() {
        final long id = chat.activeId();
        for (final Listener l : listeners) ui.execute(new Runnable() { @Override public void run() { l.onSessionsChanged(id); } });
    }

    private static String clip(String s) {
        String t = s.trim();
        return t.codePointCount(0, t.length()) <= MAX_TITLE ? t : t.substring(0, t.offsetByCodePoints(0, MAX_TITLE));
    }

    private static long parse(String s) {
        try { return Long.parseLong(s.trim()); } catch (RuntimeException e) { return 0; }
    }
}
