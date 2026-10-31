package app.jarvis.dev.scenes;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import app.jarvis.R;
import app.jarvis.dev.PreviewHost;
import app.jarvis.ui.Backdrop;
import app.jarvis.ui.OrbView;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Ui;

public final class BasicsScenes {
    public static void render() throws Exception {
        Context c = PreviewHost.ctx;
        FrameLayout root = new FrameLayout(c);
        root.addView(new Backdrop(c), new FrameLayout.LayoutParams(-1, -1));
        LinearLayout col = Ui.column(c);
        col.setPadding(Ui.dp(20), Ui.dp(48), Ui.dp(20), Ui.dp(20));
        OrbView orb = new OrbView(c);
        orb.setMode(OrbView.Mode.LISTENING);
        orb.setLevel(0.5f);
        col.addView(orb, Ui.lp(Ui.dp(220), Ui.dp(220)));
        LinearLayout card = Ui.column(c);
        card.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
        card.setBackground(Ui.glass(20));
        card.addView(Ui.text(c, "Слушаю", Ui.TITLE));
        card.addView(Ui.text(c, "Скажите «Джарвис» или нажмите кнопку", Ui.BODY, Ui.P.text2));
        col.addView(card, Ui.lpm(-1, -2, 0, 12, 0, 0));
        LinearLayout row = Ui.row(c);
        row.addView(Ui.button(c, "Говорить", R.drawable.ic_mic, Ui.BTN_PRIMARY, null));
        row.addView(Ui.space(c, 10, 1));
        row.addView(Ui.button(c, "Остановить", R.drawable.ic_stop, Ui.BTN_GLASS, null));
        row.addView(Ui.space(c, 10, 1));
        row.addView(Ui.iconButton(c, R.drawable.ic_settings, 44, Ui.P.text, null));
        col.addView(row, Ui.lpm(-2, -2, 0, 16, 0, 0));
        col.addView(Ui.chip(c, "ElevenLabs · готов", Ui.P.ok), Ui.lpm(-2, -2, 0, 12, 0, 0));
        root.addView(col);
        OverlayHost host = new OverlayHost(c);
        root.addView(host, new FrameLayout.LayoutParams(-1, -1));
        PreviewHost.render(root, "basics_home");
    }
}
