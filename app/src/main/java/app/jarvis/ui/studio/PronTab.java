package app.jarvis.ui.studio;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.voice.PronunciationDict;
import app.jarvis.core.voice.TtsProvider;
import app.jarvis.data.Settings;
import app.jarvis.rt.SpeechApi;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import java.util.ArrayList;
import java.util.List;

/**
 * Вкладка «Произношение»: словарь замен (шаблон → замена, букв./регэксп, без учёта регистра),
 * живой предпросмотр «как будет прочитано» через prepareText, размер кэша с очисткой и 4 совета.
 */
public class PronTab {
    private final Host host;
    private final Context ctx;
    private final app.jarvis.rt.Jarvis j;

    private LinearLayout col, rulesHost, previewHost, cacheHost;
    private EditText previewInput;
    private TextView previewOut;

    public PronTab(Host host) {
        this.host = host;
        this.ctx = host.context();
        this.j = host.jarvis();
    }

    public View view() {
        ScrollView sv = Ui.scroll(ctx);
        sv.setFillViewport(true);
        col = Ui.column(ctx);
        col.setPadding(0, 0, 0, Ui.dp(24));
        sv.addView(col);
        rebuild();
        return sv;
    }

    public void rebuild() {
        col.removeAllViews();
        if (j.speech == null) {
            col.addView(Widgets.empty(ctx, R.drawable.ic_voice, "Голосовой модуль не подключён",
                "Словарь произношения станет доступен после подключения речевого движка."));
            return;
        }
        col.addView(Widgets.sectionTitle(ctx, "Как будет прочитано"));
        col.addView(previewCard());
        col.addView(Widgets.sectionTitle(ctx, "Словарь замен"));
        col.addView(rulesCard());
        col.addView(Widgets.sectionTitle(ctx, "Кэш озвучки"));
        col.addView(cacheCard());
        col.addView(Widgets.sectionTitle(ctx, "Четыре совета"));
        col.addView(tipsCard());
    }

