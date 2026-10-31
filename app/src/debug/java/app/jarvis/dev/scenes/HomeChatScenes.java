package app.jarvis.dev.scenes;

import android.view.View;
import android.view.ViewGroup;
import app.jarvis.ui.Ui;
import app.jarvis.ui.chat.list.ChatListView;
import app.jarvis.data.ChatItem;
import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.media.MusicOut;
import app.jarvis.rt.AState;
import app.jarvis.rt.ChatEvent;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.Shell;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Home + Agent scenes: real states through the real Jarvis runtime. The only stub is a scene-local MusicOut standing
 * in for the not-yet-attached player module (layout + button wiring only); it exists here, never in product code.
 */
public final class HomeChatScenes {
    private HomeChatScenes() { }

    public static void render() throws Exception {
        homeIdle();
        homeStates();
        homeMusic();
        agentEmpty();
        agentSeeded();
        agentLong();
        agentToolcards();
        agentStreaming();
        agentSheets();
    }

    // ---- home ------------------------------------------------------------------------------------------------------

    private static void homeIdle() throws Exception {
        Jarvis j = PreviewKit.jarvis();
        j.setVoiceState(AState.IDLE, "");
        j.setAgentState(AState.IDLE, "");
        j.lastAction("", true);
        Shell s = PreviewKit.shell("home");
        PreviewHost.pump(120);
        PreviewHost.render(s, "home_idle");
    }

    private static void homeStates() throws Exception {
        Jarvis j = PreviewKit.jarvis();
        Shell s = PreviewKit.shell("home");
        j.lastAction("Заметка «Покупки» создана", true);
        j.setVoiceState(AState.LISTENING, "");
        PreviewHost.pump(80);
        PreviewHost.render(s, "home_listening");
        j.setVoiceState(AState.IDLE, "");
        j.setAgentState(AState.THINKING, "");
        j.lastAction("fs_read: файл не найден", false);
        PreviewHost.pump(80);
        PreviewHost.render(s, "home_thinking");
        j.setAgentState(AState.IDLE, "");
        j.lastAction("", true);
    }

    private static void homeMusic() throws Exception {
        final Jarvis j = PreviewKit.jarvis();
        if (j.music == null) j.music = stubMusic();
        Shell s = PreviewKit.shell("home");
        j.musicChanged(info(MusicOut.State.PLAYING, "Svetlana — Dobroe utro.mp3", 61_000, 183_000));
        PreviewHost.pump(700);
        PreviewHost.render(s, "home_music");
        j.musicChanged(info(MusicOut.State.STOPPED, "", 0, 0));
        j.music = null;
    }

    // ---- agent -----------------------------------------------------------------------------------------------------

    private static void agentEmpty() throws Exception {
        Jarvis j = PreviewKit.jarvis();
        j.setAgentState(AState.IDLE, "");
        Shell s = PreviewKit.shell("agent");
        PreviewHost.pump(120);
        PreviewHost.render(s, "agent_empty");
    }

    private static void agentSeeded() throws Exception {
        PreviewKit.seedChat();
        Shell s = PreviewKit.shell("agent");
        PreviewHost.pump(120);
        PreviewHost.render(s, "agent_seeded");
    }

