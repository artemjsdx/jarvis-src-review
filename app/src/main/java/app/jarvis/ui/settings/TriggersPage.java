package app.jarvis.ui.settings;

import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.agent.Triggers;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import java.io.File;
import java.io.IOException;
import java.util.List;

/** «Фразы-триггеры»: «когда говорю X — сделай Y». A direct trigger runs without the model; an LLM one is a hint to it. */
public class TriggersPage extends SettingsPage {
    static final String[] MODE_LABELS = {"Готовое действие, без нейросети", "Через нейросеть"};
    static final String[] ACTION_HELP = {"morning — приветствие с любимой песней", "music.playFavorite — включить любимую песню",
        "app.open:<раздел> — открыть раздел, например app.open:log", "tools.call:<имя>:<json> — вызвать инструмент, например tools.call:music_stop:{}"};

    private LinearLayout list;

    public TriggersPage(Host host) { super(host); }

    @Override public String title() { return "Фразы-триггеры"; }

    /** One-line status: how many triggers are on. */
    public static String statusLine(app.jarvis.rt.Jarvis j) {
        int on = 0, all = 0;
        for (Triggers.Trigger t : Triggers.load(file(j)).all()) { all++; if (t.enabled) on++; }
        return all == 0 ? "нет триггеров" : on + " из " + all + " включено";
    }

    static File file(app.jarvis.rt.Jarvis j) { return new File(j.home, app.jarvis.rt.TriggerTools.FILE); }

    @Override protected View build() {
        ScrollView sv = Ui.scroll(ctx);
        sv.setFillViewport(true);
        LinearLayout content = Ui.column(ctx);
        content.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(24));

        LinearLayout how = Widgets.card(ctx);
        how.addView(Ui.text(ctx, "Как это работает", Ui.BODY, Ui.P.text), Ui.lpm(-1, -2, 14, 12, 14, 4));
        how.addView(PageUi.caption(ctx, "Быстрый путь без нейросети — мгновенно, но только для готовых действий: приветствие, музыка, открыть раздел, вызов инструмента. "
            + "Через нейросеть — гибче: модель понимает фразу и выполняет инструкцию, это на секунду дольше. "
            + "Фраза срабатывает, если команда начинается с неё или совпадает целиком; обращение «Джарвис» перед фразой не мешает."), Ui.lpm(-1, -2, 14, 0, 14, 12));
        content.addView(how);

