package app.jarvis.ui.settings.llm;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.core.agent.ContextFit;
import app.jarvis.core.llm.Cap;
import app.jarvis.core.llm.LlmConfig;
import app.jarvis.core.llm.ModelCaps;
import app.jarvis.core.llm.Presets;
import app.jarvis.core.llm.ProviderPreset;
import app.jarvis.data.Settings;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;

/**
 * Generation parameters of the current model. Every control reads what the model reports: a level or parameter the catalog
 * does not list is shown as unavailable or unknown, and the reason is written under the control.
 */
public final class GenerationCard {
    static final String[] LABELS = {"Авто", "Выкл", "Низкий", "Средний", "Высокий", "Максимум", "Мгновенно"};
    static final String[] LEVEL_VALUES = {"", "off", "low", "medium", "high", "max", "instant"};
    static final int DEFAULT_MAX_TOKENS = 4000;

    private final Host host;
    private final Context c;
    private final Jarvis j;
    private final String pid;
    private final String model;
    private final ModelCaps caps;
    private final LlmConfig cfg;

    private GenerationCard(Host host) {
        this.host = host;
        this.c = host.context();
        this.j = host.jarvis();
        this.pid = j.providerId();
        this.model = j.modelFor(pid);
        this.caps = j.catalog.caps(pid, model);
        ProviderPreset p = Presets.get(pid);
        this.cfg = p == null ? new LlmConfig() : p.toConfig("");
    }

    public static LinearLayout build(Host host) { return new GenerationCard(host).card(); }

    private LinearLayout card() {
        LinearLayout card = Widgets.card(c);
        LinearLayout pad = Ui.column(c);
        pad.setPadding(Ui.dp(16), Ui.dp(6), Ui.dp(16), Ui.dp(14));
        card.addView(pad);
        reasoning(pad);
        divide(pad);
        temperature(pad);
        divide(pad);
        maxTokens(pad);
        divide(pad);
        context(pad);
        divide(pad);
        steps(pad);
        return card;
    }

    private void divide(LinearLayout pad) {
        pad.addView(Ui.divider(c), Ui.lpm(-1, Ui.dp(1), 0, Ui.dp(12), 0, Ui.dp(2)));
    }

    private LinearLayout head(String title, String help) {
        LinearLayout row = Ui.row(c);
        TextView t = Ui.text(c, title, Ui.BODY);
        t.setTypeface(Ui.medium);
        row.addView(t, Ui.lpw(1f));
        row.addView(Help.button(host, title, help));
        return row;
    }

    private static TextView caption(Context c, String s) { return Ui.text(c, s, Ui.CAPTION, Ui.P.text3); }