    /** ~62 items of realistic conversation over six days, then a scroll-up shot with the "вниз" affordance. */
    private static void agentLong() throws Exception {
        Jarvis j = PreviewKit.jarvis();
        long conv = j.chat.newConversation("Рабочие задачи");
        long now = System.currentTimeMillis();
        List<ChatItem> batch = new ArrayList<>();
        for (int d = 6; d >= 1; d--) {
            long day = now - d * 86_400_000L;
            for (int i = 0; i < 4; i++) batch.add(user(conv, day + i * 60_000L, "Джарвис, напомни в " + (9 + i) + ":00 проверить почту", d == 4 && i == 0));
            ChatItem t = new ChatItem();
            t.convId = conv; t.kind = ChatItem.Kind.TOOL; t.ts = day + 299_000L; t.runId = "r" + d; t.callId = "c" + d;
            t.toolName = "note_create";
            t.toolArgs = "{\"title\":\"Напоминания\",\"text\":\"9:00 почта, 10:00 почта, 11:00 почта, 12:00 почта\"}";
            t.ok = true; t.durationMs = 120 + d * 37; t.summary = "заметка docs/notes/202610" + (13 + d) + "-0900-napominaniya.md";
            t.toolStatus = ChatItem.ToolStatus.DONE;
            t.text = "{\"ok\":true,\"path\":\"docs/notes/202610" + (13 + d) + "-0900-napominaniya.md\",\"size\":128}";
            batch.add(t);
            ChatItem a = new ChatItem();
            a.convId = conv; a.kind = ChatItem.Kind.ASSISTANT; a.ts = day + 300_000L; a.runId = "r" + d; a.model = "mercury-3";
            a.text = "Готово: поставил напоминания на 9:00, 10:00, 11:00 и 12:00. Проверю почту вместе с вами и подскажу, что требует ответа в первую очередь.";
            batch.add(a);
            ChatItem e = new ChatItem();
            e.convId = conv; e.kind = ChatItem.Kind.ERROR; e.ts = day + 600_000L; e.runId = "r" + d;
            e.text = "Не удалось открыть сайт: провайдер недоступен из вашей сети (гео-блок).";
            e.toolArgs = "{\"status\":403,\"body\":\"CloudFront: access blocked from your country\",\"kind\":\"GEO_BLOCK\"}";
            batch.add(e);
            ChatItem t2 = new ChatItem();
            t2.convId = conv; t2.kind = ChatItem.Kind.TOOL; t2.ts = day + 610_000L; t2.runId = "r" + d; t2.callId = "c2" + d;
            t2.toolName = "shell_run";
            t2.toolArgs = "{\"command\":\"ping -c 2 api.example.com\",\"timeout_ms\":4000}";
            t2.ok = false; t2.durationMs = 4007; t2.summary = "ping: unknown host";
            t2.toolStatus = ChatItem.ToolStatus.FAILED;
            t2.text = "{\"ok\":false,\"error\":\"ping: unknown host api.example.com\",\"exit\":1}";
            batch.add(t2);
            ChatItem t3 = new ChatItem();
            t3.convId = conv; t3.kind = ChatItem.Kind.TOOL; t3.ts = day + 620_000L; t3.runId = "r" + d; t3.callId = "c3" + d;
            t3.toolName = "fs_write";
            t3.toolArgs = "{\"path\":\"docs/report.md\",\"text\":\"# Отчёт\\nЧерновик\"}";
            t3.toolStatus = ChatItem.ToolStatus.RUNNING;
            batch.add(t3);
            ChatItem a2 = new ChatItem();
            a2.convId = conv; a2.kind = ChatItem.Kind.ASSISTANT; a2.ts = day + 700_000L; a2.runId = "r" + d; a2.model = "mercury-3";
            a2.text = "Сайт недоступен из вашей сети — это ограничение провайдера, а не ошибка настройки. Я сохранил черновик отчёта в docs/report.md и продолжу попытки через прокси, когда вы включите его в настройках.";
            batch.add(a2);
        }
        batch.add(user(conv, now - 3_600_000L, "Джарвис, что по задачам на сегодня?", false));
        ChatItem aa = new ChatItem();
        aa.convId = conv; aa.kind = ChatItem.Kind.ASSISTANT; aa.ts = now - 3_500_000L; aa.runId = "rt"; aa.model = "mercury-3";
        aa.text = "На сегодня: четыре напоминания о почте (9:00–12:00), черновик отчёта ждёт правок в docs/report.md и одна заметка о покупках.";
        batch.add(aa);
        for (ChatItem it : batch) j.chat.add(it);
        for (ChatItem it : batch) j.fireChat(ChatEvent.Type.ADDED, it, null, it.runId);
        Shell s = PreviewKit.shell("agent");
        PreviewHost.pump(250);
        PreviewHost.render(s, "agent_long");
        scrollList(s, 3);
        PreviewHost.pump(150);
        PreviewHost.render(s, "agent_long_scrolled");
    }

