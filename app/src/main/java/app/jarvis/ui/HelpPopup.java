package app.jarvis.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import app.jarvis.R;

/** The "?" hint: a small round button that opens a bottom sheet with a title and 2–5 short lines. */
public final class HelpPopup {
    private HelpPopup() { }

    public static View button(final Context c, final Host h, final String title, final String... lines) {
        return Ui.iconButton(c, R.drawable.ic_help, 34, Ui.P.text3, new Runnable() { @Override public void run() {
            show(h, title, lines);
        } });
    }

    public static void show(final Host h, String title, String... lines) {
        Context c = h.context();
        LinearLayout body = Ui.column(c);
        body.setPadding(Ui.dp(20), 0, Ui.dp(20), Ui.dp(8));
        for (int i = 0; i < lines.length; i++) {
            body.addView(Ui.text(c, lines[i], Ui.BODY, Ui.P.text2), Ui.lpm(-1, -2, 0, i == 0 ? 0 : 8, 0, 0));
        }
        final OverlayHost.Layer[] ly = new OverlayHost.Layer[1];
        View ok = Ui.button(c, "Понятно", 0, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() { h.overlays().dismiss(ly[0]); } });
        body.addView(ok, Ui.lpm(-1, -2, 8, 16, 8, 0));
        ly[0] = Dialogs.sheet(h, title, body, true);
    }
}
