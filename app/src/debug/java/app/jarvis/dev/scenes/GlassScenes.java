package app.jarvis.dev.scenes;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.dev.PreviewHost;
import app.jarvis.data.ChatItem;
import app.jarvis.dev.PreviewKit;
import app.jarvis.rt.ChatEvent;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.chat.list.ChatListView;
import app.jarvis.ui.Backdrop;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Shell;
import app.jarvis.ui.Ui;
import app.jarvis.ui.glass.Glass;
import app.jarvis.ui.glass.GlassHost;
import app.jarvis.ui.glass.GlassLevel;
import app.jarvis.ui.glass.LiquidIndicator;
import app.jarvis.ui.glass.SegmentedGlass;

/** Liquid glass on the real runtime: dock drop (static and mid-flight), dialog over text, floating button, segmented control. */
public final class GlassScenes {
    private GlassScenes() { }

    public static void render() throws Exception {
        Ui.reduceMotion = true;
        GlassLevel.configure("full");
        Shell s = PreviewKit.shell("home");
        PreviewHost.render(s, "glass_home");

        Ui.reduceMotion = false;
        s.navigate("log");
        PreviewHost.pump(60);
        LiquidIndicator drop = s.dockIndicator();
        for (int i = 1; i <= 3; i++) {
            drop.advance(0.08f);
            PreviewHost.render(s, "glass_dock_flow_" + i);
        }
        Ui.reduceMotion = true;

        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 14; i++) lines.append("Строка журнала ").append(i + 1).append(": голосовой запуск, ответ модели.\n");
        Dialogs.info(PreviewKit.host(), "Событие журнала", lines.toString());
        PreviewHost.pump(400);
        PreviewHost.render(s, "glass_dialog_over_log");
        s.overlays().dismissAll();
        PreviewHost.pump(300);

        PreviewKit.seedChat();
        s.navigate("agent");
        PreviewHost.pump(120);
        TextView fab = new TextView(PreviewHost.ctx);
        fab.setGravity(Gravity.CENTER);
        fab.setText("↓");
        fab.setTextSize(20);
        fab.setTextColor(Ui.P.text);
        fab.setMinWidth(Ui.dp(44));
        fab.setMinHeight(Ui.dp(44));
        Glass.floating(PreviewKit.host(), fab, Gravity.BOTTOM | Gravity.END, 0, 0, 120, 600, 22);
        PreviewHost.pump(60);
        PreviewHost.render(s, "glass_scroll_button");
        ((ViewGroup) fab.getParent()).removeView(fab);

        GlassHost host = new GlassHost(PreviewHost.ctx);
        LinearLayout text = Ui.column(PreviewHost.ctx);
        text.setPadding(Ui.dp(18), Ui.dp(40), Ui.dp(18), Ui.dp(40));
        for (int i = 0; i < 40; i++) {
            TextView t = Ui.text(PreviewHost.ctx, "Ответ ассистента " + (i + 1) + ": проверка размытия, текст должен быть виден сквозь стекло.", Ui.BODY);
            t.setTextColor(i % 3 == 0 ? Color.rgb(255, 210, 120) : Ui.P.text);
            text.addView(t, Ui.lpm(-1, -2, 0, 0, 0, 14));
        }
        host.content().addView(new Backdrop(PreviewHost.ctx), new FrameLayout.LayoutParams(-1, -1));
        host.content().addView(text, new FrameLayout.LayoutParams(-1, -1));
        SegmentedGlass seg = new SegmentedGlass(PreviewHost.ctx);
        seg.setItems(new String[]{"Все", "Инфо", "Предупр.", "Ошибки"});
        seg.setSelected(2);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, Ui.dp(48), Gravity.TOP);
        lp.leftMargin = Ui.dp(16);
        lp.rightMargin = Ui.dp(16);
        lp.topMargin = Ui.dp(70);
        host.overlay().addView(seg, lp);
        PreviewHost.render(host, "glass_segmented");
        GlassLevel.configure("reduced");
        PreviewHost.render(host, "glass_segmented_reduced");
        GlassLevel.configure("full");
        Ui.reduceMotion = true;
        renderScrollButton();
        GlassLevel.configure("auto");
    }

    /** The real scroll-to-bottom button: a long chat scrolled up, so the button is pinned over the chat's bottom-end corner. */
    private static void renderScrollButton() throws Exception {
        PreviewKit.seedChat();
        Jarvis j = PreviewKit.jarvis();
        long conv = j.chat.currentConversation();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 30; i++) {
            ChatItem it = new ChatItem();
            it.convId = conv;
            it.kind = ChatItem.Kind.USER;
            it.ts = now - (30 - i) * 60_000L;
            it.text = "Строка диалога " + (i + 1) + ": текст под кнопкой «вниз» должен размываться.";
            it.source = "text";
            it.runId = "rglass" + i;
            j.chat.add(it);
            j.fireChat(ChatEvent.Type.ADDED, it, null, it.runId);
        }
        Shell s = PreviewKit.shell("agent");
        PreviewHost.pump(800);
        scrollChatUp(s, Ui.dp(360));
        PreviewHost.pump(800);
        scrollChatUp(s, Ui.dp(360));
        PreviewHost.pump(600);
        PreviewHost.render(s, "glass_chat_scroll_button");
    }

    private static void scrollChatUp(View v, final int px) {
        if (v instanceof ChatListView) { v.scrollTo(0, Math.max(0, v.getScrollY() - px)); return; }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) scrollChatUp(g.getChildAt(i), px);
        }
    }
}
