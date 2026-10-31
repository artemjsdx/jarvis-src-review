package app.jarvis.ui.glass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Rect;
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
    private final Path clip = new Path(), contentClip = new Path();
    private final Path surfacePath = new Path(), viewportPath = new Path();
    private final RectF rect = new RectF(), region = new RectF(), surfaceBounds = new RectF();
    private final Rect overlayVisible = new Rect();
    private final int[] hostOnScreen = new int[2];
    private final ArrayList<View> glass = new ArrayList<>();
    private final ArrayList<Lens> lenses = new ArrayList<>();
    private View backdrop;
    private boolean wallpaperWanted;
    private long lastCaptureMs = -1_000_000L;
    private final Runnable recapture = new Runnable() { @Override public void run() { invalidate(); } };

    public GlassHost(Context c) {
        super(c);
        content = new FrameLayout(c) {
            @Override protected void dispatchDraw(Canvas canvas) {
                // Draw the static wallpaper first, then its frosted regions in HOST
                // coordinates, before the screen controls and text. A card's own
                // RenderNode is cached while scrolling: recording host-space
                // coordinates inside that node caused a dragged rectangular ghost.
                // Software captures for floating overlays use the normal path.
                if (!canvas.isHardwareAccelerated() || wallpaper == null
                        || !wallpaper.recorded() || !GlassLevel.blur() || getChildCount() == 0) {
                    super.dispatchDraw(canvas);
                    return;
                }
                long drawingTime = getDrawingTime();
                drawChild(canvas, getChildAt(0), drawingTime);
                drawContentGlass(canvas);
                for (int i = 1; i < getChildCount(); i++)
                    drawChild(canvas, getChildAt(i), drawingTime);
            }
        };
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

    /** Re-record the host-space glass mask whenever the viewport scrolls. */
    public void invalidateContentGlass() { content.invalidate(); }

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
        getLocationOnScreen(hostOnScreen);
        clip.rewind();
        region.setEmpty();
        collect(overlay);
        long t0 = System.nanoTime();
        if (wallpaperWanted || (wallpaper != null && wallpaper.recorded()
                && wallpaper.scale() != (GlassLevel.effective() == GlassLevel.FULL ? SCALE_FULL : SCALE_REDUCED)))
            captureWallpaper();
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
        // A theme/quality change can keep the same dimensions, yet needs a new
        // capture. wallpaperWanted means the old snapshot is not authoritative.
        wallpaper.capture(backdrop, rect, Ui.dpf(BLUR_DP));
    }

    /**
     * Finds content-layer surfaces in current host coordinates, including ancestor
     * ScrollView clips. These shapes are computed at draw time instead of cached
     * inside a child Drawable's display list. No screen-space bitmap sampling is
     * recorded in a card, so a fling cannot drag a stale piece of wallpaper.
     */
    private void drawContentGlass(Canvas c) {
        if (wallpaper == null || !wallpaper.recorded()) return;
        contentClip.rewind();
        collectContentGlass(content, 0, 0, getWidth(), getHeight());
        if (!contentClip.isEmpty()) wallpaper.drawInto(c, contentClip);
    }

    private void collectContentGlass(View v, float clipL, float clipT, float clipR, float clipB) {
        if (v.getVisibility() != VISIBLE || v.getAlpha() <= 0f || clipR <= clipL || clipB <= clipT) return;
        float left = clipL, top = clipT, right = clipR, bottom = clipB;
        if (v != content) {
            Glass.rectInHost(v, this, surfaceBounds);
            final float vl = surfaceBounds.left, vt = surfaceBounds.top;
            final float vr = surfaceBounds.right, vb = surfaceBounds.bottom;
            if (v.getBackground() instanceof GlassSurface
                    && vr > clipL && vb > clipT && vl < clipR && vt < clipB) {
                GlassSurface surface = (GlassSurface) v.getBackground();
                float inset = surface.insetYPx();
                float r = Math.min(Ui.dpf(surface.radiusDp()), Math.min(vr - vl, vb - vt) / 2f);
                surfacePath.rewind();
                surfacePath.addRoundRect(vl, vt + inset, vr, vb - inset, r, r, Path.Direction.CW);
                viewportPath.rewind();
                viewportPath.addRect(clipL, clipT, clipR, clipB, Path.Direction.CW);
                if (surfacePath.op(viewportPath, Path.Op.INTERSECT))
                    contentClip.addPath(surfacePath);
            }
            if (v instanceof ViewGroup && ((ViewGroup) v).getClipChildren()) {
                left = Math.max(clipL, vl);
                top = Math.max(clipT, vt);
                right = Math.min(clipR, vr);
                bottom = Math.min(clipB, vb);
            }
        }
        if (v instanceof ViewGroup && right > left && bottom > top) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++)
                collectContentGlass(group.getChildAt(i), left, top, right, bottom);
        }
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

    /** Shared blur for marked overlay panels AND every nested GlassSurface. */
    private void collect(View v) {
        if (v.getVisibility() != VISIBLE || v.getAlpha() <= 0f) return;
        Glass.Mark m = Glass.markOf(v);
        GlassSurface surface = v.getBackground() instanceof GlassSurface
            ? (GlassSurface) v.getBackground() : null;
        if ((m != null || surface != null) && v.getWidth() > 0 && v.getHeight() > 0) {
            Glass.rectInHost(v, this, rect);
            float radiusDp = m != null ? m.radiusDp : surface.radiusDp();
            float radius = Math.min(Ui.dpf(radiusDp) * v.getScaleX(),
                Math.min(rect.width(), rect.height()) / 2f);
            // Clip to the real on-screen viewport of scroll containers and sheets.
            // An off-screen child must not enlarge the blur capture area.
            if (v.getGlobalVisibleRect(overlayVisible)) {
                surfacePath.rewind();
                surfacePath.addRoundRect(rect, radius, radius, Path.Direction.CW);
                viewportPath.rewind();
                viewportPath.addRect(overlayVisible.left - hostOnScreen[0],
                    overlayVisible.top - hostOnScreen[1],
                    overlayVisible.right - hostOnScreen[0],
                    overlayVisible.bottom - hostOnScreen[1], Path.Direction.CW);
                if (surfacePath.op(viewportPath, Path.Op.INTERSECT) && !surfacePath.isEmpty()) {
                    clip.addPath(surfacePath);
                    rect.intersect(overlayVisible.left - hostOnScreen[0], overlayVisible.top - hostOnScreen[1],
                        overlayVisible.right - hostOnScreen[0], overlayVisible.bottom - hostOnScreen[1]);
                    if (region.isEmpty()) region.set(rect); else region.union(rect);
                    glass.add(v);
                }
            }
        }
        if (v instanceof Lens) lenses.add((Lens) v);
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i));
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        lastCaptureMs = -1_000_000L;
        wallpaperWanted = true;
    }
}
