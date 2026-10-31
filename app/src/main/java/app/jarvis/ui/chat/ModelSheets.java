package app.jarvis.ui.chat;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.llm.ModelInfo;
import app.jarvis.core.util.CancelToken;
import app.jarvis.data.Settings;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import app.jarvis.ui.settings.llm.ModelSwitch;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The chat top-strip sheets: model/provider chooser (live GET /models with search and manual id entry) and the
 * conversation history (open / delete). Everything heavy (network, DB) runs off the UI thread.
 */
public final class ModelSheets {
    private ModelSheets() { }

    // ---- model + provider chooser ----------------------------------------------------------------------------------
    public static void modelSheet(final Host host) {
        final Context c = host.context();
        final Jarvis j = host.jarvis();
        final LinearLayout col = Ui.column(c);

        col.addView(section(c, "Провайдер"));
        final ProviderAdapter providers = new ProviderAdapter(host);
        ListView pv = fixedList(c, providers, Ui.dp(216));
        col.addView(pv);

        col.addView(divider(c));

        final TextView current = Ui.text(c, "", Ui.MONO, Ui.P.text);
        final TextView fetchState = Ui.text(c, "", Ui.CAPTION, Ui.P.text3);
        col.addView(section(c, "Модель"));
        LinearLayout cur = Ui.row(c);
        cur.setPadding(Ui.dp(20), 0, Ui.dp(20), 0);
        cur.addView(current, Ui.lpw(1f));
        col.addView(cur, Ui.lpm(-1, -2, 0, 0, 0, 0));
        col.addView(fetchState, Ui.lpm(-1, -2, Ui.dp(20), Ui.dp(4), Ui.dp(20), 0));

        final OverlayHost.Layer[] layerRef = new OverlayHost.Layer[1];
        final ModelAdapter models = new ModelAdapter(host, new ModelAdapter.Pick() {
            @Override public void on(String id) {
                ModelSwitch.request(host, j.providerId(), id, new Runnable() { @Override public void run() {
                    refreshModelRow(host, current);
                    if (layerRef[0] != null) host.overlays().dismiss(layerRef[0]);
                } });
            }
        });
        final EditText search = Widgets.field(c, "Поиск модели", "", false, false);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) { models.filter(s.toString()); }
        });
        final FrameLayout searchBox = Widgets.fieldBox(c, search);
        searchBox.setVisibility(View.GONE);
        col.addView(searchBox, Ui.lpm(-1, -2, Ui.dp(12), Ui.dp(8), Ui.dp(12), 0));
        final ListView mv = fixedList(c, models, Ui.dp(180));
        mv.setVisibility(View.GONE);
        col.addView(mv);

        LinearLayout btns = Ui.row(c);
        btns.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(4));
        btns.addView(Ui.button(c, "Обновить список", R.drawable.ic_refresh, Ui.BTN_GLASS, new Runnable() {
            @Override public void run() { fetchModels(host, fetchState, searchBox, mv, models); }
        }));
        btns.addView(Ui.space(c, 8, 1));
        btns.addView(Ui.button(c, "Ввести ID", R.drawable.ic_edit, Ui.BTN_GHOST, new Runnable() {
            @Override public void run() { promptModelId(host); }
        }));
        col.addView(btns);

        refreshModelRow(host, current);
        // the sheet grows when the fetched model list appears: wrap in a height-capped ScrollView so nothing clips
        android.widget.ScrollView sv = new android.widget.ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
        final int maxH = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.68f);
        sv.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (sv.getHeight() > 0 && col.getHeight() > maxH && sv.getLayoutParams().height != maxH) {
                    sv.getLayoutParams().height = maxH;
                    sv.requestLayout();
                    return false;
                }
                return true;
            }
        });
        layerRef[0] = Dialogs.sheet(host, "Модель", sv, false);
        providers.onChanged = new Runnable() {
            @Override public void run() { refreshModelRow(host, current); }
        };
    }

    private static void refreshModelRow(Host host, TextView current) {
        Jarvis.LlmChoice ch = currentChoice(host);
        current.setText(ch == null || ch.model.isEmpty() ? "не выбрана" : ch.model);
    }

    private static Jarvis.LlmChoice currentChoice(Host host) {
        List<Jarvis.LlmChoice> l = host.jarvis().llmChoices();
        for (Jarvis.LlmChoice c : l) if (c.preset.id.equals(host.jarvis().providerId())) return c;
        return null;
    }

    private static ListView fixedList(Context c, BaseAdapter a, int h) {
        ListView lv = new ListView(c);
        lv.setAdapter(a);
        lv.setDivider(null);
        lv.setVerticalScrollBarEnabled(false);
        lv.setLayoutParams(new ViewGroup.LayoutParams(-1, h));
        return lv;
    }

    private static TextView section(Context c, String s) {
        TextView t = Ui.text(c, s.toUpperCase(Locale.ROOT), Ui.CAPTION, Ui.P.text3);
        t.setTypeface(Ui.medium);
        t.setLetterSpacing(0.08f);
        t.setPadding(Ui.dp(20), Ui.dp(14), Ui.dp(20), Ui.dp(6));
        return t;
    }

    private static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Ui.P.divider);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(1))));
        return v;
    }

    private static void promptModelId(final Host host) {
        final Jarvis j = host.jarvis();
        Jarvis.LlmChoice ch = currentChoice(host);
        Dialogs.prompt(host, "Модель вручную", "id модели, как в /v1/models", ch == null ? "" : ch.model, false, false, "Сохранить",
            new Dialogs.StringListener() {
                @Override public void on(String s) {
                    String v = s == null ? "" : s.trim();
                    if (v.isEmpty()) return;
                    ModelSwitch.request(host, j.providerId(), v, null);
                }
            });
    }

    private static void fetchModels(final Host host, final TextView state, final FrameLayout searchBox, final ListView lv, final ModelAdapter models) {
        final Jarvis j = host.jarvis();
        final Jarvis.LlmChoice ch = currentChoice(host);
        if (ch == null) { state.setText("Сначала выберите провайдера"); state.setTextColor(Ui.P.warn); return; }
        if (models.loading) return;
        models.loading = true;
        state.setText("Загружаю список моделей…");
        state.setTextColor(Ui.P.text3);
        final CancelToken cancel = models.cancel;
        new Thread(new Runnable() {
            @Override public void run() {
                List<ModelInfo> out;
                String err = "";
                try {
                    out = j.llm.listModels(ch.config, cancel);
                } catch (Throwable t) {
                    out = null;
                    err = t instanceof app.jarvis.core.llm.LlmException ? ((app.jarvis.core.llm.LlmException) t).userMessage
                        : "Не удалось получить список: " + t.getClass().getSimpleName();
                }
                final List<ModelInfo> res = out;
                final String errText = err;
                j.main.post(new Runnable() {
                    @Override public void run() {
                        models.loading = false;
                        if (cancel.isCancelled()) return;
                        if (res == null) {
                            state.setText(errText);
                            state.setTextColor(Ui.P.err);
                            return;
                        }
                        models.setAll(res);
                        state.setText("Моделей: " + res.size() + (ch.hasKey ? "" : " · нет ключа — список может быть неполным"));
                        state.setTextColor(Ui.P.text3);
                        searchBox.setVisibility(res.isEmpty() ? View.GONE : View.VISIBLE);
                        lv.setVisibility(res.isEmpty() ? View.GONE : View.VISIBLE);
                    }
                });
            }
        }, "jv-models").start();
    }

    private static final class ProviderAdapter extends BaseAdapter {
        final Host host;
        Runnable onChanged;
        final List<app.jarvis.core.llm.ProviderPreset> data;

        ProviderAdapter(Host host) {
            this.host = host;
            data = app.jarvis.core.llm.Presets.all();
        }

        void select(String id) {
            ModelSwitch.request(host, id, host.jarvis().modelFor(id), new Runnable() { @Override public void run() {
                notifyDataSetChanged();
                if (onChanged != null) onChanged.run();
            } });
        }

        @Override public int getCount() { return data.size(); }
        @Override public Object getItem(int position) { return data.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            final Context c = host.context();
            LinearLayout row;
            if (convertView == null) {
                row = Ui.row(c);
                row.setPadding(Ui.dp(16), Ui.dp(9), Ui.dp(16), Ui.dp(9));
                row.setBackground(Ui.rippleOn(new android.graphics.drawable.ColorDrawable(0), 12));
                LinearLayout tx = Ui.column(c);
                LinearLayout head = Ui.row(c);
                TextView name = Ui.text(c, "", Ui.BODY);
                name.setTypeface(Ui.medium);
                name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14.5f);
                name.setTag("name");
                head.addView(name, Ui.lpw(1f));
                ImageView check = Ui.icon(c, R.drawable.ic_check, 17, Ui.P.accent);
                check.setTag("check");
                head.addView(check);
                tx.addView(head, new LinearLayout.LayoutParams(-1, -2));
                TextView note = Ui.text(c, "", Ui.CAPTION, Ui.P.text3);
                note.setMaxLines(2);
                note.setEllipsize(android.text.TextUtils.TruncateAt.END);
                note.setTag("note");
                tx.addView(note, Ui.lpm(-2, -2, 0, 2, 0, 0));
                row.addView(tx, Ui.lpw(1f));
                TextView pill = Widgets.pill(c, "", Widgets.MUTED);
                pill.setTag("pill");
                row.addView(pill, Ui.lpm(-2, -2, Ui.dp(8), 0, 0, 0));
                convertView = row;
            }
            app.jarvis.core.llm.ProviderPreset p = data.get(position);
            boolean selected = p.id.equals(host.jarvis().providerId());
            ((TextView) convertView.findViewWithTag("name")).setText(p.name);
            ((ImageView) convertView.findViewWithTag("check")).setVisibility(selected ? View.VISIBLE : View.GONE);
            ((TextView) convertView.findViewWithTag("note")).setText(p.note == null || p.note.isEmpty() ? p.baseUrl : p.note);
            boolean hasKey = !host.jarvis().secrets.get("key." + p.id).isEmpty() || !p.keyRequired;
            TextView pill = (TextView) convertView.findViewWithTag("pill");
            pill.setText(hasKey ? "ключ есть" : "нет ключа");
            pill.setTextColor(hasKey ? Ui.P.ok : Ui.P.text3);
            pill.setBackground(Ui.rect(Ui.alpha(hasKey ? Ui.P.ok : Ui.P.text3, 0.13f), 20));
            convertView.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { select(p.id); }
            });
            return convertView;
        }
    }

    private static final class ModelAdapter extends BaseAdapter {
        interface Pick { void on(String id); }

        final Host host;
        final Pick pick;
        final CancelToken cancel = new CancelToken();
        final List<ModelInfo> all = new ArrayList<>();
        final List<ModelInfo> shown = new ArrayList<>();
        String query = "";
        boolean loading;

        ModelAdapter(Host host, Pick pick) {
            this.host = host;
            this.pick = pick;
        }

        void setAll(List<ModelInfo> ms) {
            all.clear();
            for (ModelInfo m : ms) if (m.chatLikely) all.add(m);
            apply();
        }

        void filter(String q) {
            query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
            apply();
        }

        private void apply() {
            shown.clear();
            for (ModelInfo m : all) if (query.isEmpty() || m.id.toLowerCase(Locale.ROOT).contains(query)) shown.add(m);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            final Context c = host.context();
            LinearLayout row;
            if (convertView == null) {
                row = Ui.row(c);
                row.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(8));
                row.setBackground(Ui.rippleOn(new android.graphics.drawable.ColorDrawable(0), 12));
                LinearLayout tx = Ui.column(c);
                TextView id = Ui.text(c, "", Ui.MONO, Ui.P.text);
                id.setSingleLine(true);
                id.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                id.setTag("id");
                tx.addView(id);
                TextView sub = Ui.text(c, "", Ui.CAPTION, Ui.P.text3);
                sub.setTag("sub");
                tx.addView(sub, Ui.lpm(-2, -2, 0, 1, 0, 0));
                row.addView(tx, Ui.lpw(1f));
                ImageView check = Ui.icon(c, R.drawable.ic_check, 17, Ui.P.accent);
                check.setTag("check");
                row.addView(check);
                convertView = row;
            }
            final ModelInfo m = shown.get(position);
            String current = "";
            Jarvis.LlmChoice ch = currentChoice(host);
            if (ch != null) current = ch.model;
            ((TextView) convertView.findViewWithTag("id")).setText(m.id);
            String owner = m.ownedBy.isEmpty() ? "" : " · " + m.ownedBy;
            ((TextView) convertView.findViewWithTag("sub")).setText(m.contextLength > 0 ? ChatFormat.k(m.contextLength) + " контекст" + owner : owner);
            ((ImageView) convertView.findViewWithTag("check")).setVisibility(m.id.equals(current) ? View.VISIBLE : View.GONE);
            convertView.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pick.on(m.id); }
            });
            return convertView;
        }
    }
}