    private void reasoning(LinearLayout pad) {
        pad.addView(head("Уровень рассуждений", "Сколько модель думает перед ответом. Доступные уровни берутся из описания модели у провайдера. "
            + "Уровень, которого модель не принимает, не отправляется, а выбирается «Авто»."), Ui.lpm(-1, -2, 0, Ui.dp(6), 0, Ui.dp(6)));
        final String selected = j.settings.get(Settings.LLM_REASONING, "low");
        final TextView[] cells = new TextView[LEVEL_VALUES.length];
        CapChips.Flow grid = new CapChips.Flow(c);
        for (int idx = 0; idx < LEVEL_VALUES.length; idx++) {
            final int at = idx;
            TextView cell = Ui.chip(c, LABELS[idx], Ui.P.text2);
            cell.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(8));
            cell.setClickable(true);
            cells[idx] = cell;
            cell.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View w) {
                if (caps.reasoningLevel(LEVEL_VALUES[at]) == Cap.NO) {
                    host.overlays().toast("Модель «" + model + "» не поддерживает уровень «" + LABELS[at] + "»", 2);
                    return;
                }
                j.settings.set(Settings.LLM_REASONING, LEVEL_VALUES[at]);
                styleCells(cells, LEVEL_VALUES[at]);
                rebuildCaption(pad);
            } });
            grid.addView(cell);
        }
        pad.addView(grid, Ui.lpm(-1, -2, 0, Ui.dp(8), 0, 0));
        styleCells(cells, selected);
        TextView hint = caption(c, levelText(selected));
        hint.setTag("reasoning.hint");
        pad.addView(hint, Ui.lpm(-2, -2, 0, Ui.dp(6), 0, 0));
    }

    private void rebuildCaption(LinearLayout pad) {
        View v = pad.findViewWithTag("reasoning.hint");
        if (v instanceof TextView) ((TextView) v).setText(levelText(j.settings.get(Settings.LLM_REASONING, "low")));
    }

    private void styleCells(TextView[] cells, String selected) {
        int sel = indexOf(selected);
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] == null) continue;
            boolean no = caps.reasoningLevel(LEVEL_VALUES[i]) == Cap.NO;
            boolean on = i == sel;
            cells[i].setBackground(on ? Ui.rect(Ui.P.accent, 12) : Ui.rect(Ui.alpha(Ui.P.text2, 0.08f), 12));
            cells[i].setTextColor(on ? Ui.P.onAccent : (no ? Ui.alpha(Ui.P.text3, 0.55f) : Ui.P.text2));
        }
    }

    private String levelText(String level) {
        if (level.isEmpty()) return "Авто: модель сама решает, сколько думать.";
        switch (caps.reasoningLevel(level)) {
            case YES: return "Модель принимает этот уровень (по описанию провайдера).";
            case NO: return "Модель не поддерживает этот уровень: он не отправляется, используется «Авто».";
            default: return "Неизвестно, принимает ли модель этот уровень. Попробуйте: если откажет, ошибка попадёт в журнал, и уровень перестанет отправляться.";
        }
    }

    private static int indexOf(String v) {
        for (int i = 0; i < LEVEL_VALUES.length; i++) if (LEVEL_VALUES[i].equals(v)) return i;
        return 0;
    }

    private void temperature(LinearLayout pad) {
        pad.addView(head("Температура", "Насколько разнообразны ответы: меньше — точнее и ровнее, больше — свободнее. "
            + "Не все модели принимают этот параметр."), Ui.lpm(-1, -2, 0, 0, 0, Ui.dp(6)));
        String range = "диапазон провайдера: " + fmt(cfg.quirks.tempMin) + "–" + fmt(cfg.quirks.tempMax);
        if (caps.temperature == Cap.NO) {
            pad.addView(caption(c, "Модель не принимает температуру: параметр не отправляется."), Ui.lpm(-2, -2, 0, Ui.dp(4), 0, 0));
            return;
        }
        final LinearLayout block = Ui.column(c);
        pad.addView(block, Ui.lpm(-1, -2, 0, Ui.dp(4), 0, 0));
        buildTemperature(block, range);
    }

    private void buildTemperature(final LinearLayout block, final String range) {
        block.removeAllViews();
        final boolean auto = !j.settings.has(Settings.LLM_TEMPERATURE);
        LinearLayout head = Ui.row(c);
        String note = caps.temperature == Cap.YES ? "модель принимает температуру · " + range : "неизвестно, принимает ли модель · " + range;
        head.addView(caption(c, auto ? "авто · " + note : note), Ui.lpw(1f));
        head.addView(Widgets.toggle(c, auto, new Widgets.Toggle.Listener() { @Override public void onChanged(boolean on) {
            if (on) j.settings.remove(Settings.LLM_TEMPERATURE);
            else j.settings.set(Settings.LLM_TEMPERATURE, String.valueOf(Math.max(cfg.quirks.tempMin, Math.min(cfg.quirks.tempMax, 0.75))));
            buildTemperature(block, range);
        } }), Ui.lpm(-2, -2, Ui.dp(8), 0, 0, 0));
        block.addView(head);
        if (!auto) {
            final float saved = j.settings.getFloat(Settings.LLM_TEMPERATURE, 0.75f);
            final TextView val = caption(c, fmt(saved));
            block.addView(val, Ui.lpm(-2, -2, 0, Ui.dp(6), 0, 0));
            block.addView(Widgets.slider(c, (float) cfg.quirks.tempMin, (float) cfg.quirks.tempMax, 0.05f, saved, new Widgets.SliderListener() { @Override public void onValue(float v, boolean fromUser) {
                val.setText(fmt(v));
                j.settings.set(Settings.LLM_TEMPERATURE, String.valueOf(v));
            } }), Ui.lpm(-1, Ui.dp(32), 0, 0, 0, 0));
        }
        if (caps.temperature == Cap.UNKNOWN) block.addView(caption(c, "Если модель не примет параметр, ошибка попадёт в журнал, и он перестанет отправляться."),
            Ui.lpm(-2, -2, 0, Ui.dp(4), 0, 0));
    }

    private static String fmt(double t) {
        String s = String.format(java.util.Locale.US, "%.2f", t);
        if (s.endsWith(".00")) return s.substring(0, s.length() - 3);
        if (s.endsWith("0")) return s.substring(0, s.length() - 1);
        return s;
    }

    private void maxTokens(LinearLayout pad) {
        pad.addView(head("Максимум токенов ответа", "Сколько токенов модель может написать за один ответ. Значение больше лимита модели "
            + "уменьшается до лимита, чтобы провайдер не отклонил запрос."), Ui.lpm(-1, -2, 0, 0, 0, Ui.dp(6)));
        final EditText field = Widgets.field(c, String.valueOf(DEFAULT_MAX_TOKENS), String.valueOf(j.settings.getInt(Settings.LLM_MAX_TOKENS, DEFAULT_MAX_TOKENS)), false, false);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        final TextView note = caption(c, "");
        field.setOnFocusChangeListener(new android.view.View.OnFocusChangeListener() { @Override public void onFocusChange(View v, boolean has) {
            if (!has) save(field, note);
        } });
        field.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) { save(field, note); return false; }
        });
        pad.addView(Widgets.fieldBox(c, field), Ui.lpm(-1, -2, 0, Ui.dp(4), 0, 0));
        note.setText(limitText());
        pad.addView(note, Ui.lpm(-2, -2, 0, Ui.dp(4), 0, 0));
    }

    private String limitText() {
        return caps.maxOutputTokens > 0 ? "Лимит модели: " + CapChips.tokens(caps.maxOutputTokens) + " токенов."
            : "Лимит модели провайдер не сообщает: значение отправляется как есть.";
    }

    private void save(EditText field, TextView note) {
        int v;
        try { v = Integer.parseInt(field.getText().toString().trim()); } catch (NumberFormatException e) { v = -1; }
        if (v < 16 || v > 200_000) {
            host.overlays().toast("Введите число от 16 до 200 000", 2);
            field.setText(String.valueOf(j.settings.getInt(Settings.LLM_MAX_TOKENS, DEFAULT_MAX_TOKENS)));
            return;
        }
        if (caps.maxOutputTokens > 0 && v > caps.maxOutputTokens) {
            v = caps.maxOutputTokens;
            host.overlays().toast("Ограничено лимитом модели: " + CapChips.tokens(v), 0);
            field.setText(String.valueOf(v));
        }
        j.settings.set(Settings.LLM_MAX_TOKENS, String.valueOf(v));
        note.setText(limitText());
    }

    private void context(LinearLayout pad) {
        pad.addView(head("Размер контекста", "Сколько истории разговора отправляется модели. «Авто» берёт окно модели из её описания "
            + "(не больше 200 тыс. токенов) и оставляет место для ответа. Ручное значение можно задать, но оно не больше окна модели."),
            Ui.lpm(-1, -2, 0, 0, 0, Ui.dp(6)));
        final boolean auto = j.settings.getBool(Settings.LLM_CONTEXT_AUTO, true);
        final LinearLayout block = Ui.column(c);
        pad.addView(block, Ui.lpm(-1, -2, 0, Ui.dp(4), 0, 0));
        buildContext(block, auto);
    }

    private void buildContext(final LinearLayout block, boolean auto) {
        block.removeAllViews();
        LinearLayout head = Ui.row(c);
        final int answer = j.settings.getInt(Settings.LLM_MAX_TOKENS, DEFAULT_MAX_TOKENS);
        final int autoChars = ContextFit.autoContextChars(caps.contextTokens, answer > 0 ? answer : DEFAULT_MAX_TOKENS);
        String text = auto
            ? (caps.contextTokens > 0 ? "Авто: " + (autoChars / 1000) + " тыс. символов · окно модели " + CapChips.tokens(caps.contextTokens)
                : "Авто: окно модели неизвестно, " + (ContextFit.DEFAULT_CHARS / 1000) + " тыс. символов")
            : (caps.contextTokens > 0 ? "Вручную · окно модели " + CapChips.tokens(caps.contextTokens) + " токенов" : "Вручную · окно модели неизвестно");
        head.addView(caption(c, text), Ui.lpw(1f));
        head.addView(Widgets.toggle(c, auto, new Widgets.Toggle.Listener() { @Override public void onChanged(boolean on) {
            j.settings.setBool(Settings.LLM_CONTEXT_AUTO, on);
            buildContext(block, on);
        } }), Ui.lpm(-2, -2, Ui.dp(8), 0, 0, 0));
        block.addView(head);
        if (!auto) {
            final int kChars = Math.max(8, Math.min(200, j.settings.getInt(Settings.LLM_CONTEXT_CHARS, 60_000) / 1000));
            final TextView val = caption(c, kChars + " тыс. символов истории");
            block.addView(val, Ui.lpm(-2, -2, 0, Ui.dp(6), 0, 0));
            block.addView(Widgets.slider(c, 8, 200, 2, kChars, new Widgets.SliderListener() { @Override public void onValue(float v, boolean fromUser) {
                int n = Math.round(v);
                val.setText(n + " тыс. символов истории");
                j.settings.set(Settings.LLM_CONTEXT_CHARS, String.valueOf(n * 1000));
            } }), Ui.lpm(-1, Ui.dp(32), 0, Ui.dp(2), 0, 0));
        }
    }

    private void steps(LinearLayout pad) {
        pad.addView(head("Шагов агента", "Сколько шагов агент может сделать за один запрос: вызовы инструментов и ответ. "
            + "«Без ограничения» — до 200 шагов, защита от зацикливания; это записывается в журнал."), Ui.lpm(-1, -2, 0, 0, 0, Ui.dp(6)));
        final LinearLayout block = Ui.column(c);
        pad.addView(block, Ui.lpm(-1, -2, 0, Ui.dp(4), 0, 0));
        buildSteps(block);
    }

    private void buildSteps(final LinearLayout block) {
        block.removeAllViews();
        final int saved = j.settings.getInt(Settings.LLM_MAX_STEPS, 10);
        final boolean unlimited = saved == 0;
        LinearLayout head = Ui.row(c);
        final TextView val = caption(c, unlimited ? "без ограничения (не больше 200)" : Math.max(2, Math.min(30, saved)) + " шагов");
        head.addView(val, Ui.lpw(1f));
        head.addView(Widgets.toggle(c, unlimited, new Widgets.Toggle.Listener() { @Override public void onChanged(boolean on) {
            j.settings.set(Settings.LLM_MAX_STEPS, on ? "0" : "10");
            buildSteps(block);
        } }), Ui.lpm(-2, -2, Ui.dp(8), 0, 0, 0));
        block.addView(head);
        if (!unlimited) {
            block.addView(Widgets.slider(c, 2, 30, 1, Math.max(2, Math.min(30, saved)), new Widgets.SliderListener() { @Override public void onValue(float v, boolean fromUser) {
                int n = Math.round(v);
                val.setText(n + " шагов");
                j.settings.set(Settings.LLM_MAX_STEPS, String.valueOf(n));
            } }), Ui.lpm(-1, Ui.dp(32), 0, Ui.dp(2), 0, 0));
        }
    }
}
