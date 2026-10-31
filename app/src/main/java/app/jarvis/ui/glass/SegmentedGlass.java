package app.jarvis.ui.glass;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.ui.Ui;
import java.util.ArrayList;

/**
 * Segmented control on glass (journal filters: All, Info, Warnings, Errors). One {@link LiquidIndicator} drop flows between the
 * segments; the whole control is a glass surface, so it blurs what is behind it.
 */
public final class SegmentedGlass extends FrameLayout {
    /** Called on a tap; setSelected() does not call it. */
    public interface Listener { void onSelected(int index); }

    private static final float RADIUS_DP = 22f, INSET_DP = 2f;

    private final LiquidIndicator drop;
    private final LinearLayout row;
    private final ArrayList<View> cells = new ArrayList<>();
    private final ArrayList<TextView> labels = new ArrayList<>();
    private int selected = -1;
    private Listener listener;

    public SegmentedGlass(Context c) {
        super(c);
        setBackground(Glass.background(RADIUS_DP));
        Glass.mark(this, RADIUS_DP);
        row = Ui.row(c);
        row.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
        DropFrame frame = new DropFrame(c, row);
        drop = frame.drop();
        addView(frame, new LayoutParams(-1, -2));
        addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) { retarget(); }
        });
    }

    public void setItems(String[] labels) {
        int keep = selected;
        row.removeAllViews();
        cells.clear();
        this.labels.clear();
        selected = -1;
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            // the label is centred inside a wrap-sized child of a weighted cell: a centred TextView that is a weighted
            // LinearLayout child gets a layout as wide as the screen and draws off-screen
            FrameLayout cell = new FrameLayout(getContext());
            TextView t = Ui.text(getContext(), labels[i], Ui.BODY, Ui.P.text2);
            t.setSingleLine(true);
            t.setPadding(Ui.dp(8), Ui.dp(9), Ui.dp(8), Ui.dp(9));
            cell.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            cell.setClickable(true);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    setSelected(index);
                    if (listener != null) listener.onSelected(index);
                }
            });
            row.addView(cell, Ui.lpw(1f));
            cells.add(cell);
            this.labels.add(t);
        }
        setSelected(Math.max(0, Math.min(keep, labels.length - 1)));
    }

    /** Moves the drop to the segment (animated once the control is on screen). */
    public void setSelected(int index) {
        if (index == selected || index < 0 || index >= cells.size()) return;
        if (selected >= 0) styleSegment(selected, false);
        selected = index;
        styleSegment(index, true);
        retarget();
    }

    public int selected() { return selected; }

    public void setListener(Listener l) { listener = l; }

    private void styleSegment(int i, boolean on) {
        TextView t = labels.get(i);
        t.setTextColor(on ? Ui.P.text : Ui.P.text2);
        t.setTypeface(on ? Ui.medium : Ui.regular);
    }

    private void retarget() {
        if (selected < 0 || getWidth() == 0) return;
        View t = cells.get(selected);
        float inset = Ui.dpf(INSET_DP);
        drop.moveTo(t.getLeft() + inset, t.getTop() + inset, t.getRight() - inset, t.getBottom() - inset);
    }
}
