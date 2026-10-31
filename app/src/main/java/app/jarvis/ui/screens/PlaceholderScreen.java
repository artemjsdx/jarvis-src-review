package app.jarvis.ui.screens;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import app.jarvis.ui.Host;
import app.jarvis.ui.Screen;
import app.jarvis.ui.Ui;

/** Temporary body for screens that are still being built; shows what it is, never pretends to work. */
public class PlaceholderScreen extends Screen {
    private final String title, note;

    public PlaceholderScreen(Host host, String title, String note) { super(host); this.title = title; this.note = note; }

    @Override protected View build() {
        LinearLayout c = Ui.column(ctx);
        c.setGravity(Gravity.CENTER);
        c.setPadding(Ui.dp(32), Ui.dp(32), Ui.dp(32), Ui.dp(32));
        c.addView(Ui.text(ctx, title, Ui.TITLE));
        c.addView(Ui.text(ctx, note, Ui.BODY, Ui.P.text2), Ui.lpm(-2, -2, 0, 8, 0, 0));
        return c;
    }

    @Override public String title() { return title; }
}
