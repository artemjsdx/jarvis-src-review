package app.jarvis.ui.glass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.view.Choreographer;
import android.view.View;
import app.jarvis.ui.Ui;

/**
 * The liquid drop: a pill that flows between items on springs (x0, y0, x1, y1 each on a {@link Spring}), stretches along its
 * horizontal velocity while keeping its area, and is refracted from the blurred backdrop on API 33+ at level FULL (the host draws
 * the lens, see {@link GlassHost.Lens}). Without refraction it is a translucent tint with a rim light.
 * Place it under the items of a container whose bounds are the drop's coordinate space.
 */
public final class LiquidIndicator extends View implements GlassHost.Lens {
    private static final float STRETCH_PER_PX_PER_S = 1f / 2600f;
    private static final float MAX_STRETCH = 0.16f;

    private final Spring left = new Spring(), top = new Spring(), right = new Spring(), bottom = new Spring();
    private final RectF shape = new RectF();
    private final RectF hostRect = new RectF();
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean placed;
    private boolean running;
    private long lastFrameNanos;
    private LensPass lens;

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            running = false;
            long dt = lastFrameNanos == 0 ? 16_666_666L : frameTimeNanos - lastFrameNanos;
            lastFrameNanos = frameTimeNanos;
            if (advance(dt / 1e9f)) startFrames();
            else lastFrameNanos = 0;
        }
    };

    public LiquidIndicator(Context c) {
        super(c);
        setWillNotDraw(false);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, Ui.dpf(1f)));
    }

    /** Sets the drop's rectangle in this view's coordinates. The first call places it without animation. */
    public void moveTo(float l, float t, float r, float b) {
        left.setTarget(l);
        top.setTarget(t);
        right.setTarget(r);
        bottom.setTarget(b);
        if (!placed || Ui.reduceMotion) {
            left.snap(l);
            top.snap(t);
            right.snap(r);
            bottom.snap(b);
            placed = true;
            invalidate();
        } else if (isAttachedToWindow()) {
            startFrames();
        }
    }

    /** Steps all springs by seconds; returns true while the drop still moves. Used by the frame callback and by previews. */
    public boolean advance(float seconds) {
        boolean moving = left.step(seconds) | top.step(seconds) | right.step(seconds) | bottom.step(seconds);
        invalidate();
        return moving;
    }

    private void startFrames() {
        if (running || !isAttachedToWindow()) return;
        running = true;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!left.atRest() || !top.atRest() || !right.atRest() || !bottom.atRest()) startFrames();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (running) Choreographer.getInstance().removeFrameCallback(frame);
        running = false;
        lastFrameNanos = 0;
    }

    /** The drop's shape right now, in this view's coordinates: stretched along the motion, area preserved. */
    void shapeNow(RectF out) {
        float l = left.value(), t = top.value(), r = right.value(), b = bottom.value();
        float w = Math.max(1f, r - l), h = Math.max(1f, b - t);
        float vx = (left.velocity() + right.velocity()) * 0.5f;
        float stretch = 1f + Math.min(MAX_STRETCH, Math.abs(vx) * STRETCH_PER_PX_PER_S);
        float w2 = w * stretch, h2 = h / stretch;
        float cx = (l + r) / 2f, cy = (t + b) / 2f;
        out.set(cx - w2 / 2f, cy - h2 / 2f, cx + w2 / 2f, cy + h2 / 2f);
    }

    private boolean refractedByHost() {
        return GlassLevel.refraction() && Glass.hostOf(this) != null;
    }

    @Override protected void onDraw(Canvas c) {
        if (!placed || getWidth() == 0) return;
        shapeNow(shape);
        float radius = Math.min(shape.width(), shape.height()) / 2f;
        boolean dark = Ui.P.dark;
        if (!refractedByHost()) {
            tint.setColor(Ui.P.glassTop);
            c.drawRoundRect(shape, radius, radius, tint);
        }
        rim.setColor(dark ? 0x59FFFFFF : 0xB3FFFFFF);
        float inset = rim.getStrokeWidth() / 2f;
        shape.inset(inset, inset);
        c.drawRoundRect(shape, Math.max(0f, radius - inset), Math.max(0f, radius - inset), rim);
        shape.inset(-inset, -inset);
    }

    @Override public void drawLens(Canvas c, GlassHost host, Bitmap snapshot, float snapshotScale) {
        if (!placed || snapshot == null || Build.VERSION.SDK_INT < 33 || !GlassLevel.refraction() || getWidth() == 0) return;
        if (lens == null) lens = new LensPass();
        Glass.rectInHost(this, host, hostRect);
        shapeNow(shape);
        float radius = Math.min(shape.width(), shape.height()) / 2f;
        lens.draw(c, snapshot, snapshotScale, hostRect.left, hostRect.top, getWidth(), getHeight(), shape, radius);
    }
}
