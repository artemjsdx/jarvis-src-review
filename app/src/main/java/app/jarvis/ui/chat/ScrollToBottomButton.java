package app.jarvis.ui.chat;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.ui.Ui;

/** Chevron in a glass circle that jumps the chat to the newest message; a counter shows what arrived below. */
public final class ScrollToBottomButton extends FrameLayout {
    private static final long ANIM_MS = 200;
    private static final int SIZE_DP = 44;

    private final TextView badge;
    private boolean shown;

    public ScrollToBottomButton(Context c) {
        super(c);
        setLayoutParams(new FrameLayout.LayoutParams(Ui.dp(SIZE_DP), Ui.dp(SIZE_DP), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        setBackground(Ui.glassPressable(SIZE_DP / 2f));
        setClickable(true);
        setFocusable(true);
        setContentDescription("Вниз");
        addView(Ui.icon(c, R.drawable.ic_chevron_down, 20, Ui.P.text), new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        badge = Ui.text(c, "", Ui.CAPTION, Ui.P.onAccent);
        badge.setTypeface(Ui.medium);
        badge.setPadding(Ui.dp(5), 0, Ui.dp(5), 0);
        badge.setBackground(Ui.accentFill(9));
        badge.setGravity(Gravity.CENTER);
        badge.setVisibility(GONE);
        addView(badge, new FrameLayout.LayoutParams(-2, Ui.dp(18), Gravity.TOP | Gravity.END));
        setVisibility(GONE);
    }

    public void show(boolean on) {
        if (on == shown) return;
        shown = on;
        animate().cancel();
        if (on) {
            setVisibility(VISIBLE);
            setAlpha(0f);
            setScaleX(0.8f);
            setScaleY(0.8f);
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Ui.ms(ANIM_MS)).start();
        } else {
            animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(Ui.ms(ANIM_MS)).withEndAction(new Runnable() {
                @Override public void run() { if (!shown) setVisibility(GONE); }
            }).start();
        }
    }

    public void setUnread(int n) {
        badge.setText(n > 99 ? "99+" : String.valueOf(n));
        badge.setVisibility(n > 0 ? VISIBLE : GONE);
    }
}
