package app.jarvis.ui.settings;

import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.agent.PromptReport;
import app.jarvis.core.agent.Prompts;
import app.jarvis.core.tools.MemoryStore;
import app.jarvis.data.Settings;
import app.jarvis.rt.AgentRunner;
import app.jarvis.rt.Suggestions;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import java.util.List;

/** «Контекст и промпт»: what the next request contains, its size against the window, and the knobs that change it. */
public class ContextPage extends SettingsPage {
    static final int DEFAULT_HISTORY_CHARS = 60_000;

    private LinearLayout reportBox;
    private TextView summary;
    private EditText memoryField, historyField, reminderField, fullToolsField;

    public ContextPage(Host host) { super(host); }

    @Override public String title() { return "Контекст и промпт"; }

    /** One-line status for the settings list: size of the next request. */
    public static String statusLine(app.jarvis.rt.Jarvis j) {
        PromptReport r = j.agent.promptReport(j.sessions.active());
        return r.totalTokens() + " токенов · " + r.percentOfWindow() + "% окна";
    }

    @Override protected View build() {
        ScrollView sv = Ui.scroll(ctx);
        sv.setFillViewport(true);
        LinearLayout content = Ui.column(ctx);
        content.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(24));

        content.addView(Widgets.sectionTitle(ctx, "Следующий запрос к модели"));
        LinearLayout card = Widgets.card(ctx);
        summary = PageUi.caption(ctx, "считаю…");
        card.addView(summary, Ui.lpm(-1, -2, 14, 12, 14, 4));
        reportBox = Ui.column(ctx);
        card.addView(reportBox, Ui.lpm(-1, -2, 14, 0, 14, 12));
        content.addView(card);
        content.addView(PageUi.note(ctx, "Оценка: кириллица ≈ 3,2 символа на токен, латиница ≈ 4. Это не точный подсчёт токенов модели."), Ui.lpm(-1, -2, 4, 8, 4, 0));

        LinearLayout actions = Widgets.card(ctx);
        actions.addView(Widgets.row(ctx, R.drawable.ic_eye, "Показать системный промпт", "Только для чтения: то, что уходит модели первым сообщением", null, new Runnable() { @Override public void run() {
            showSystemPrompt();
        } }));
        content.addView(actions);

        content.addView(Widgets.sectionTitle(ctx, "Свой промпт"));
        LinearLayout own = Widgets.card(ctx);
        LinearLayout ownHead = Ui.row(ctx);
        TextView ownTitle = Ui.text(ctx, "Указания пользователя", Ui.BODY);
        ownTitle.setTypeface(Ui.medium);
        ownHead.addView(ownTitle, Ui.lpw(1f));
        ownHead.addView(app.jarvis.ui.HelpPopup.button(ctx, host, "Свой промпт",
            "Текст отсюда добавляется в системный промпт перед памятью: свои правила, разрешения, стиль.",
            "Пишите коротко: каждая строка оплачивается на каждом запросе.",
            "Меняется редко, поэтому кэш префикса провайдера не страдает между ходами."));
        own.addView(ownHead, Ui.lpm(-1, -2, 16, 12, 14, 6));
        final EditText userPrompt = Widgets.field(ctx, "Например: можно скачивать и слушать музыку по моим ссылкам; не спрашивай подтверждения…",
            j.settings.get("prompt.userExtra", ""), true, false);
        userPrompt.setMinLines(3);
        userPrompt.setOnFocusChangeListener(new android.view.View.OnFocusChangeListener() { @Override public void onFocusChange(android.view.View v, boolean has) {
            if (!has) { j.settings.set("prompt.userExtra", userPrompt.getText().toString().trim()); loadReport(); }
        } });
        own.addView(userPrompt, Ui.lpm(-1, -2, 16, 0, 16, 14));
        content.addView(own);