    // -------------------------------------------------------------------------------------------- preview
    private View previewCard() {
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));
        previewInput = Widgets.field(ctx, "Введите фразу с числами или именами…", "28.10.2026, всё для замка", true, false);
        card.addView(Widgets.fieldBox(ctx, previewInput));
        previewOut = Ui.text(ctx, "", Ui.BODY, Ui.P.accent);
        previewOut.setTypeface(Ui.mono);
        card.addView(previewOut, Ui.lpm(-2, -2, 0, Ui.dp(10), 0, 0));
        card.addView(StudioKit.metaRow(ctx, "для провайдера:", providerLabel()), Ui.lpm(-1, -2, 0, Ui.dp(6), 0, 0));
        card.addView(caption(ctx, "Так текст уйдёт в синтез: числа и даты словами, эмодзи и разметка убраны, словарь применён."),
            Ui.lpm(-2, -2, 0, Ui.dp(6), 0, 0));
        previewInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { updatePreview(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        updatePreview();
        return card;
    }

    private String providerLabel() {
        SpeechApi sp = j.speech;
        if (sp == null) return "—";
        List<String> chain = sp.activeChain();
        String pid = chain.isEmpty() ? (sp.ttsProviders().isEmpty() ? "" : sp.ttsProviders().get(0).info().id) : chain.get(0);
        TtsProvider p = pid.isEmpty() ? null : sp.tts(pid);
        return p == null ? "—" : p.info().name;
    }

    private String previewProviderId() {
        SpeechApi sp = j.speech;
        List<String> chain = sp.activeChain();
        return chain.isEmpty() ? (sp.ttsProviders().isEmpty() ? "" : sp.ttsProviders().get(0).info().id) : chain.get(0);
    }

    private void updatePreview() {
        if (j.speech == null || previewOut == null) return;
        String text = previewInput.getText().toString();
        previewOut.setText(text.trim().isEmpty() ? "…" : j.speech.prepareText(text, previewProviderId()));
    }

    // -------------------------------------------------------------------------------------------- rules
    private View rulesCard() {
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));
        rulesHost = Ui.column(ctx);
        card.addView(rulesHost);
        renderRules();
        return card;
    }

    private List<PronunciationDict.Rule> loadRules() {
        String js = j.speech == null ? "" : j.speech.pronunciationJson();
        return PronunciationDict.fromJson(js).rules();
    }

    private void saveRules(List<PronunciationDict.Rule> rules) {
        PronunciationDict d = new PronunciationDict();
        for (PronunciationDict.Rule r : rules) d.add(r.pattern, r.replacement, r.regex, r.caseInsensitive);
        if (j.speech != null) j.speech.setPronunciationJson(d.toJson());
    }

    private void renderRules() {
        rulesHost.removeAllViews();
        List<PronunciationDict.Rule> rules = loadRules();
        if (rules.isEmpty()) {
            rulesHost.addView(caption(ctx, "Правил нет. Правило заменяет слово или сочетание перед озвучкой: например, «Джарвис» → «Джа́рвис» с нужным ударением."));
        } else {
            int i = 0;
            for (final PronunciationDict.Rule r : rules) {
                if (i > 0) Widgets.divide(rulesHost);
                LinearLayout row = Ui.row(ctx);
                row.setPadding(0, Ui.dp(10), 0, Ui.dp(10));
                LinearLayout tx = Ui.column(ctx);
                LinearLayout patternRow = Ui.row(ctx);
                TextView pat = Ui.text(ctx, r.pattern, Ui.BODY);
                pat.setTypeface(Ui.mono);
                pat.setSingleLine(true);
                pat.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                patternRow.addView(pat, Ui.lpw(1f));
                patternRow.addView(Widgets.pill(ctx, r.regex ? "регэксп" : "текст", Widgets.MUTED), Ui.lpm(-2, -2, Ui.dp(6), 0, 0, 0));
                if (!r.caseInsensitive) patternRow.addView(Widgets.pill(ctx, "А-я", Widgets.MUTED), Ui.lpm(-2, -2, Ui.dp(6), 0, 0, 0));
                tx.addView(patternRow);
                TextView rep = Ui.text(ctx, "→ " + (r.replacement.isEmpty() ? "(удалить)" : r.replacement), Ui.CAPTION, Ui.P.text2);
                rep.setSingleLine(true);
                rep.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                tx.addView(rep, Ui.lpm(-2, -2, 0, Ui.dp(2), 0, 0));
                row.addView(tx, Ui.lpw(1f));
                row.addView(Ui.iconButton(ctx, R.drawable.ic_edit, 36, Ui.P.text2, new Runnable() { @Override public void run() { editRule(r); } }));
                row.setBackground(Ui.rippleOn(new android.graphics.drawable.ColorDrawable(0), 12));
                row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { editRule(r); } });
                rulesHost.addView(row);
                i++;
            }
        }
        rulesHost.addView(Ui.button(ctx, "Добавить правило", R.drawable.ic_plus, Ui.BTN_GLASS, new Runnable() { @Override public void run() { editRule(null); } }),
            Ui.lpm(-2, -2, 0, Ui.dp(10), 0, 0));
    }

    private void editRule(final PronunciationDict.Rule existing) {
        final Context c = ctx;
        LinearLayout body = Ui.column(c);
        body.setPadding(Ui.dp(16), Ui.dp(4), Ui.dp(16), Ui.dp(4));
        final EditText pat = Widgets.field(c, "Что заменить (текст или регэксп)", existing == null ? "" : existing.pattern, false, false);
        body.addView(Widgets.fieldBox(c, pat));
        final EditText rep = Widgets.field(c, "Чем заменить", existing == null ? "" : existing.replacement, false, false);
        body.addView(Widgets.fieldBox(c, rep), Ui.lpm(-1, -2, 0, Ui.dp(8), 0, 0));
        final Widgets.Toggle regex = Widgets.toggle(c, existing != null && existing.regex, null);
        final Widgets.Toggle ci = Widgets.toggle(c, existing == null || existing.caseInsensitive, null);
        body.addView(toggleRow(c, "Регулярное выражение", regex), Ui.lpm(-2, -2, 0, Ui.dp(10), 0, 0));
        body.addView(toggleRow(c, "Без учёта регистра", ci), Ui.lpm(-2, -2, 0, Ui.dp(8), 0, 0));
        body.addView(caption(c, existing == null
            ? "Пример текста: «джарвис» → «Джарвис»; пример регэкспа: «(\\d+) руб» → «$1 рублей»."
            : "Изменения применяются после «Сохранить»."), Ui.lpm(-2, -2, 0, Ui.dp(8), 0, 0));

        final OverlayHost.Layer[] ly = new OverlayHost.Layer[1];
        LinearLayout btns = Ui.row(c);
        btns.setGravity(Gravity.END);
        if (existing != null) {
            btns.addView(Ui.button(c, "Удалить", R.drawable.ic_trash, Ui.BTN_DANGER, new Runnable() { @Override public void run() {
                host.overlays().dismiss(ly[0]);
                List<PronunciationDict.Rule> rules = loadRules();
                for (int i = 0; i < rules.size(); i++)
                    if (rules.get(i).pattern.equals(existing.pattern) && rules.get(i).replacement.equals(existing.replacement)) { rules.remove(i); break; }
                saveRules(rules);
                renderRules();
                updatePreview();
            } }), Ui.lpm(-2, -2, 0, 0, Ui.dp(8), 0));
        }
        btns.addView(Ui.button(c, "Сохранить", 0, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() {
            String p = pat.getText().toString().trim();
            if (p.isEmpty()) { host.overlays().toast("Введите шаблон", 2); return; }
            if (regex.isChecked()) {
                try { java.util.regex.Pattern.compile(p); } catch (Throwable t) {
                    host.overlays().toast("Некорректный регэксп: " + t.getMessage(), 2);
                    return;
                }
            }
            host.overlays().dismiss(ly[0]);
            List<PronunciationDict.Rule> rules = loadRules();
            rules.add(new PronunciationDict.Rule(p, rep.getText().toString(), regex.isChecked(), ci.isChecked()));
            saveRules(rules);
            renderRules();
            updatePreview();
        } }));
        body.addView(btns, Ui.lpm(-1, -2, 0, Ui.dp(14), 0, 0));
        ly[0] = Dialogs.sheet(host, existing == null ? "Новое правило" : "Правило", body, true);
    }

    private View toggleRow(Context c, String title, Widgets.Toggle t) {
        LinearLayout r = Ui.row(c);
        r.addView(Ui.text(c, title, Ui.BODY), Ui.lpw(1f));
        r.addView(t, Ui.lpm(-2, -2, Ui.dp(8), 0, 0, 0));
        return r;
    }

    // -------------------------------------------------------------------------------------------- cache
    private View cacheCard() {
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));
        card.addView(caption(ctx, "Озвученные фразы хранятся на диске, чтобы повтор звучал мгновенно и не тратил квоту."));
        cacheHost = Ui.column(ctx);
        card.addView(cacheHost, Ui.lpm(-2, -2, 0, Ui.dp(8), 0, 0));
        renderCache();
        return card;
    }

    private void renderCache() {
        cacheHost.removeAllViews();
        LinearLayout row = Ui.row(ctx);
        row.addView(Widgets.pill(ctx, humanSize(j.speech.cacheBytes()), Widgets.MUTED));
        row.addView(Ui.space(ctx, 8, 1));
        row.addView(Ui.button(ctx, "Очистить кэш", R.drawable.ic_trash, Ui.BTN_GHOST, new Runnable() { @Override public void run() {
            Dialogs.confirm(host, "Очистить кэш озвучки?", "Повторы фраз будут синтезироваться заново (у ElevenLabs — с тратой символов).", "Очистить", true,
                new Runnable() { @Override public void run() {
                    j.speech.clearCache();
                    renderCache();
                    host.overlays().toast("Кэш очищен", 0);
                } });
        } }));
        cacheHost.addView(row);
    }

    private static String humanSize(long b) {
        if (b < 1024) return b + " Б";
        if (b < 1024 * 1024) return String.format(java.util.Locale.US, "%.0f КБ", b / 1024.0);
        return String.format(java.util.Locale.US, "%.1f МБ", b / 1048576.0);
    }

    // -------------------------------------------------------------------------------------------- tips
    private View tipsCard() {
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));
        tip(card, "Числа и даты — словами", "«28.10.2026» синтезаторы читают непредсказуемо. Пишите «двадцать восьмое октября» или добавьте правило в словарь.");
        tip(card, "Ставьте «ё»", "Без точек «всё» и «все» звучат одинаково. Явная «ё» снимает часть ошибок ударения.");
        tip(card, "Омографы — перефразированием", "«замок» читается по-разному. Напишите «дверной замок» или «старинный замок» — или добавьте правило замены.");
        tip(card, "Теги эмоций и дубли", "В ElevenLabs v3/v4 эмоции — теги в [квадратных скобках] на английском, один на фразу ([whispers], [sighs]). Снимайте три дубля и выбирайте лучший на слух.");
        return card;
    }

    private void tip(LinearLayout card, String title, String body) {
        LinearLayout row = Ui.row(card.getContext());
        row.addView(Ui.icon(ctx, R.drawable.ic_sparkles, 16, Ui.P.accent), Ui.lpm(-2, -2, 0, Ui.dp(2), Ui.dp(10), 0));
        LinearLayout tx = Ui.column(ctx);
        TextView t = Ui.text(ctx, title, Ui.BODY);
        t.setTypeface(Ui.medium);
        tx.addView(t);
        tx.addView(caption(ctx, body), Ui.lpm(-2, -2, 0, Ui.dp(2), 0, 0));
        row.addView(tx, Ui.lpw(1f));
        card.addView(row, Ui.lpm(-2, -2, 0, Ui.dp(6), 0, 0));
    }

    private static TextView caption(Context c, String s) { return Ui.text(c, s, Ui.CAPTION, Ui.P.text2); }
}