        content.addView(Ui.button(ctx, "Новый триггер", R.drawable.ic_plus, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() {
            edit(null);
        } }), Ui.lpm(-1, -2, 0, 14, 0, 0));

        content.addView(Widgets.sectionTitle(ctx, "Триггеры"));
        list = Ui.column(ctx);
        content.addView(list);

        sv.addView(content);
        return sv;
    }

    @Override public void onShow() { refresh(); }

    private void refresh() {
        if (list == null) return;
        list.removeAllViews();
        final List<Triggers.Trigger> all = Triggers.load(file(j)).all();
        if (all.isEmpty()) {
            list.addView(PageUi.note(ctx, "Пока пусто. Добавьте фразу или попросите ассистента создать триггер в чате."));
            return;
        }
        LinearLayout card = Widgets.card(ctx);
        for (int i = 0; i < all.size(); i++) {
            final Triggers.Trigger t = all.get(i);
            final String phrases = join(t.phrases);
            View row = Widgets.row(ctx, t.direct() ? R.drawable.ic_bolt : R.drawable.ic_bot, phrases, describe(t),
                Widgets.toggle(ctx, t.enabled, new Widgets.Toggle.Listener() { @Override public void onChanged(boolean on) {
                    Triggers all2 = Triggers.load(file(j));
                    all2.upsert(t.id, t.phrases, t.mode, t.action, t.instruction, on);
                    save(all2);
                } }), new Runnable() { @Override public void run() { edit(t); } });
            card.addView(row);
            if (i < all.size() - 1) Widgets.divide(card);
        }
        list.addView(card);
    }

    static String describe(Triggers.Trigger t) {
        return t.direct() ? "без нейросети · " + t.action : "через нейросеть · " + t.instruction;
    }

    private static String join(List<String> ps) {
        StringBuilder b = new StringBuilder();
        for (String p : ps) { if (b.length() > 0) b.append(" · "); b.append(p); }
        return b.toString();
    }

    private void save(Triggers t) {
        try { t.save(file(j)); } catch (IOException e) { host.overlays().toast("Не сохранилось: " + e.getMessage(), 2); }
        refresh();
    }

    /** Add (t == null) or edit a trigger in a sheet. */
    private void edit(final Triggers.Trigger t) {
        LinearLayout c = Ui.column(ctx);
        final EditText phrases = Widgets.field(ctx, "например: включи радио | радио", t == null ? "" : join(t.phrases), false, false);
        c.addView(caption("Фразы (через «|»)"));
        c.addView(phrases, Ui.lpm(-1, -2, 0, 4, 0, 10));

        final int[] mode = {t == null || t.direct() ? 0 : 1};
        final TextView modeView = Ui.text(ctx, MODE_LABELS[mode[0]], Ui.BODY, Ui.P.text);
        c.addView(caption("Как выполнять"));
        c.addView(Ui.button(ctx, "Выбрать", R.drawable.ic_sliders, Ui.BTN_GHOST, new Runnable() { @Override public void run() {
            Dialogs.choose(host, "Как выполнять", MODE_LABELS, mode[0], new Dialogs.IndexListener() { @Override public void on(int index) {
                mode[0] = index;
                modeView.setText(MODE_LABELS[index]);
            } });
        } }), Ui.lpm(-2, -2, 0, 4, 0, 6));
        c.addView(modeView, Ui.lpm(-1, -2, 0, 0, 0, 10));

        final EditText action = Widgets.field(ctx, "morning", t == null ? "" : t.action, false, false);
        final EditText instruction = Widgets.field(ctx, "например: спроси, нужен ли перерыв", t == null ? "" : t.instruction, false, false);
        c.addView(caption("Действие (без нейросети)"));
        c.addView(action, Ui.lpm(-1, -2, 0, 4, 0, 4));
        c.addView(PageUi.note(ctx, join(java.util.Arrays.asList(ACTION_HELP))), Ui.lpm(-1, -2, 0, 0, 0, 10));
        c.addView(caption("Инструкция (через нейросеть)"));
        c.addView(instruction, Ui.lpm(-1, -2, 0, 4, 0, 10));

        final boolean[] on = {t == null || t.enabled};
        c.addView(Widgets.row(ctx, R.drawable.ic_activity, "Включён", null,
            Widgets.toggle(ctx, on[0], new Widgets.Toggle.Listener() { @Override public void onChanged(boolean v) { on[0] = v; } }), null), Ui.lpm(-1, -2, 0, 0, 0, 10));

        final OverlayHost.Layer[] sheet = {null};
        LinearLayout buttons = Ui.row(ctx);
        buttons.addView(Ui.button(ctx, "Отмена", 0, Ui.BTN_GHOST, new Runnable() { @Override public void run() { host.overlays().dismiss(sheet[0]); } }), Ui.lpw(1f));
        if (t != null) buttons.addView(Ui.button(ctx, "Удалить", R.drawable.ic_trash, Ui.BTN_DANGER, new Runnable() { @Override public void run() {
            Triggers all = Triggers.load(file(j));
            all.remove(t.id);
            save(all);
            host.overlays().dismiss(sheet[0]);
        } }), Ui.lpw(1f));
        buttons.addView(Ui.button(ctx, "Сохранить", 0, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() {
            String err = validate(phrases.getText().toString(), mode[0], action.getText().toString().trim(), instruction.getText().toString().trim());
            if (err != null) { host.overlays().toast(err, 2); return; }
            Triggers all = Triggers.load(file(j));
            all.upsert(t == null ? "" : t.id, Triggers.splitPhrases(phrases.getText().toString()), mode[0] == 1 ? Triggers.Mode.LLM : Triggers.Mode.DIRECT,
                mode[0] == 1 ? "" : action.getText().toString().trim(), mode[0] == 1 ? instruction.getText().toString().trim() : "", on[0]);
            save(all);
            host.overlays().dismiss(sheet[0]);
        } }), Ui.lpw(1f));
        c.addView(buttons, Ui.lpm(-1, -2, 0, 4, 0, 0));

        sheet[0] = Dialogs.sheet(host, t == null ? "Новый триггер" : "Триггер", c, true);
    }

    /** Null when the trigger can be saved; otherwise the message for the user. */
    static String validate(String phrasesText, int mode, String action, String instruction) {
        List<String> ph = Triggers.splitPhrases(phrasesText);
        if (ph.isEmpty()) return "Укажите фразу";
        if (ph.size() > Triggers.MAX_PHRASES) return "Фраз не больше " + Triggers.MAX_PHRASES;
        if (mode == 1) return instruction.isEmpty() ? "Напишите инструкцию для нейросети" : null;
        return Triggers.checkAction(action, null);
    }

    private TextView caption(String s) { return PageUi.caption(ctx, s); }
}