        content.addView(Widgets.sectionTitle(ctx, "Что входит в запрос"));
        LinearLayout knobs = Widgets.card(ctx);
        memoryField = numberField(MemoryStore.DEFAULT_BUDGET, "memory.budget", 200, 20_000, String.valueOf(j.settings.getInt("memory.budget", MemoryStore.DEFAULT_BUDGET)));
        knobs.addView(fieldRow(R.drawable.ic_layers, "Бюджет памяти, символов", "Сколько памяти попадает в промпт. Лишнее не отправляется.", memoryField));
        Widgets.divide(knobs);
        historyField = numberField(DEFAULT_HISTORY_CHARS, Settings.LLM_CONTEXT_CHARS, 8000, 400_000, String.valueOf(j.settings.getInt(Settings.LLM_CONTEXT_CHARS, DEFAULT_HISTORY_CHARS)));
        knobs.addView(fieldRow(R.drawable.ic_chat, "Лимит истории, символов", "Окно контекста: старые сообщения обрезаются. Та же настройка, что на странице LLM.", historyField));
        Widgets.divide(knobs);
        reminderField = numberField(AgentRunner.REMINDER_DEFAULT, AgentRunner.REMINDER_EVERY, 0, 100, String.valueOf(j.settings.getInt(AgentRunner.REMINDER_EVERY, AgentRunner.REMINDER_DEFAULT)));
        knobs.addView(fieldRow(R.drawable.ic_bolt, "Напоминание правил каждые N сообщений", "0 — выключить. Правила дописываются к сообщению пользователя, начало промпта не меняется.", reminderField));
        Widgets.divide(knobs);
        knobs.addView(toggleRow(R.drawable.ic_sliders, "Инструменты по требованию",
            "Выкл — все инструменты в каждом запросе, как раньше. Вкл — только нужные, запрос короче.",
            AgentRunner.TOOLS_GROUPS, true));
        Widgets.divide(knobs);
        fullToolsField = numberField(AgentRunner.TOOLS_FULL_DEFAULT, AgentRunner.TOOLS_FULL_EVERY, 0, 100,
            String.valueOf(j.settings.getInt(AgentRunner.TOOLS_FULL_EVERY, AgentRunner.TOOLS_FULL_DEFAULT)));
        knobs.addView(fieldRow(R.drawable.ic_layers, "Все инструменты каждые N запросов",
            "Раз в N запросов уходит полный список. 0 — никогда, 1 — в каждом.", fullToolsField));
        Widgets.divide(knobs);
        knobs.addView(toggleRow(R.drawable.ic_bug, "Отчёты бета-теста",
            "Агент молча записывает в docs/beta, чего ему не хватило или где он нашёл ошибку. В чате об этом не пишет.",
            "agent.betaReports", true));
        Widgets.divide(knobs);
        knobs.addView(Widgets.row(ctx, R.drawable.ic_folder_open, "Открыть библиотеку отчётов",
            "Файлы docs/beta: можно выделить и скопировать целиком", null, new Runnable() { @Override public void run() { j.navigate("files/docs/beta"); } }));
        Widgets.divide(knobs);
        knobs.addView(toggleRow(R.drawable.ic_sliders, "Подсказки навыков", "Короткий список подходящих навыков к запросу (skills.suggest)",
            "skills.suggest", true));
        Widgets.divide(knobs);
        knobs.addView(toggleRow(R.drawable.ic_sliders, "Подсказки на главной", "LLM предлагает быстрые действия на главном экране (home.suggestions)",
            Suggestions.ENABLED, true));
        content.addView(knobs);

        content.addView(Ui.button(ctx, "Сбросить правила к умолчанию", R.drawable.ic_refresh, Ui.BTN_GHOST, new Runnable() { @Override public void run() {
            Dialogs.confirm(host, "Сбросить настройки промпта?",
                "Вернутся значения по умолчанию: память 2 000, история 60 000, напоминание каждые 8 сообщений, инструменты по требованию включены без периодического полного списка, подсказки навыков и главной включены. Текст правил в коде не меняется.",
                "Сбросить", true, new Runnable() { @Override public void run() { resetDefaults(); } });
        } }), Ui.lpm(-1, -2, 0, 14, 0, 0));

