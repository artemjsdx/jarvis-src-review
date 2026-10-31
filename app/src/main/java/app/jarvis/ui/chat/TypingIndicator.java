package app.jarvis.ui.chat;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import app.jarvis.ui.Ui;

/** Three dots that take turns brightening: the placeholder while the assistant has not produced text yet. */
public final class TypingIndicator extends View {
    private static final long LOOP_MS = 900;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator anim;
    private float phase;

    public TypingIndicator(Context c) {
        super(c);
        setLayoutParams(new ViewGroup.LayoutParams(Ui.dp(30), Ui.dp(12)));
    }

    public void setActive(boolean on) {
        boolean run = on && !Ui.reduceMotion;
        if (run && anim == null) {
            anim = Ui.loop(LOOP_MS, new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    phase = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        } else if (!run && anim != null) {
            anim.cancel();
            anim = null;
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas cv) {
        float cell = getWidth() / 3f, r = Ui.dpf(3.5f);
        paint.setColor(Ui.P.text2);
        for (int i = 0; i < 3; i++) {
            float a = anim == null ? 0.5f : 0.3f + 0.7f * (float) (0.5 + 0.5 * Math.cos(2 * Math.PI * (phase - i * 0.2f)));
            paint.setAlpha(Math.round(a * 255));
            cv.drawCircle(cell * (i + 0.5f), getHeight() / 2f, r, paint);
        }
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (anim != null) { anim.cancel(); anim = null; }
    }
}