    /** Fresh RUNNING tool card, expanded via a real click on its head. */
    private static void agentToolcards() throws Exception {
        Jarvis j = PreviewKit.jarvis();
        Shell s = PreviewKit.shell("agent");
        long conv = j.chat.currentConversation();
        ChatItem t = new ChatItem();
        t.convId = conv; t.kind = ChatItem.Kind.TOOL; t.runId = "rs"; t.callId = "cx";
        t.toolName = "fs_read";
        t.toolArgs = "{\"path\":\"docs/report.md\"}";
        t.toolStatus = ChatItem.ToolStatus.RUNNING;
        j.chat.add(t);
        j.fireChat(ChatEvent.Type.ADDED, t, null, "rs");
        PreviewHost.pump(200);
        View head = findByTag(s, t.seq);
        if (head != null) head.performClick();
        PreviewHost.pump(200);
        PreviewHost.render(s, "agent_toolcards");
    }

    private static void agentStreaming() throws Exception {
        Jarvis j = PreviewKit.jarvis();
        Shell s = PreviewKit.shell("agent");
        String partial = "Сайт недоступен из вашей сети — это ограничение провайдера, а не ошибка настройки. Я сохранил черновик отчёта";
        j.fireChat(ChatEvent.Type.STREAM, null, partial, "rpreview");
        PreviewHost.pump(120);
        PreviewHost.render(s, "agent_streaming");
        j.fireChat(ChatEvent.Type.STREAM_END, null, null, "rpreview");
    }

    private static void agentSheets() throws Exception {
        app.jarvis.ui.Host host = PreviewKit.host();
        Shell s = PreviewKit.shell("agent");   // navigate once; re-navigating would dismiss the sheets
        app.jarvis.ui.chat.ModelSheets.modelSheet(host);
        PreviewHost.pump(350);
        PreviewHost.render(s, "agent_sheet_models");
        host.overlays().dismissAll();
        PreviewHost.pump(100);
        host.overlays().dismissAll();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static ChatItem user(long conv, long ts, String text, boolean withAttachment) {
        ChatItem u = new ChatItem();
        u.convId = conv; u.kind = ChatItem.Kind.USER; u.ts = ts; u.text = text;
        u.source = ts % 2 == 0 ? "voice" : "text"; u.runId = "rx";
        if (withAttachment) {
            ChatItem.Attachment a = new ChatItem.Attachment();
            a.name = "otchet-q3.pdf"; a.mime = "application/pdf"; a.size = 284_112;
            u.attachments.add(a);
        }
        return u;
    }

    private static void scrollList(Shell s, final int rows) {
        findList(s, new ViewVisitor() {
            @Override public void on(View v) { v.scrollTo(0, Math.max(0, v.getScrollY() - Ui.dp(rows * 120))); }
        });
    }

    private interface ViewVisitor { void on(View v); }

    private static void findList(View v, ViewVisitor visitor) {
        if (v instanceof ChatListView) { visitor.on(v); return; }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) findList(g.getChildAt(i), visitor);
        }
    }

    private static View findByTag(View v, Object tag) {
        if (tag.equals(v.getTag())) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findByTag(g.getChildAt(i), tag);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static MusicOut.Info info(MusicOut.State st, String title, long pos, long dur) {
        MusicOut.Info i = new MusicOut.Info();
        i.state = st;
        i.title = title;
        i.positionMs = pos;
        i.durationMs = dur;
        i.index = 0;
        i.queueSize = 1;
        return i;
    }

    /** Scene-only stand-in for the real player module (not attached yet): layout + wiring testing, no product code. */
    private static MusicOut stubMusic() {
        return new MusicOut() {
            Info i = new Info();
            int calls;
            void log(String what) { System.out.println("[stub music] " + what + " (calls " + (++calls) + ")"); }
            @Override public void setListener(Listener l) { }
            @Override public void play(File file, String title) { }
            @Override public void playQueue(List<File> files, int startIndex) { }
            @Override public void pause() { log("pause"); }
            @Override public void resume() { log("resume"); }
            @Override public void stop() { log("stop"); }
            @Override public void next() { log("next"); }
            @Override public void prev() { log("prev"); }
            @Override public void seekTo(long ms) { }
            @Override public void setVolume(float v) { }
            @Override public Info info() { return i; }
            @Override public void release() { }
        };
    }
}
