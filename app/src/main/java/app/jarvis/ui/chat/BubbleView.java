package app.jarvis.ui.chat;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.ui.Ui;

/**
 * A message bubble sized from the width it is given, not from global screen metrics. Its width is the widest of the
 * text, the attachments and the meta line (model label + time), never less than the meta line, never more than 78% of
 * the screen or the available width. Children are measured with explicit specs before the final pass, so no child's
 * wrap-content width is decided by a parent that has not measured it yet.
 */
public final class BubbleView extends LinearLayout {
    private static final float MAX_SCREEN_FRACTION = 0.78f;

    public final LinearLayout atts, meta;
    public final ChatTextView text;
    public final ImageView src;
    public final TextView model, time;

    public BubbleView(Context c) {
        super(c);
        setOrientation(VERTICAL);
        atts = Ui.column(c);
        addView(atts, new LayoutParams(-1, -2));
        text = new ChatTextView(c);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(Ui.P.text);
        text.setLineSpacing(0, 1.18f);
        addView(text, new LayoutParams(-2, -2));
        meta = Ui.row(c);
        meta.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        src = Ui.icon(c, R.drawable.ic_mic, 11, Ui.P.accent);
        meta.addView(src);
        model = Ui.text(c, "", Ui.CAPTION, Ui.P.text3);
        model.setSingleLine(true);
        model.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        meta.addView(model, Ui.lpm(-2, -2, 0, 0, 0, 0));
        time = Ui.text(c, "", Ui.CAPTION, Ui.P.text3);
        meta.addView(time, Ui.lpm(-2, -2, Ui.dp(6), 0, 0, 0));
        LayoutParams mp = new LayoutParams(-2, -2);
        mp.topMargin = Ui.dp(4);
        mp.gravity = Gravity.END;
        addView(meta, mp);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int avail = View.MeasureSpec.getMode(widthSpec) == View.MeasureSpec.UNSPECIFIED
            ? Integer.MAX_VALUE : View.MeasureSpec.getSize(widthSpec);
        int screen = getResources().getDisplayMetrics().widthPixels;
        int limit = screen > 0 ? Math.round(screen * MAX_SCREEN_FRACTION) : avail;
        int maxBubble = Math.min(avail, limit);
        int pad = getPaddingLeft() + getPaddingRight();
        int maxContent = Math.max(0, maxBubble - pad);
        int atMost = View.MeasureSpec.makeMeasureSpec(maxContent, View.MeasureSpec.AT_MOST);
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        atts.measure(atMost, unspecified);
        text.measure(atMost, unspecified);
        meta.measure(atMost, unspecified);
        int content = Math.max(text.getMeasuredWidth(), Math.max(atts.getMeasuredWidth(), meta.getMeasuredWidth()));
        int width = Math.min(maxBubble, Math.min(content, maxContent) + pad);
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), heightSpec);
    }
}
