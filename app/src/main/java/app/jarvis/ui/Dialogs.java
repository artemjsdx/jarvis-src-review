package app.jarvis.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;

/** Standard in-app windows built on OverlayHost: confirm, prompt, choose, sheet, progress. System dialogs are never used. */
public final class Dialogs {
    private Dialogs() { }

    public interface StringListener { void on(String s); }
    public interface IndexListener { void on(int index); }

    private static LinearLayout box(Context c, String title) {
        LinearLayout b = Ui.column(c);
        b.setPadding(Ui.dp(20), Ui.dp(18), Ui.dp(20), Ui.dp(16));
        if (title != null) b.addView(Ui.text(c, title, Ui.TITLE));
        return b;
    }

    private static LinearLayout buttons(Context c, View... bs) {
        LinearLayout r = Ui.row(c);
        r.setGravity(Gravity.END);
        for (int i = 0; i < bs.length; i++) { if (i > 0) r.addView(Ui.space(c, 8, 1)); r.addView(bs[i]); }
        return r;
    }

    public static void confirm(final Host h, String title, String message, String okLabel, boolean danger, final Runnable onOk) {
        Context c = h.context();
        LinearLayout b = box(c, title);
        if (message != null && !message.isEmpty()) b.addView(Ui.text(c, message, Ui.BODY, Ui.P.text2), Ui.lpm(-1, -2, 0, 8, 0, 0));
        final OverlayHost.Layer[] ly = new OverlayHost.Layer[1];
        View cancel = Ui.button(c, "Отмена", 0, Ui.BTN_GHOST, new Runnable() { @Override public void run() { h.overlays().dismiss(ly[0]); } });
        View ok = Ui.button(c, okLabel, 0, danger ? Ui.BTN_DANGER : Ui.BTN_PRIMARY, new Runnable() { @Override public void run() { h.overlays().dismiss(ly[0]); if (onOk != null) onOk.run(); } });
        b.addView(buttons(c, cancel, ok), Ui.lpm(-1, -2, 0, 18, 0, 0));
        ly[0] = h.overlays().showDialog(b, true, null);
    }

