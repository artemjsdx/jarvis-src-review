package app.jarvis.dev.scenes;

import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import app.jarvis.data.ChatItem;
import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.Host;
import app.jarvis.ui.Shell;
import app.jarvis.ui.Ui;
import app.jarvis.ui.chat.drawer.SessionsDrawer;

/**
 * Sessions drawer on the real Agent screen (closed, open, mid-animation, search, empty search, action sheet) and the
 * panel alone in dark and light. Sessions are written through the real ChatStore with fixed item times.
 */
public final class SessionsDrawerScenes {
    private SessionsDrawerScenes() { }

    private static final long DAY = 86_400_000L, HOUR = 3_600_000L, MIN = 60_000L;

    public static void render() throws Exception {
        Ui.init(PreviewHost.ctx, true);
        Jarvis j = PreviewKit.jarvis();
        seed(j);

        Shell s = PreviewKit.shell("agent");
        PreviewHost.pump(200);
        PreviewHost.render(s, "drawer_agent_closed");

        SessionsDrawer d = find(s);
        d.open();
        d.snapTo(1f);
        PreviewHost.pump(500);
        PreviewHost.render(s, "drawer_agent_open_dark");

        d.snapTo(0.5f);
        PreviewHost.pump(60);
        PreviewHost.render(s, "drawer_agent_mid50");

        d.open();
        d.snapTo(1f);
        PreviewHost.pump(400);
        typeSearch(s, "план");
        PreviewHost.render(s, "drawer_search_results");
        typeSearch(s, "zzzz");
        PreviewHost.render(s, "drawer_search_empty");
        typeSearch(s, "");

        findByDescription(s, "Действия с чатом").performClick();
        PreviewHost.pump(800);
        PreviewHost.render(s, "drawer_action_sheet");

        standalone(true, "drawer_panel_dark");
        standalone(false, "drawer_panel_light");
        Ui.init(PreviewHost.ctx, true);
    }

    private static void standalone(boolean dark, String name) throws Exception {
        Ui.init(PreviewHost.ctx, dark);
        Host h = PreviewKit.host();
        FrameLayout bg = new FrameLayout(PreviewHost.ctx);
        bg.setBackgroundColor(Ui.P.bg0);
        SessionsDrawer d = new SessionsDrawer(h, new SessionsDrawer.Actions() {
            @Override public void newChat() { }
            @Override public void clearContext() { }
            @Override public void voiceIn(long convId) { }
        });
        bg.addView(d, new FrameLayout.LayoutParams(-1, -1));
        d.setInsets(PreviewHost.px(30), PreviewHost.px(24));
        d.open();
        d.snapTo(1f);
        PreviewHost.pump(500);
        PreviewHost.render(bg, name, 392, 820);
    }

    private static void typeSearch(View root, String text) {
        EditText e = (EditText) findByHint(root, "Поиск по чатам");
        e.setText(text);
        PreviewHost.pump(400);
    }

    private static SessionsDrawer find(View v) {
        if (v instanceof SessionsDrawer) return (SessionsDrawer) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                SessionsDrawer d = find(g.getChildAt(i));
                if (d != null) return d;
            }
        }
        return null;
    }

    private static View findByHint(View v, String hint) {
        if (v instanceof EditText && hint.equals(((EditText) v).getHint() == null ? "" : ((EditText) v).getHint().toString())) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findByHint(g.getChildAt(i), hint);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static View findByDescription(View v, String desc) {
        if (desc.equals(v.getContentDescription() == null ? "" : v.getContentDescription().toString())) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findByDescription(g.getChildAt(i), desc);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static void seed(Jarvis j) {
        long now = System.currentTimeMillis();
        long a = session(j, "План запуска продукта", now - 2 * HOUR, 6, "Согласовал даты: релиз в ноябре, тесты до пятницы.");
        j.sessions.pin(a, true);
        long b = session(j, "Заметки по архитектуре", now - DAY, 4, "Вынесли хранилище сессий в отдельный слой.");
        j.sessions.pin(b, true);
        session(j, "Покупки на неделю", now - 5 * HOUR, 3, "Хлеб, молоко, яйца, гречка.");
        session(j, "Разбор логов Termux: почему сервис в фоне останавливается через двадцать минут простоя", now - 8 * HOUR, 8,
            "Причина — оптимизация батареи для приложения; нужно внести в исключения.");
        j.chat.newConversation("Новый диалог");
        session(j, "Перевод письма клиенту", now - DAY - 9 * HOUR, 5, "Перевёл абзацы про сроки и оплату.");
        session(j, "Идеи для Джарвиса", now - 3 * DAY, 7, "Можно добавить голосовые заметки и напоминания по геолокации.");
        session(j, "Погода на выходные", now - 14 * DAY, 2, "В субботу облачно, в воскресенье дождь.");
        session(j, "Список книг", now - 60 * DAY, 9, "Три книги про архитектуру ПО в очереди.");
        session(j, "Настройка будильника", now - 5 * DAY, 4, "Будильник на семь тридцать по будням.");
        long archived = session(j, "Бюджет на квартал", now - 20 * DAY, 4, "Лимит на сервисы — сорок долларов в месяц.");
        j.sessions.archive(archived, true);
        session(j, "mcp-test-run-17", now - HOUR, 2, "тестовая сессия моста");
        j.sessions.select(a);
    }

    private static long session(Jarvis j, String title, long ts, int items, String lastText) {
        long id = j.chat.newConversation(title);
        for (int i = 0; i < items; i++) {
            ChatItem it = new ChatItem();
            it.convId = id;
            it.kind = i % 2 == 0 ? ChatItem.Kind.USER : ChatItem.Kind.ASSISTANT;
            it.text = i == items - 1 ? lastText : "Сообщение " + (i + 1);
            it.ts = ts - (items - 1 - i) * MIN;
            it.runId = "preview";
            j.chat.add(it);
        }
        return id;
    }
}