        sv.addView(content);
        return sv;
    }

    private LinearLayout fieldRow(int icon, String title, String sub, EditText field) {
        LinearLayout r = Widgets.row(ctx, icon, title, sub, null, null);
        r.addView(field, Ui.lpm(-2, -2, 8, 0, 0, 0));
        return r;
    }

    /** Integer setting saved on Done/focus loss, clamped to [min, max]; the report is recomputed after each save. */
    private EditText numberField(final int fallback, final String key, final int min, final int max, String initial) {
        EditText e = PageUi.saveField(ctx, "", initial, new PageUi.TextListener() { @Override public void on(String s) {
            int n = Math.max(min, Math.min(max, parse(s, fallback)));
            j.settings.set(key, String.valueOf(n));
            loadReport();
        } });
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setMinWidth(Ui.dp(96));
        return e;
    }

    private View toggleRow(int icon, String title, String sub, final String key, boolean def) {
        return Widgets.row(ctx, icon, title, sub, Widgets.toggle(ctx, j.settings.getBool(key, def), new Widgets.Toggle.Listener() { @Override public void onChanged(boolean on) {
            j.settings.setBool(key, on);
            loadReport();
        } }), null);
    }

    private static int parse(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private void resetDefaults() {
        j.settings.set("memory.budget", String.valueOf(MemoryStore.DEFAULT_BUDGET));
        j.settings.set(Settings.LLM_CONTEXT_CHARS, String.valueOf(DEFAULT_HISTORY_CHARS));
        j.settings.set(AgentRunner.REMINDER_EVERY, String.valueOf(AgentRunner.REMINDER_DEFAULT));
        j.settings.setBool(AgentRunner.TOOLS_GROUPS, true);
        j.settings.setBool("agent.betaReports", true);
        j.settings.set(AgentRunner.TOOLS_FULL_EVERY, String.valueOf(AgentRunner.TOOLS_FULL_DEFAULT));
        j.settings.setBool("skills.suggest", true);
        j.settings.setBool(Suggestions.ENABLED, true);
        memoryField.setText(String.valueOf(MemoryStore.DEFAULT_BUDGET));
        historyField.setText(String.valueOf(DEFAULT_HISTORY_CHARS));
        reminderField.setText(String.valueOf(AgentRunner.REMINDER_DEFAULT));
        fullToolsField.setText(String.valueOf(AgentRunner.TOOLS_FULL_DEFAULT));
        host.overlays().toast("Настройки промпта сброшены", 1);
        loadReport();
    }

    private void showSystemPrompt() {
        TextView t = Ui.text(ctx, Prompts.system(j.agent.currentPromptInfo()), Ui.CAPTION, Ui.P.text);
        t.setTextIsSelectable(true);
        Dialogs.sheet(host, "Системный промпт", t, true);
    }

    @Override public void onShow() { loadReport(); }

    private void loadReport() {
        if (reportBox == null) return;
        final long conv = j.sessions.active();
        new Thread(new Runnable() {
            @Override public void run() {
                final PromptReport r = j.agent.promptReport(conv);
                j.main.post(new Runnable() { @Override public void run() { render(r); } });
            }
        }, "jv-context-report").start();
    }

    private void render(PromptReport r) {
        reportBox.removeAllViews();
        summary.setText(r.totalTokens() + " токенов · " + PageUi.group(r.totalChars()) + " симв. · " + r.percentOfWindow()
            + "% окна (" + PageUi.group(r.windowChars) + ")" + (r.historyDropped > 0 ? " · обрезано сообщений: " + r.historyDropped : ""));
        List<PromptReport.Row> rows = r.rows;
        for (PromptReport.Row row : rows) {
            LinearLayout line = Ui.column(ctx);
            LinearLayout head = Ui.row(ctx);
            head.addView(Ui.text(ctx, row.title, Ui.CAPTION, Ui.P.text), Ui.lpw(1f));
            head.addView(Ui.text(ctx, row.tokens + " ток.", Ui.CAPTION, Ui.P.text2));
            line.addView(head, Ui.lpm(-1, -2, 0, 4, 0, 2));
            line.addView(bar(r.fraction(row)), Ui.lpm(-1, Ui.dp(6), 0, 0, 0, 0));
            if (!row.note.isEmpty()) line.addView(PageUi.note(ctx, row.note), Ui.lpm(-1, -2, 0, 2, 0, 0));
            reportBox.addView(line, Ui.lpm(-1, -2, 0, 0, 0, 6));
        }
    }

    /** Proportional bar: accent fill over a track, the fill's weight is the share of the window. */
    private View bar(double fraction) {
        LinearLayout b = Ui.row(ctx);
        View fill = new View(ctx);
        fill.setBackground(Ui.rect(Ui.P.accent, 3));
        View rest = new View(ctx);
        rest.setBackground(Ui.rect(Ui.P.accentSoft, 3));
        float f = (float) Math.max(0.0, Math.min(1.0, fraction));
        b.addView(fill, Ui.lpw(Math.max(0.001f, f)));
        b.addView(rest, Ui.lpw(Math.max(0.001f, 1f - f)));
        return b;
    }

    @Override public void onHide() { }
}
