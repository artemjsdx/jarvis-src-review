package app.jarvis.ui.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import app.jarvis.ui.Ui;

/** A single placeholder line with a soft highlight sweeping across it; still when motion is reduced. */
public final class ShimmerLine extends View {
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG), sweep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float phase;
    private ValueAnimator anim;

    public ShimmerLine(Context c) { super(c); }

    public void start() {
        if (anim != null || Ui.reduceMotion) return;
        anim = Ui.loop(1400, new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                phase = a.getAnimatedFraction();
                invalidate();
            }
        });
        anim.start();
    }

    public void stop() {
        if (anim != null) anim.cancel();
        anim = null;
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float lineW = w * 0.5f, lineH = Ui.dp(10);
        float left = (w - lineW) / 2f, top = (h - lineH) / 2f;
        rect.set(left, top, left + lineW, top + lineH);
        base.setColor(Ui.alpha(Ui.P.text3, 0.14f));
        c.drawRoundRect(rect, lineH / 2f, lineH / 2f, base);
        float band = lineW * 0.6f;
        float x = left - band + phase * (lineW + band);
        sweep.setShader(new LinearGradient(x, 0, x + band, 0,
            new int[]{Ui.alpha(Ui.P.text, 0f), Ui.alpha(Ui.P.text, 0.20f), Ui.alpha(Ui.P.text, 0f)}, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(rect, lineH / 2f, lineH / 2f, sweep);
    }
}
