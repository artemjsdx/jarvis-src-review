package app.jarvis.ui.glass;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

/**
 * A {@link LiquidIndicator} drop behind a content view. The frame is as large as its content (wrap), and the drop is sized to the
 * same rectangle, so it never inflates the frame to the size of the parent.
 */
public final class DropFrame extends FrameLayout {
    private final LiquidIndicator drop;
    private final View content;

    public DropFrame(Context c, View content) {
        super(c);
        this.content = content;
        drop = new LiquidIndicator(c);
        addView(drop);
        addView(content);
    }

    /** The drop; aim it at content coordinates (the content is laid out at the frame origin). */
    public LiquidIndicator drop() { return drop; }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        measureChild(content, widthSpec, heightSpec);
        int w = content.getMeasuredWidth(), h = content.getMeasuredHeight();
        drop.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        setMeasuredDimension(w, h);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        drop.layout(0, 0, getWidth(), getHeight());
        content.layout(0, 0, getWidth(), getHeight());
    }
}
