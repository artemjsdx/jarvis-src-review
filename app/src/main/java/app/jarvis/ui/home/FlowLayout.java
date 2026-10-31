package app.jarvis.ui.home;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import app.jarvis.ui.Ui;

/** Wraps children into centred rows with 8 dp gaps; every visible child gets the height of the tallest one. */
public final class FlowLayout extends ViewGroup {
    private final int gap;
    private int rowHeight;

    public FlowLayout(Context c) {
        super(c);
        gap = Ui.dp(8);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int avail = width - getPaddingLeft() - getPaddingRight();
        int tallest = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            v.measure(MeasureSpec.makeMeasureSpec(Math.max(0, avail), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            tallest = Math.max(tallest, v.getMeasuredHeight());
        }
        rowHeight = tallest;
        int rows = placeRows(avail, 0, false);
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            v.measure(MeasureSpec.makeMeasureSpec(v.getMeasuredWidth(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY));
        }
        int h = rows == 0 ? 0 : rows * rowHeight + (rows - 1) * gap;
        setMeasuredDimension(width, h + getPaddingTop() + getPaddingBottom());
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        placeRows(r - l - getPaddingLeft() - getPaddingRight(), getPaddingTop(), true);
    }

    /** Same breaking rule for measuring and placing; returns the number of rows. */
    private int placeRows(int avail, int top, boolean apply) {
        int n = getChildCount();
        int rows = 0, y = top, idx = 0;
        while (idx < n) {
            int rowStart = idx, used = 0;
            boolean any = false;
            while (idx < n) {
                View v = getChildAt(idx);
                if (v.getVisibility() == GONE) { idx++; continue; }
                int w = v.getMeasuredWidth();
                int need = (any ? gap : 0) + w;
                if (any && used + need > avail) break;
                used += need;
                any = true;
                idx++;
            }
            if (!any) continue;
            rows++;
            if (apply) {
                int x = getPaddingLeft() + (avail - used) / 2;
                for (int k = rowStart; k < idx; k++) {
                    View v = getChildAt(k);
                    if (v.getVisibility() == GONE) continue;
                    int w = v.getMeasuredWidth();
                    v.layout(x, y, x + w, y + rowHeight);
                    x += w + gap;
                }
            }
            y += rowHeight + gap;
        }
        return rows;
    }
}
