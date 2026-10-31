package app.jarvis.ui.settings.llm;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.llm.Cap;
import app.jarvis.core.llm.ModelCaps;
import app.jarvis.core.llm.ModelCatalog;
import app.jarvis.core.llm.ModelInfo;
import app.jarvis.core.llm.Presets;
import app.jarvis.core.llm.ProviderPreset;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Model list of one provider with search, filters that exist only when the catalog reports the data, the capability chips of every
 * row, a refresh button and the last load error. Picking a row goes through {@link ModelSwitch}.
 */
public final class ModelBrowser {
    private static final int ALL = 0, TOOLS = 1, REASONING = 2, FREE = 3;
    private static final String[] FILTER_NAMES = {"Все", "С инструментами", "С рассуждением", "Бесплатные"};

    private ModelBrowser() { }

    public static void open(final Host host, final String providerId, final Runnable onPicked) {
        final OverlayHost.Layer[] ly = new OverlayHost.Layer[1];
        final Runnable[] detach = new Runnable[1];
        Runnable close = new Runnable() { @Override public void run() {
            host.overlays().dismiss(ly[0]);
            if (onPicked != null) onPicked.run();
        } };
        View v = content(host, providerId, close, detach);
        ly[0] = host.overlays().showSheet(v, true, new Runnable() { @Override public void run() {
            if (detach[0] != null) detach[0].run();
        } });
    }

