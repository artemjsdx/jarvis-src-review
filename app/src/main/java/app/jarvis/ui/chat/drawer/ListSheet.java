package app.jarvis.ui.chat.drawer;

import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Ui;

/** A bottom sheet with a plain list of actions; tapping one closes the sheet and then runs it. */
final class ListSheet {
    static final class Item {
        final int icon;
        final String label;
        final boolean danger, checked;
        final Runnable action;

        Item(int icon, String label, boolean danger, boolean checked, Runnable action) {
            this.icon = icon;
            this.label = label;
            this.danger = danger;
            this.checked = checked;
            this.action = action;
        }
    }

    private ListSheet() { }

    static void show(final Host h, String title, Item... items) {
        LinearLayout list = Ui.column(h.context());
        final OverlayHost.Layer[] layer = new OverlayHost.Layer[1];
        for (final Item it : items) {
            LinearLayout row = Ui.row(h.context());
            row.setPadding(Ui.dp(20), Ui.dp(14), Ui.dp(20), Ui.dp(14));
            row.setBackground(Ui.rippleOn(new ColorDrawable(0), 14));
            int color = it.danger ? Ui.P.err : Ui.P.text;
            if (it.icon != 0) row.addView(Ui.icon(h.context(), it.icon, 20, it.danger ? Ui.P.err : Ui.P.text2), Ui.lpm(-2, -2, 0, 0, 14, 0));
            TextView label = Ui.text(h.context(), it.label, Ui.BODY, color);
            label.setTypeface(Ui.medium);
            row.addView(label, Ui.lpw(1f));
            if (it.checked) row.addView(Ui.icon(h.context(), R.drawable.ic_check, 20, Ui.P.accent), Ui.lpm(-2, -2, 10, 0, 0, 0));
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    h.overlays().dismiss(layer[0]);
                    it.action.run();
                }
            });
            list.addView(row);
        }
        layer[0] = Dialogs.sheet(h, title, list, false);
    }
}