    public static void info(final Host h, String title, String message) {
        Context c = h.context();
        LinearLayout b = box(c, title);
        TextView t = Ui.text(c, message, Ui.BODY, Ui.P.text2);
        t.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(c);
        sv.addView(t);
        b.addView(sv, new LinearLayout.LayoutParams(-1, -2));
        ((LinearLayout.LayoutParams) sv.getLayoutParams()).topMargin = Ui.dp(8);
        final OverlayHost.Layer[] ly = new OverlayHost.Layer[1];
        b.addView(buttons(c, Ui.button(c, "Закрыть", 0, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() { h.overlays().dismiss(ly[0]); } })), Ui.lpm(-1, -2, 0, 14, 0, 0));
        ly[0] = h.overlays().showDialog(b, true, null);
    }

    public static void prompt(final Host h, String title, String hint, String initial, boolean multiline, boolean secret, String okLabel, final StringListener onOk) {
        Context c = h.context();
        LinearLayout b = box(c, title);
        final EditText e = Widgets.field(c, hint, initial, multiline, secret);
        b.addView(Widgets.fieldBox(c, e), Ui.lpm(-1, -2, 0, 12, 0, 0));
        final OverlayHost.Layer[] ly = new OverlayHost.Layer[1];
        View cancel = Ui.button(c, "Отмена", 0, Ui.BTN_GHOST, new Runnable() { @Override public void run() { h.hideKeyboard(); h.overlays().dismiss(ly[0]); } });
        View ok = Ui.button(c, okLabel == null ? "Сохранить" : okLabel, 0, Ui.BTN_PRIMARY, new Runnable() {
            @Override public void run() { h.hideKeyboard(); String v = e.getText().toString(); h.overlays().dismiss(ly[0]); if (onOk != null) onOk.on(v); }
        });
        b.addView(buttons(c, cancel, ok), Ui.lpm(-1, -2, 0, 16, 0, 0));
        ly[0] = h.overlays().showDialog(b, true, null);
        e.requestFocus();
    }

    /** Single choice list as a bottom sheet. Items may have a second line via "title\nsubtitle". */
    public static void choose(final Host h, String title, final String[] items, int selected, final IndexListener onPick) {
        Context c = h.context();
        LinearLayout b = Ui.column(c);
        b.addView(grip(c));
        b.addView(Ui.text(c, title, Ui.TITLE), Ui.lpm(-2, -2, 20, 4, 20, 8));
        ScrollView sv = new ScrollView(c);
        LinearLayout list = Ui.column(c);
        final OverlayHost.Layer[] ly = new OverlayHost.Layer[1];
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            String[] parts = items[i].split("\n", 2);
            View.OnClickListener ocl = new View.OnClickListener() { @Override public void onClick(View v) { h.overlays().dismiss(ly[0]); if (onPick != null) onPick.on(idx); } };
            LinearLayout row = Widgets.row(c, 0, parts[0], parts.length > 1 ? parts[1] : null, i == selected ? Ui.icon(c, R.drawable.ic_check, 20, Ui.P.accent) : new View(c), new Runnable() { @Override public void run() { } });
            row.setOnClickListener(ocl);
            list.addView(row);
        }
        sv.addView(list);
        int maxH = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.62f);
        b.addView(sv, new LinearLayout.LayoutParams(-1, -2));
        sv.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.min(maxH, items.length * Ui.dp(58) + Ui.dp(8))));
        b.addView(Ui.space(c, 1, 12));
        ly[0] = h.overlays().showSheet(b, true, null);
    }

    /** Generic bottom sheet with a title and caller-built content. Returns the layer so the caller can dismiss it. */
    public static OverlayHost.Layer sheet(Host h, String title, View content, boolean scroll) {
        Context c = h.context();
        LinearLayout b = Ui.column(c);
        b.addView(grip(c));
        if (title != null) b.addView(Ui.text(c, title, Ui.TITLE), Ui.lpm(-2, -2, 20, 4, 20, 10));
        if (scroll) {
            ScrollView sv = new ScrollView(c);
            sv.setVerticalScrollBarEnabled(false);
            sv.addView(content);
            int maxH = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.72f);
            b.addView(sv, new LinearLayout.LayoutParams(-1, -2));
            sv.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
            content.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int l, int t, int r, int bm, int ol, int ot, int or, int ob) { }
            });
            sv.setMinimumHeight(0);
            sv.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            capHeight(sv, maxH);
        } else b.addView(content);
        b.addView(Ui.space(c, 1, 14));
        return h.overlays().showSheet(b, true, null);
    }

    private static void capHeight(final ScrollView sv, final int max) {
        sv.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (sv.getChildCount() > 0 && sv.getChildAt(0).getHeight() > max && sv.getLayoutParams().height != max) { sv.getLayoutParams().height = max; sv.requestLayout(); return false; }
                return true;
            }
        });
    }

    private static View grip(Context c) {
        FrameLayout f = new FrameLayout(c);
        View g = new View(c);
        g.setBackground(Ui.rect(Ui.P.strokeHi, 3));
        f.addView(g, new FrameLayout.LayoutParams(Ui.dp(36), Ui.dp(4), Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(20)));
        return f;
    }

    public interface ProgressController { void update(String text, float fraction); void close(); }

    /** Non-dismissable progress window; fraction < 0 = indeterminate. */
    public static ProgressController progress(final Host h, String title, String text, final Runnable onCancel) {
        Context c = h.context();
        LinearLayout b = box(c, title);
        final TextView t = Ui.text(c, text, Ui.BODY, Ui.P.text2);
        b.addView(t, Ui.lpm(-1, -2, 0, 8, 0, 12));
        final Widgets.Progress bar = new Widgets.Progress(c);
        b.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(4)));
        final OverlayHost.Layer[] ly = new OverlayHost.Layer[1];
        if (onCancel != null) b.addView(buttons(c, Ui.button(c, "Отмена", 0, Ui.BTN_GHOST, new Runnable() { @Override public void run() { onCancel.run(); } })), Ui.lpm(-1, -2, 0, 14, 0, 0));
        ly[0] = h.overlays().showDialog(b, false, null);
        return new ProgressController() {
            @Override public void update(String text, float fraction) { if (text != null) t.setText(text); if (fraction >= 0) bar.set(fraction); }
            @Override public void close() { h.overlays().dismiss(ly[0]); }
        };
    }
}