    /** The sheet body. {@code detachOut[0]} receives the action that unregisters the catalog listener when the sheet closes. */
    public static View content(final Host host, final String providerId, final Runnable close, final Runnable[] detachOut) {
        final Context c = host.context();
        final Jarvis j = host.jarvis();
        final ProviderPreset p = Presets.get(providerId);
        final Adapter ad = new Adapter(c, j, providerId, close, host);

        LinearLayout body = Ui.column(c);
        final TextView status = Ui.text(c, "", Ui.CAPTION, Ui.P.text3);
        body.addView(status, Ui.lpm(-1, -2, 0, 0, 0, Ui.dp(6)));
        LinearLayout actions = Ui.row(c);
        actions.addView(Ui.button(c, "Обновить список", R.drawable.ic_refresh, Ui.BTN_GLASS, new Runnable() { @Override public void run() {
            j.catalog.refresh(providerId);
        } }));
        actions.addView(Ui.space(c, 8, 1));
        actions.addView(Ui.button(c, "Ввести ID вручную", R.drawable.ic_edit, Ui.BTN_GHOST, new Runnable() { @Override public void run() {
            Dialogs.prompt(host, "Модель вручную", "ID, например inception/mercury-3", j.modelFor(providerId), false, false, "Выбрать",
                new Dialogs.StringListener() { @Override public void on(String s) {
                    String v = s.trim();
                    if (!v.isEmpty()) ModelSwitch.request(host, providerId, v, close);
                } });
        } }));
        body.addView(actions, Ui.lpm(-1, -2, 0, 0, 0, Ui.dp(8)));

        final EditText search = Widgets.field(c, "Поиск модели", "", false, false);
        body.addView(Widgets.fieldBox(c, search), Ui.lpm(-1, -2, 0, 0, 0, Ui.dp(6)));
        final LinearLayout filters = Ui.row(c);
        android.widget.HorizontalScrollView filterScroll = new android.widget.HorizontalScrollView(c);
        filterScroll.setHorizontalScrollBarEnabled(false);
        filterScroll.addView(filters);
        body.addView(filterScroll, Ui.lpm(-1, -2, 0, 0, 0, Ui.dp(6)));

        final ListView list = new ListView(c);
        list.setDivider(null);
        list.setCacheColorHint(0);
        list.setAdapter(ad);
        body.addView(list, new LinearLayout.LayoutParams(-1, Ui.dp(380)));

        final ModelCatalog.Listener listener = new ModelCatalog.Listener() {
            @Override public void onCatalogChanged(String pid) {
                if (!pid.equals(providerId)) return;
                j.main.post(new Runnable() { @Override public void run() { ad.reload(); } });
            }
        };
        j.catalog.addListener(listener);
        if (detachOut != null && detachOut.length > 0) detachOut[0] = new Runnable() { @Override public void run() { j.catalog.removeListener(listener); } };
        ad.status = status;
        ad.filters = filters;
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { ad.query = s.toString(); ad.refilter(); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        ad.reload();
        return wrapTitle(c, p == null ? providerId : p.name, body);
    }

    private static View wrapTitle(Context c, String provider, View body) {
        LinearLayout col = Ui.column(c);
        col.setPadding(Ui.dp(20), 0, Ui.dp(20), Ui.dp(12));
        col.addView(Ui.text(c, "Модели · " + provider, Ui.TITLE), Ui.lpm(-2, -2, 0, Ui.dp(4), 0, Ui.dp(10)));
        col.addView(body);
        return col;
    }

    private static final class Adapter extends BaseAdapter {
        private final Context c;
        private final Jarvis j;
        private final String pid;
        private final Host host;
        private Runnable picked;
        TextView status;
        LinearLayout filters;
        String query = "";
        private int filter = ALL;
        private List<ModelInfo> all = new ArrayList<>();
        private List<ModelInfo> shown = new ArrayList<>();

        Adapter(Context c, Jarvis j, String pid, Runnable picked, Host host) { this.c = c; this.j = j; this.pid = pid; this.picked = picked; this.host = host; }

        void reload() {
            all = j.catalog.models(pid);
            ModelCatalog.Status st = j.catalog.status(pid);
            if (status != null) status.setText(statusText(st));
            rebuildFilters();
            refilter();
        }

        private String statusText(ModelCatalog.Status st) {
            if (st.loading) return "Загружаю список…";
            if (!st.lastError.isEmpty()) return "Ошибка загрузки: " + st.lastError + (st.lastHttp > 0 ? " (HTTP " + st.lastHttp + ")" : "") + ". Записано в журнал.";
            if (st.fetchedAt <= 0) return "Список ещё не загружен";
            return "Моделей: " + st.count + " · обновлено " + CapChips.ago(System.currentTimeMillis() - st.fetchedAt);
        }

        /** Filters are shown only for data the provider actually reports. */
        private void rebuildFilters() {
            if (filters == null) return;
            filters.removeAllViews();
            boolean tools = false, reasoning = false, free = false;
            for (ModelInfo m : all) {
                ModelCaps k = m.caps;
                if (k.tools != Cap.UNKNOWN) tools = true;
                if (k.reasoning != Cap.UNKNOWN) reasoning = true;
                if (k.priceInPerM != null) free = true;
            }
            boolean[] offered = {true, tools, reasoning, free};
            for (int i = 0; i < FILTER_NAMES.length; i++) {
                if (!offered[i]) continue;
                final int idx = i;
                TextView t = Widgets.pill(c, FILTER_NAMES[i], filter == i ? Widgets.OK : Widgets.MUTED);
                t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                    filter = idx;
                    rebuildFilters();
                    refilter();
                } });
                filters.addView(t, Ui.lpm(-2, -2, 0, 0, Ui.dp(6), 0));
            }
        }

        void refilter() {
            String q = query.toLowerCase(Locale.ROOT).trim();
            List<ModelInfo> out = new ArrayList<>();
            for (ModelInfo m : all) {
                if (!q.isEmpty() && !m.id.toLowerCase(Locale.ROOT).contains(q) && !m.name.toLowerCase(Locale.ROOT).contains(q)) continue;
                if (!passes(m.caps)) continue;
                out.add(m);
            }
            shown = out;
            notifyDataSetChanged();
        }

        private boolean passes(ModelCaps k) {
            switch (filter) {
                case TOOLS: return k.tools == Cap.YES;
                case REASONING: return k.reasoning == Cap.YES;
                case FREE: return k.priceInPerM != null && k.priceInPerM == 0 && (k.priceOutPerM == null || k.priceOutPerM == 0);
                default: return true;
            }
        }

        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            final ModelInfo m = shown.get(position);
            LinearLayout row = Ui.column(c);
            row.setPadding(Ui.dp(12), Ui.dp(9), Ui.dp(12), Ui.dp(9));
            row.setBackground(m.id.equals(j.modelFor(pid)) ? Ui.rect(Ui.P.accentSoft, 12) : Ui.rippleOn(new android.graphics.drawable.ColorDrawable(0), 12));
            TextView id = Ui.text(c, m.id, Ui.BODY);
            id.setTypeface(Ui.mono);
            id.setSingleLine(true);
            id.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(id);
            if (!m.name.equals(m.id)) {
                TextView nm = Ui.text(c, m.name, Ui.CAPTION, Ui.P.text3);
                nm.setSingleLine(true);
                nm.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(nm, Ui.lpm(-2, -2, 0, Ui.dp(1), 0, 0));
            }
            row.addView(CapChips.chips(c, m.caps), Ui.lpm(-1, -2, 0, Ui.dp(6), 0, 0));
            TextView price = CapChips.priceLine(c, m.caps);
            if (price != null) row.addView(price, Ui.lpm(-2, -2, 0, Ui.dp(4), 0, 0));
            if (!m.caps.rejectedParams.isEmpty()) {
                row.addView(Ui.text(c, "провайдер отклонил: " + TextUtils.join(", ", m.caps.rejectedParams), Ui.CAPTION, Ui.P.warn),
                    Ui.lpm(-2, -2, 0, Ui.dp(4), 0, 0));
            }
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                ModelSwitch.request(host, pid, m.id, picked);
            } });
            return row;
        }
    }
}
