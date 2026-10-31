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

/** The full provider list in its own sheet, with search and key status; the settings page keeps only one row. */
public final class ProviderSheet {
    public interface Pick { void on(String providerId); }

    private ProviderSheet() { }

    public static void open(final Host host, final Pick pick) {
        final OverlayHost.Layer[] ly = new OverlayHost.Layer[1];
        Runnable close = new Runnable() { @Override public void run() { host.overlays().dismiss(ly[0]); } };
        ly[0] = Dialogs.sheet(host, "Провайдеры", content(host, pick, close), false);
    }

    /** The sheet body; {@code close} runs after a provider is picked. */
    public static View content(final Host host, final Pick pick, final Runnable close) {
        final Context c = host.context();
        final Jarvis j = host.jarvis();
        LinearLayout body = Ui.column(c);
        final EditText search = Widgets.field(c, "Поиск провайдера", "", false, false);
        body.addView(Widgets.fieldBox(c, search), Ui.lpm(-1, -2, 0, 0, 0, Ui.dp(8)));
        final ListView list = new ListView(c);
        list.setDivider(null);
        list.setCacheColorHint(0);
        final Adapter ad = new Adapter(c, j, pick, close);
        list.setAdapter(ad);
        body.addView(list, new LinearLayout.LayoutParams(-1, Ui.dp(440)));
        body.addView(Ui.text(c, "Свой адрес OpenAI-совместимого сервера — пункт «Свой OpenAI-совместимый адрес» в списке.", Ui.CAPTION, Ui.P.text3),
            Ui.lpm(-1, -2, 0, Ui.dp(8), 0, 0));
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { ad.filter(s.toString()); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        return body;
    }

    private static final class Adapter extends BaseAdapter {
        private final Context c;
        private final Jarvis j;
        private final Pick pick;
        private final Runnable close;
        private List<ProviderPreset> shown = new ArrayList<>(Presets.all());
        private String query = "";

        Adapter(Context c, Jarvis j, Pick pick, Runnable close) { this.c = c; this.j = j; this.pick = pick; this.close = close; }

        void filter(String q) {
            query = q.toLowerCase(Locale.ROOT).trim();
            shown = new ArrayList<>();
            for (ProviderPreset p : Presets.all()) {
                if (query.isEmpty() || p.name.toLowerCase(Locale.ROOT).contains(query) || p.id.contains(query)) shown.add(p);
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            final ProviderPreset p = shown.get(position);
            boolean current = p.id.equals(j.providerId());
            LinearLayout row = Ui.row(c);
            row.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
            row.setBackground(current ? Ui.rect(Ui.P.accentSoft, 12) : Ui.rippleOn(new android.graphics.drawable.ColorDrawable(0), 12));
            LinearLayout tx = Ui.column(c);
            TextView name = Ui.text(c, p.name, Ui.BODY);
            name.setTypeface(current ? Ui.medium : Ui.regular);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            tx.addView(name);
            String note = p.note.isEmpty() ? (p.keyRequired ? "нужен ключ" : "ключ не нужен") : p.note;
            TextView sub = Ui.text(c, note, Ui.CAPTION, Ui.P.text3);
            sub.setMaxLines(2);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            tx.addView(sub, Ui.lpm(-2, -2, 0, Ui.dp(2), 0, 0));
            row.addView(tx, Ui.lpw(1f));
            row.addView(keyBadge(p), Ui.lpm(-2, -2, Ui.dp(8), 0, 0, 0));
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                pick.on(p.id);
                if (close != null) close.run();
            } });
            return row;
        }

        private View keyBadge(ProviderPreset p) {
            if (!p.keyRequired) return Widgets.pill(c, "ключ не нужен", Widgets.MUTED);
            if (j.secrets.has("key." + p.id)) return Widgets.pill(c, "ключ задан", Widgets.OK);
            return Widgets.pill(c, "ключа нет", Widgets.WARN);
        }
    }
}
