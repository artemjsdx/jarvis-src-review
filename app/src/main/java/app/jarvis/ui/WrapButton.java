package app.jarvis.ui;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** {@link Ui#button} whose label wraps to two lines instead of being cut to "Пров…". */
public final class WrapButton {
    private WrapButton() { }

    public static LinearLayout create(Context c, CharSequence label, int iconRes, int style, Runnable onClick) {
        LinearLayout b = Ui.button(c, label, iconRes, style, onClick);
        if (label == null || label.length() == 0) return b;
        TextView t = (TextView) b.getChildAt(b.getChildCount() - 1);
        t.setSingleLine(false);
        t.setMaxLines(2);
        t.setEllipsize(null);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return b;
    }
}
