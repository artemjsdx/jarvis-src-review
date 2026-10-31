package app.jarvis.ui.studio;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.voice.Voice;
import app.jarvis.core.voice.VoiceException;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Поиск по голосам провайдера: каталог грузится реально (listVoices) в фоне; есть ручной ввод ID,
 * если каталог не открылся. Выбор возвращает voiceId ("" = сбросить на голос по умолчанию).
 */
public final class VoicesSheet {
    private VoicesSheet() { }

    public interface Listener { void onPicked(String voiceId); }

    public static void open(final Host host, final app.jarvis.core.voice.TtsProvider p, final String current, final Listener onPick) {
        final Context ctx = host.context();
        LinearLayout body = Ui.column(ctx);
        body.setPadding(Ui.dp(16), Ui.dp(4), Ui.dp(16), Ui.dp(4));

        final OverlayHost.Layer[] ly = new OverlayHost.Layer[1];
        final EditText search = Widgets.field(ctx, "Поиск по имени…", "", false, false);
        body.addView(Widgets.fieldBox(ctx, search), Ui.lpm(-1, -2, 0, 0, 0, Ui.dp(8)));

        final ListView list = new ListView(ctx);
        list.setDivider(null);
        list.setCacheColorHint(0);
        final Adapter ad = new Adapter(host, list, ly, current);
        ad.listener = onPick;
        list.setAdapter(ad);
        body.addView(list, new LinearLayout.LayoutParams(-1, Ui.dp(320)));

        LinearLayout manual = Ui.row(ctx);
        manual.addView(Ui.button(ctx, "Ввести ID голоса вручную", R.drawable.ic_edit, Ui.BTN_GHOST, new Runnable() { @Override public void run() {
            Dialogs.prompt(host, "Голос вручную", "ID провайдера, например ru-RU-SvetlanaNeural", current, false, false, "Выбрать",
                new Dialogs.StringListener() { @Override public void on(String s) {
                    String v = s.trim();
                    if (v.isEmpty()) return;
                    host.overlays().dismiss(ly[0]);
                    if (onPick != null) onPick.onPicked(v);
                } });
        } }));
        if (current != null && !current.isEmpty()) {
            manual.addView(Ui.button(ctx, "Сбросить", 0, Ui.BTN_GHOST, new Runnable() { @Override public void run() {
                host.overlays().dismiss(ly[0]);
                if (onPick != null) onPick.onPicked("");
            } }));
        }
        body.addView(manual, Ui.lpm(-1, -2, 0, Ui.dp(8), 0, 0));

        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { ad.filter(s.toString()); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        ly[0] = Dialogs.sheet(host, "Голоса · " + p.info().name, body, false);

        // real catalog, off the UI thread
        final CancelToken token = new CancelToken();
        new Thread(new Runnable() { @Override public void run() {
            try {
                final List<Voice> voices = p.listVoices(token);
                host.jarvis().main.post(new Runnable() { @Override public void run() { ad.loaded(voices); } });
            } catch (final VoiceException e) {
                if (e.kind == VoiceException.Kind.CANCELLED) return;
                host.jarvis().main.post(new Runnable() { @Override public void run() { ad.failed(e.userMessage); } });
            } catch (final Throwable t) {
                host.jarvis().main.post(new Runnable() { @Override public void run() {
                    ad.failed("Не удалось получить список голосов: " + t.getClass().getSimpleName() + ". Введите ID вручную.");
                } });
            }
        } }, "jv-list-voices").start();
    }

    private static final class Holder {
        TextView name, sub;
        ImageView check;
    }

    private static final class Adapter extends BaseAdapter {
        final Host host;
        final ListView list;
        final OverlayHost.Layer[] ly;
        final String currentId;
        List<Voice> all = new ArrayList<>(), shown = new ArrayList<>();
        String query = "";
        String error = "";
        boolean loading = true;

        Adapter(Host host, ListView list, OverlayHost.Layer[] ly, String currentId) {
            this.host = host;
            this.list = list;
            this.ly = ly;
            this.currentId = currentId == null ? "" : currentId;
        }

        void loaded(List<Voice> v) { loading = false; error = ""; all = v; refilter(); }
        void failed(String e) { loading = false; error = e; shown = new ArrayList<>(); notifyDataSetChanged(); }
        void filter(String q) { query = q; refilter(); }

        private void refilter() {
            List<Voice> out = new ArrayList<>();
            String q = query.toLowerCase(Locale.ROOT).trim();
            for (Voice v : all)
                if (q.isEmpty() || v.name.toLowerCase(Locale.ROOT).contains(q) || v.id.toLowerCase(Locale.ROOT).contains(q)) out.add(v);
            shown = out;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return loading || !error.isEmpty() ? 1 : shown.size(); }
        @Override public Object getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            if (loading) {
                if (convert instanceof LinearLayout && convert.findViewWithTag("vs.spin") != null) return convert;
                LinearLayout rl = Ui.row(host.context());
                Widgets.Spinner sp = Widgets.spinner(host.context(), 18);
                sp.setTag("vs.spin");
                rl.addView(sp);
                rl.addView(Ui.text(host.context(), "  Загружаю каталог голосов…", Ui.CAPTION, Ui.P.text2));
                rl.setPadding(Ui.dp(8), Ui.dp(14), Ui.dp(8), Ui.dp(8));
                return rl;
            }
            if (!error.isEmpty()) {
                TextView t = convert instanceof TextView ? (TextView) convert : Ui.text(host.context(), "", Ui.CAPTION, Ui.P.err);
                t.setText(error);
                t.setPadding(Ui.dp(8), Ui.dp(14), Ui.dp(8), Ui.dp(8));
                return t;
            }
            Holder h;
            LinearLayout row;
            if (convert instanceof LinearLayout && convert.getTag() instanceof Holder) {
                row = (LinearLayout) convert;
                h = (Holder) convert.getTag();
            } else {
                row = Ui.row(host.context());
                row.setPadding(Ui.dp(16), Ui.dp(11), Ui.dp(14), Ui.dp(11));
                h = new Holder();
                LinearLayout tx = Ui.column(host.context());
                h.name = Ui.text(host.context(), "", Ui.BODY);
                h.name.setTypeface(Ui.medium);
                h.name.setSingleLine(true);
                h.name.setEllipsize(TextUtils.TruncateAt.END);
                tx.addView(h.name);
                h.sub = Ui.text(host.context(), "", Ui.CAPTION, Ui.P.text3);
                h.sub.setSingleLine(true);
                h.sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                tx.addView(h.sub, Ui.lpm(-2, -2, 0, Ui.dp(2), 0, 0));
                row.addView(tx, Ui.lpw(1f));
                h.check = Ui.icon(host.context(), R.drawable.ic_check, 18, Ui.P.accent);
                h.check.setVisibility(View.GONE);
                row.addView(h.check, Ui.lpm(-2, -2, Ui.dp(8), 0, 0, 0));
                row.setTag(h);
                row.setBackground(Ui.rippleOn(new android.graphics.drawable.ColorDrawable(0), 12));
            }
            final Voice m = shown.get(position);
            h.name.setText(m.name);
            StringBuilder sb = new StringBuilder(m.id);
            if (!m.language.isEmpty()) sb.append(" · ").append(m.language);
            if (!m.gender.isEmpty()) sb.append(" · ").append(ruGender(m.gender));
            h.sub.setText(sb.toString());
            h.check.setVisibility(m.id.equals(currentId) ? View.VISIBLE : View.GONE);
            row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                host.overlays().dismiss(ly[0]);
                if (listener != null) listener.onPicked(m.id);
            } });
            return row;
        }

        Listener listener;

        private static String ruGender(String g) {
            if (g.startsWith("fem")) return "женский";
            if (g.startsWith("male")) return "мужской";
            return g;
        }
    }
}
