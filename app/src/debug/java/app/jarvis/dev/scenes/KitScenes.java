package app.jarvis.dev.scenes;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import app.jarvis.R;
import app.jarvis.dev.PreviewHost;
import app.jarvis.ui.Backdrop;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;

public final class KitScenes {
    public static void render() throws Exception {
        Context c = PreviewHost.ctx;
        FrameLayout root = new FrameLayout(c);
        root.addView(new Backdrop(c), new FrameLayout.LayoutParams(-1, -1));
        ScrollView sv = new ScrollView(c);
        LinearLayout col = Ui.column(c);
        col.setPadding(Ui.dp(16), Ui.dp(40), Ui.dp(16), Ui.dp(24));
        col.addView(Widgets.sectionTitle(c, "Подключение"));
        LinearLayout card = Widgets.card(c);
        card.addView(Widgets.row(c, R.drawable.ic_key, "API-ключ", "sk_f…9b4 (35 симв.)", null, new Runnable() { @Override public void run() { } }));
        Widgets.divide(card);
        card.addView(Widgets.row(c, R.drawable.ic_globe, "Прокси", "не используется", Widgets.pill(c, "выкл", Widgets.MUTED), null));
        Widgets.divide(card);
        card.addView(Widgets.row(c, R.drawable.ic_mic, "Режим обращения «Джарвис»", "Слушает в фоне, пока включено", Widgets.toggle(c, true, null), null));
        Widgets.divide(card);
        card.addView(Widgets.row(c, R.drawable.ic_sparkles, "Озвучка ответов", null, Widgets.toggle(c, false, null), null));
        col.addView(card);
        col.addView(Widgets.sectionTitle(c, "Генерация"));
        LinearLayout g = Widgets.card(c);
        g.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
        g.addView(Ui.text(c, "Уровень рассуждений", Ui.BODY));
        g.addView(Widgets.segmented(c, new String[]{"Выкл", "Быстро", "Средне", "Глубоко"}, 1, null), Ui.lpm(-1, -2, 0, 10, 0, 0));
        g.addView(Ui.text(c, "Температура 0,75", Ui.BODY), Ui.lpm(-2, -2, 0, 16, 0, 0));
        g.addView(Widgets.slider(c, 0.5f, 1f, 0.05f, 0.75f, null), Ui.lpm(-1, -2, 0, 4, 0, 0));
        g.addView(Widgets.fieldBox(c, Widgets.field(c, "Адрес API", "https://api.inceptionlabs.ai/v1", false, false)), Ui.lpm(-1, -2, 0, 12, 0, 0));
        col.addView(g);
        LinearLayout pills = Ui.row(c);
        pills.addView(Widgets.pill(c, "работает", Widgets.OK));
        pills.addView(Ui.space(c, 6, 1));
        pills.addView(Widgets.pill(c, "лимит", Widgets.WARN));
        pills.addView(Ui.space(c, 6, 1));
        pills.addView(Widgets.pill(c, "ошибка", Widgets.ERR));
        pills.addView(Ui.space(c, 10, 1));
        pills.addView(Widgets.spinner(c, 18));
        col.addView(pills, Ui.lpm(-2, -2, 0, 14, 0, 0));
        col.addView(Widgets.empty(c, R.drawable.ic_folder_open, "Здесь пока пусто", "Пришлите файл в чат или скачайте командой"));
        sv.addView(col);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        PreviewHost.render(root, "kit_widgets");
    }
}
