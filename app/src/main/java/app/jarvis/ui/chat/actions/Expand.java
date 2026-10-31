package app.jarvis.ui.chat.actions;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;

/** Animated show/hide of a block by its height. A reversal mid-animation continues from the current height. */
public final class Expand {
    private Expand() { }

    public static void set(final View v, final boolean show, long ms) {
        Object running = v.getTag();
        if (running instanceof ValueAnimator) ((ValueAnimator) running).cancel();
        boolean visible = v.getVisibility() == View.VISIBLE;
        if (!show && !visible) return;
        View parent = v.getParent() instanceof View ? (View) v.getParent() : null;
        int width = parent == null ? 0 : parent.getWidth();
        if (ms <= 0 || width == 0) {
            v.setVisibility(show ? View.VISIBLE : View.GONE);
            return;
        }
        v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int target = v.getMeasuredHeight();
        final int from = visible ? v.getHeight() : 0;
        final ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.height = from;
        v.setLayoutParams(lp);
        v.setVisibility(View.VISIBLE);
        ValueAnimator a = ValueAnimator.ofInt(from, show ? target : 0);
        a.setDuration(ms);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator an) {
                lp.height = (Integer) an.getAnimatedValue();
                v.setLayoutParams(lp);
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) {
                v.setTag(null);
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                if (!show) v.setVisibility(View.GONE);
                v.setLayoutParams(lp);
            }
        });
        v.setTag(a);
        a.start();
    }
}
