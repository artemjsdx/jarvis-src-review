package app.jarvis.ui.chat.list;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import app.jarvis.ui.Ui;
import java.util.ArrayList;
import java.util.List;

/**
 * Chat container on a ScrollView. Rows carry stable keys: the row the user is reading stays at the same screen offset
 * when rows are re-rendered or change height above it (expand, collapse, older history). The view follows the bottom
 * only while the user is there.
 */
public final class ChatListView extends ScrollView {
    private static final int NEAR_TOP_DP = 120, BOTTOM_SLACK_DP = 24;

    private final LinearLayout content;
    private View footer;
    private List<Object> keys = new ArrayList<>();
    private Object anchorKey;
    private int anchorOffset;
    private boolean stickPending, internalScroll, hasOlder;
    private Runnable nearTop, scrolled;

    public ChatListView(Context c) {
        super(c);
        setVerticalScrollBarEnabled(false);
        setClipToPadding(false);
        content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        addView(content, new LayoutParams(-1, -2));
    }

    /** Replaces the rows. rowKeys[i] identifies rows.get(i) across renders. */
    public void setRows(List<View> rows, List<Object> rowKeys) {
        captureAnchor();
        content.removeAllViews();
        for (View v : rows) content.addView(v, new LinearLayout.LayoutParams(-1, -2));
        keys = rowKeys;
        if (footer != null) content.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        requestLayout();
    }

    /** Streaming footer, always the last child. */
    public void setFooter(View f) {
        if (footer != null) content.removeView(footer);
        footer = f;
        if (f != null) content.addView(f, new LinearLayout.LayoutParams(-1, -2));
    }

    public void setNearTopListener(Runnable r) { nearTop = r; }

    public void setScrolledListener(Runnable r) { scrolled = r; }

    public void setHasOlder(boolean v) { hasOlder = v; }

    /** Puts the row with this key at the top after the next layout pass. False when no row has the key. */
    public boolean scrollToKey(final Object key) {
        final int i = keys.indexOf(key);
        if (i < 0 || i >= content.getChildCount()) return false;
        post(new Runnable() {
            @Override public void run() { if (i < content.getChildCount()) scrollTo(0, content.getChildAt(i).getTop()); }
        });
        return true;
    }

    public boolean isAtBottom() {
        return ScrollMath.atBottom(getScrollY(), content.getHeight(), getHeight(), Ui.dp(BOTTOM_SLACK_DP));
    }

    /** Before content grows: if the user is at the bottom, the next layout pass moves to the new bottom. */
    public void followIfAtBottom() {
        if (isAtBottom()) { stickPending = true; requestLayout(); }
    }

    public void scrollToBottom(boolean smooth) {
        if (!smooth) { stickPending = true; requestLayout(); return; }
        post(new Runnable() {
            @Override public void run() { smoothScrollTo(0, ScrollMath.maxScroll(content.getHeight(), getHeight())); }
        });
    }

    private void captureAnchor() {
        int y = getScrollY();
        for (int i = 0; i < content.getChildCount() && i < keys.size(); i++) {
            View v = content.getChildAt(i);
            if (v.getBottom() > y) {
                anchorKey = keys.get(i);
                anchorOffset = v.getTop() - y;
                return;
            }
        }
        anchorKey = null;
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (stickPending) {
            stickPending = false;
            setInternalScroll(ScrollMath.maxScroll(content.getHeight(), getHeight()));
            captureAnchor();
            return;
        }
        if (anchorKey == null) return;
        int i = keys.indexOf(anchorKey);
        if (i < 0 || i >= content.getChildCount()) return;
        setInternalScroll(ScrollMath.anchoredScroll(content.getChildAt(i).getTop(), anchorOffset, content.getHeight(), getHeight()));
    }

    @Override protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (!internalScroll) captureAnchor();
        if (hasOlder && nearTop != null && t < Ui.dp(NEAR_TOP_DP)) nearTop.run();
        if (scrolled != null) scrolled.run();
    }

    private void setInternalScroll(int y) {
        internalScroll = true;
        scrollTo(0, y);
        internalScroll = false;
    }
}
