package app.jarvis.dev;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import app.jarvis.data.ChatItem;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.Host;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Screen;
import app.jarvis.ui.Shell;
import java.io.File;

/**
 * Builds the REAL runtime (Jarvis: settings, secrets, SQLite chat/journal, sandbox, tools) inside the headless preview
 * and wraps it in the real Shell, so scenes show real screens with real data. Demo data is written through the real
 * stores (clearly marked "preview" in the journal), never through fake classes.
 */
public final class PreviewKit {
    private PreviewKit() { }

    private static Jarvis jarvis;
    private static Shell shell;

    public static synchronized Jarvis jarvis() throws Exception {
        if (jarvis == null) {
            // fresh state for every run so scenes are deterministic
            deleteTree(new File(PreviewHost.filesDir, "sandbox"));
            for (String n : new String[]{"jarvis.db", "jarvis.db-wal", "jarvis.db-shm", "settings.json", "secrets.json", "memory.json"}) new File(PreviewHost.filesDir, n).delete();
            jarvis = Jarvis.init(PreviewHost.ctx);
            app.jarvis.Modules.attach(PreviewHost.ctx, jarvis);
        }
        return jarvis;
    }

    public static Host host() throws Exception {
        final Jarvis j = jarvis();
        return new Host() {
            @Override public Context context() { return PreviewHost.ctx; }
            @Override public Activity activity() { return null; }
            @Override public Jarvis jarvis() { return j; }
            @Override public OverlayHost overlays() { return shell.overlays(); }
            @Override public void navigate(String t) { shell.navigate(t); }
            @Override public void pickDocument(Screen r, int c, String[] m, boolean mu) { }
            @Override public void requestPermission(String p) { }
            @Override public boolean hasPermission(String p) { return true; }
            @Override public void hideKeyboard() { }
            @Override public void copyToClipboard(String l, String t) { }
            @Override public void shareFile(File f, String m) { }
            @Override public void openUrl(String u) { }
            @Override public void openBatterySettings() { }
            @Override public int bottomInset() { return PreviewHost.px(24); }
            @Override public int topInset() { return PreviewHost.px(30); }
            @Override public void startActivityForResult(Screen r, Intent i, int c) { }
        };
    }

    /** A Shell sized like a phone screen, with system-bar insets applied, navigated to {@code target}. */
    public static Shell shell(String target) throws Exception {
        if (shell == null) {
            shell = new Shell(host());
            shell.attach();
        }
        shell.setInsets(PreviewHost.px(30), PreviewHost.px(24), 0);
        // the previous screen's exit animation never finishes off-window, so it stays drawn under the next one: skip it
        int motion = app.jarvis.ui.motion.Motion.mode();
        app.jarvis.ui.motion.Motion.setMode(app.jarvis.ui.motion.Motion.OFF);
        shell.navigate(target);
        app.jarvis.ui.motion.Motion.setMode(motion);
        PreviewHost.pump(50);
        return shell;
    }

    /** Adds a short realistic conversation (user, tool card, assistant answer) through the real ChatStore. */
    public static void seedChat() throws Exception {
        Jarvis j = jarvis();
        long conv = j.chat.currentConversation();
        if (!j.chat.items(conv, 0, 1).isEmpty()) return;
        ChatItem u = new ChatItem();
        u.convId = conv; u.kind = ChatItem.Kind.USER; u.text = "Джарвис, создай заметку: купить хлеб и молоко"; u.source = "voice"; u.runId = "demo";
        j.chat.add(u);
        ChatItem a = new ChatItem();
        a.convId = conv; a.kind = ChatItem.Kind.ASSISTANT; a.runId = "demo"; a.model = "demo";
        a.toolCallsJson = "[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"note_create\",\"arguments\":\"{}\"}}]";
        j.chat.add(a);
        ChatItem t = new ChatItem();
        t.convId = conv; t.kind = ChatItem.Kind.TOOL; t.runId = "demo"; t.callId = "c1"; t.toolName = "note_create"; t.toolArgs = "{\"title\":\"Покупки\",\"text\":\"Купить хлеб и молоко\"}";
        t.ok = true; t.durationMs = 41; t.summary = "заметка docs/notes/20261028-1200-pokupki.md"; t.toolStatus = ChatItem.ToolStatus.DONE;
        t.text = "{\"ok\":true,\"path\":\"docs/notes/20261028-1200-pokupki.md\",\"size\":42}";
        j.chat.add(t);
        ChatItem r = new ChatItem();
        r.convId = conv; r.kind = ChatItem.Kind.ASSISTANT; r.runId = "demo"; r.model = "demo"; r.text = "Готово, заметка «Покупки» создана.";
        j.chat.add(r);
    }

    static void deleteTree(File f) {
        if (!f.exists()) return;
        File[] c = f.listFiles();
        if (c != null) for (File x : c) deleteTree(x);
        f.delete();
    }
}
