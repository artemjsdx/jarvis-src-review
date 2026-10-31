package app.jarvis.ui.glass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import app.jarvis.ui.Ui;
import java.util.ArrayList;

/**
 * Two layers: content (screens, backdrop) below, overlay (dock, dialogs, toasts, floating buttons) above.
 * Overlay glass blurs the whole content: each frame where overlay glass exists, the content is captured into a snapshot (throttled),
 * the glass surfaces share one blur pass clipped to their union, and the overlay is drawn on top.
 * Content-layer glass blurs the wallpaper (the view given to {@link #setBackdrop}), captured once per size because it does not move.
 * Below API 31 or at level OFF the host is a plain FrameLayout.
 */
public final class GlassHost extends FrameLayout {
    /** Lens hook: a view that wants its body refracted from the backdrop, drawn by the host before the overlay. */
    public interface Lens {
        /** Draws the lens into host coordinates; the snapshot is the backdrop at scale s. */
        void drawLens(Canvas c, GlassHost host, Bitmap snapshot, float snapshotScale);
    }

    private static final float BLUR_DP = 16f;
    private static final float CAPTURE_MARGIN_DP = 40f;
    private static final long THROTTLE_FULL_MS = 40, THROTTLE_REDUCED_MS = 120;
    private static final float SCALE_FULL = 0.25f, SCALE_REDUCED = 0.16f;

    private final FrameLayout content, overlay;
    private final BlurLayer blur;
    private final BlurLayer wallpaper;
    private final FrameGovernor governor = new FrameGovernor();
    private final Path clip = new Path(), surfacePath = new Path();
    private final RectF rect = new RectF(), region = new RectF();
    private final PointF origin = new PointF();
    private final ArrayList<View> glass = new ArrayList<>();
    private final ArrayList<Lens> lenses = new ArrayList<>();
    private View backdrop;
    private boolean wallpaperWanted;
    private long lastCaptureMs = -1_000_000L;
    private final Runnable recapture = new Runnable() { @Override public void run() { invalidate(); } };

    public GlassHost(Context c) {
        super(c);
        content = new FrameLayout(c);
        overlay = new FrameLayout(c);
        overlay.setClipChildren(false);
        addView(content, new FrameLayout.LayoutParams(-1, -1));
        addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        boolean api31 = Build.VERSION.SDK_INT >= 31;
        blur = api31 ? new BlurLayer() : null;
        wallpaper = api31 ? new BlurLayer() : null;
    }

    /** Screens and the backdrop go here. */
    public FrameLayout content() { return content; }

    /** Dock, dialogs, toasts and floating buttons go here. */
    public FrameLayout overlay() { return overlay; }

    /** The static wallpaper that content-layer glass blurs. It must not move or animate. */
    public void setBackdrop(View v) {
        backdrop = v;
        wallpaperWanted = true;
    }

    @Override protected void dispatchDraw(Canvas c) {
        if (blur == null || !c.isHardwareAccelerated() || !GlassLevel.blur()) { super.dispatchDraw(c); return; }
        glass.clear();
        lenses.clear();
        clip.rewind();
        region.setEmpty();
        collect(overlay);
        long t0 = System.nanoTime();
        if (wallpaperWanted) captureWallpaper();
        long t1 = System.nanoTime();
        if (glass.isEmpty()) { super.dispatchDraw(c); return; }
        drawChild(c, content, getDrawingTime());
        long t2 = System.nanoTime();
        drawGlass(c);
        long t3 = System.nanoTime();
        drawChild(c, overlay, getDrawingTime());
        if (GlassLevel.preferred() == GlassLevel.AUTO) {
            double costMs = ((t1 - t0) + (t3 - t2)) / 1e6;
            if (governor.onFrame(t0 / 1e6, costMs)) { GlassLevel.stepDown(); invalidate(); }
        }
    }

    private void captureWallpaper() {
        wallpaperWanted = false;
        int w = getWidth(), h = getHeight();
        if (backdrop == null || w <= 0 || h <= 0) return;
        wallpaper.resize(w, h, GlassLevel.effective() == GlassLevel.FULL ? SCALE_FULL : SCALE_REDUCED);
        rect.set(0, 0, w, h);
        if (!wallpaper.covers(rect)) wallpaper.capture(backdrop, rect, Ui.dpf(BLUR_DP));
    }

    /**
     * Blurs the wallpaper under a content-layer glass surface; called from {@link GlassSurface#draw} with the surface's rectangle in
     * its own coordinates. Overlay surfaces are skipped: the overlay pass blurs them.
     */
    void drawBackdrop(Canvas c, View v, float left, float top, float right, float bottom, float radiusPx) {
        if (wallpaper == null || !c.isHardwareAccelerated() || !GlassLevel.blur() || isInOverlay(v)) return;
        if (!wallpaper.recorded()) {
            wallpaperWanted = true;
            invalidate();
            return;
        }
        Glass.originInHost(v, this, origin);
        c.save();
        surfacePath.rewind();
        surfacePath.addRoundRect(left, top, right, bottom, radiusPx, radiusPx, Path.Direction.CW);
        c.clipPath(surfacePath);
        c.translate(-origin.x, -origin.y);
        wallpaper.drawNode(c);
        c.restore();
    }

    private boolean isInOverlay(View v) {
        for (View cur = v; cur != null && cur != this; cur = cur.getParent() instanceof View ? (View) cur.getParent() : null) {
            if (cur == overlay) return true;
        }
        return false;
    }

    private void drawGlass(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || region.isEmpty()) return;
        boolean full = GlassLevel.effective() == GlassLevel.FULL;
        float s = full ? SCALE_FULL : SCALE_REDUCED;
        long throttle = full ? THROTTLE_FULL_MS : THROTTLE_REDUCED_MS;
        blur.resize(w, h, s);
        float margin = Ui.dpf(CAPTURE_MARGIN_DP);
        region.set(0, Math.max(0, region.top - margin), w, Math.min(h, region.bottom + margin));
        long now = SystemClock.uptimeMillis();
        if (!blur.covers(region) || now - lastCaptureMs >= throttle) {
            lastCaptureMs = now;
            blur.capture(content, region, Ui.dpf(BLUR_DP));
        } else {
            postDelayed(recapture, throttle - (now - lastCaptureMs));
        }
        if (!blur.recorded()) return;
        blur.drawInto(c, clip);
        for (Lens lens : lenses) lens.drawLens(c, this, blur.snapshot(), blur.scale());
    }

    /** Gathers overlay glass surfaces (and lenses) in overlay coordinates; adds their shapes to the clip path. */
    private void collect(View v) {
        if (v.getVisibility() != VISIBLE || v.getAlpha() <= 0f) return;
        Glass.Mark m = Glass.markOf(v);
        if (m != null && v.getWidth() > 0 && v.getHeight() > 0) {
            Glass.rectInHost(v, this, rect);
            float r = Math.min(Ui.dpf(m.radiusDp) * v.getScaleX(), Math.min(rect.width(), rect.height()) / 2f);
            clip.addRoundRect(rect, r, r, Path.Direction.CW);
            if (region.isEmpty()) region.set(rect); else region.union(rect);
            glass.add(v);
        }
        if (v instanceof Lens) lenses.add((Lens) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0, n = g.getChildCount(); i < n; i++) collect(g.getChildAt(i));
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        lastCaptureMs = -1_000_000L;
        wallpaperWanted = true;
    }
}
