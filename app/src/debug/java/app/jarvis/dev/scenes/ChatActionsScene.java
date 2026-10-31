package app.jarvis.dev.scenes;

import android.view.View;
import android.widget.LinearLayout;
import app.jarvis.data.ChatItem;
import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.ui.Ui;
import app.jarvis.ui.chat.actions.ActionGroupView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Action block states (idle, running 1, running 3 in parallel, done, failed, expanded, overflow), dark and light. */
public final class ChatActionsScene {
    private ChatActionsScene() { }

    private static final class State implements ActionGroupView.State {
        final Set<Long> groups = new HashSet<>(), subs = new HashSet<>();
        @Override public boolean isGroupOpen(long key) { return groups.contains(key); }
        @Override public void setGroupOpen(long key, boolean open) { if (open) groups.add(key); else groups.remove(key); }
        @Override public boolean isSubOpen(long seq) { return subs.contains(seq); }
        @Override public void setSubOpen(long seq, boolean open) { if (open) subs.add(seq); else subs.remove(seq); }
        @Override public boolean firstSeen(long seq) { return false; }
    }

    private static final String[] NAMES = {"fs_list", "fs_read", "web_search", "shell_exec", "memory_save", "music_play", "http_fetch", "note_create", "fs_write"};

    public static void render() throws Exception {
        for (boolean dark : new boolean[]{true, false}) {
            String suffix = dark ? "dark" : "light";
            Ui.init(PreviewHost.ctx, dark);
            shot("idle_" + suffix, dark, false, tools(3, ChatItem.ToolStatus.DONE), false);
            shot("running1_" + suffix, dark, false, runningOnly(1), false);
            shot("running3_" + suffix, dark, false, runningOnly(3), false);
            shot("done_" + suffix, dark, true, tools(3, ChatItem.ToolStatus.DONE), false);
            shot("failed_" + suffix, dark, true, failedMix(), false);
            shot("expanded_" + suffix, dark, true, tools(3, ChatItem.ToolStatus.DONE), true);
            shot("many_" + suffix, dark, false, tools(9, ChatItem.ToolStatus.DONE), false);
        }
    }

    private static void shot(String name, boolean dark, boolean openGroup, List<ChatItem> run, boolean subsOpen) throws Exception {
        Ui.init(PreviewHost.ctx, dark);
        State st = new State();
        long key = run.get(0).seq;
        if (openGroup) st.groups.add(key);
        if (subsOpen) for (ChatItem t : run) st.subs.add(t.seq);
        LinearLayout col = Ui.column(PreviewHost.ctx);
        col.setBackgroundColor(Ui.P.bg0);
        col.setPadding(Ui.dp(12), Ui.dp(24), Ui.dp(12), Ui.dp(24));
        ActionGroupView g = new ActionGroupView(PreviewHost.ctx, PreviewKit.host(), st, run);
        col.addView(g, new LinearLayout.LayoutParams(-1, -2));
        PreviewHost.pump(120);
        PreviewHost.render(col, "actions_" + name, 392, 0);
    }

    private static List<ChatItem> tools(int n, ChatItem.ToolStatus st) {
        List<ChatItem> out = new ArrayList<>();
        long t = System.currentTimeMillis() - 20_000L;
        for (int i = 0; i < n; i++) {
            ChatItem it = base(t + i * 900L, NAMES[i % NAMES.length], "{\"path\":\"docs/" + i + "\"}", st);
            it.durationMs = 40L + 130L * i;
            it.summary = "готово";
            it.text = "{\"ok\":true,\"n\":" + i + "}";
            out.add(it);
        }
        return out;
    }

    private static List<ChatItem> runningOnly(int n) {
        List<ChatItem> out = new ArrayList<>();
        long t = System.currentTimeMillis() - 4_000L;
        for (int i = 0; i < n; i++) out.add(base(t + i * 300L, NAMES[i], "{\"path\":\"docs/x\"}", ChatItem.ToolStatus.RUNNING));
        return out;
    }

    private static List<ChatItem> failedMix() {
        List<ChatItem> out = new ArrayList<>();
        long t = System.currentTimeMillis() - 9_000L;
        ChatItem ok = base(t, "fs_list", "{\"path\":\"/\"}", ChatItem.ToolStatus.DONE);
        ok.durationMs = 12; ok.text = "{\"entries\":7}";
        ChatItem bad = base(t + 500, "shell_exec", "{\"cmd\":\"rm -rf /\"}", ChatItem.ToolStatus.FAILED);
        bad.durationMs = 310; bad.text = "{\"error\":\"команда запрещена\"}";
        ChatItem ok2 = base(t + 900, "web_search", "{\"q\":\"погода\"}", ChatItem.ToolStatus.DONE);
        ok2.durationMs = 1400; ok2.text = "{\"results\":3}";
        out.add(ok); out.add(bad); out.add(ok2);
        return out;
    }

    private static ChatItem base(long ts, String name, String args, ChatItem.ToolStatus st) {
        ChatItem it = new ChatItem();
        it.seq = ts;
        it.convId = 1;
        it.kind = ChatItem.Kind.TOOL;
        it.ts = ts;
        it.runId = "actions";
        it.callId = "c" + ts;
        it.toolName = name;
        it.toolArgs = args;
        it.toolStatus = st;
        return it;
    }
}
