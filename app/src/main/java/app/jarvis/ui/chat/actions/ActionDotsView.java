package app.jarvis.ui.chat.actions;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import app.jarvis.ui.Ui;

/**
 * Running indicator: nine dots in a 3x3 square; the brightness wave runs along a spiral (snake order), one lap per
 * 1.1 s. With reduced motion it is a static grey square.
 */
public final class ActionDotsView extends View {
    private static final int[] SNAKE = {0, 1, 2, 5, 8, 7, 6, 3, 4};
    private static final long LOOP_MS = 1100;

    private final int color;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator anim;
    private float phase;

    public ActionDotsView(Context c, float sizeDp, int color) {
        super(c);
        this.color = color;
        setLayoutParams(new ViewGroup.LayoutParams(Ui.dp(sizeDp), Ui.dp(sizeDp)));
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
        float cell = getWidth() / 3f, r = cell * 0.2f;
        boolean still = anim == null;
        paint.setColor(still && Ui.reduceMotion ? Ui.P.text3 : color);
        for (int k = 0; k < SNAKE.length; k++) {
            int idx = SNAKE[k];
            float cx = (idx % 3 + 0.5f) * cell, cy = (idx / 3 + 0.5f) * cell;
            float a = still ? 0.45f : 0.25f + 0.75f * (float) (0.5 + 0.5 * Math.cos(2 * Math.PI * (phase - k / 9f)));
            paint.setAlpha(Math.round(a * 255));
            cv.drawCircle(cx, cy, r, paint);
        }
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (anim != null) { anim.cancel(); anim = null; }
    }
}
