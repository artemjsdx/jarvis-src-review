package app.jarvis.dev.scenes;

import app.jarvis.data.ChatItem;
import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.ui.Ui;
import app.jarvis.ui.chat.ChatAdapter;
import app.jarvis.ui.chat.list.ChatListView;
import java.util.ArrayList;
import java.util.List;

/** Bubble alignment, meta-line width (model label of different lengths), long text and attachments, dark and light. */
public final class ChatBubblesScene {
    private ChatBubblesScene() { }

    public static void render() throws Exception {
        for (boolean dark : new boolean[]{true, false}) {
            Ui.init(PreviewHost.ctx, dark);
            ChatListView list = new ChatListView(PreviewHost.ctx);
            list.setBackgroundColor(Ui.P.bg0);
            ChatAdapter adapter = new ChatAdapter(PreviewKit.host(), new ChatAdapter.Actions() {
                @Override public void onSpeak(String text) { }
            }, list);
            adapter.setAll(items());
            PreviewHost.pump(60);
            PreviewHost.render(list, "bubbles_" + (dark ? "dark" : "light"), 392, 820);
        }
    }

    private static List<ChatItem> items() {
        long t = System.currentTimeMillis() - 60_000L;
        List<ChatItem> out = new ArrayList<>();
        out.add(item(ChatItem.Kind.USER, "Привет", "text", "", t));
        out.add(item(ChatItem.Kind.ASSISTANT, "Ок.", "", "DeepSeek", t + 1_000));
        out.add(item(ChatItem.Kind.USER, "Джарвис, создай заметку: купить хлеб и молоко к ужину", "voice", "", t + 2_000));
        out.add(item(ChatItem.Kind.ASSISTANT,
            "Готово. Заметка «Покупки» создана: хлеб, молоко, яйца и сыр. Если нужно, добавлю напоминание на вечер.\n\nМогу также показать список всех заметок.",
            "", "deepseek/deepseek-v4.1-flash-1017", t + 3_000));
        ChatItem withFile = item(ChatItem.Kind.USER, "Посмотри отчёт", "text", "", t + 4_000);
        ChatItem.Attachment a = new ChatItem.Attachment();
        a.name = "otchet-q3.pdf"; a.size = 284_112;
        withFile.attachments.add(a);
        out.add(withFile);
        out.add(item(ChatItem.Kind.ASSISTANT, "Да.", "", "mercury-3", t + 5_000));
        return out;
    }

    private static ChatItem item(ChatItem.Kind kind, String text, String source, String model, long ts) {
        ChatItem it = new ChatItem();
        it.seq = ts;
        it.convId = 1;
        it.kind = kind;
        it.text = text;
        it.source = source.isEmpty() ? "text" : source;
        it.model = model;
        it.ts = ts;
        it.runId = "bubbles";
        return it;
    }
}
