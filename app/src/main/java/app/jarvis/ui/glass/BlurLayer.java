package app.jarvis.ui.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.view.View;

/**
 * Backdrop for glass, API 31+. A band of the content layer around the glass surfaces is drawn into a reduced-resolution software
 * snapshot, and one RenderNode with a blur RenderEffect draws that snapshot. Blur runs on the GPU at snapshot size, and every glass
 * surface shares the same node.
 * Measured in the spike (docs/GLASS.md): a child RenderNode inside a RenderEffect renders empty, while content recorded directly
 * into the effect node renders, so the snapshot is recorded as a bitmap draw.
 */
final class BlurLayer {
    private final RenderNode blurNode = new RenderNode("glass.blur");
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF captured = new RectF();
    private Bitmap snap;
    private Canvas snapCanvas;
    private float scale;
    private int width, height;
    private boolean recorded;

    /** Resizes the snapshot for a host of w x h pixels; s = snapshot pixels per host pixel. */
    void resize(int w, int h, float s) {
        if (w == width && h == height && s == scale && snap != null) return;
        width = w;
        height = h;
        scale = s;
        captured.setEmpty();
        recorded = false;
        if (snap != null) snap.recycle();
        snap = Bitmap.createBitmap(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), Bitmap.Config.RGB_565);
        snapCanvas = new Canvas(snap);
    }

    /** True once a capture has been recorded, so drawNode has content. */
    boolean recorded() { return recorded; }

    /** True if the last capture already covers region (host pixels), so no new capture is needed for it. */
    boolean covers(RectF region) { return captured.contains(region); }

    float scale() { return scale; }

    Bitmap snapshot() { return snap; }

    /**
     * Draws the content layer into the part of the snapshot that lies in region (host pixels) and records the blur node.
     * Only that part is drawn, so the cost follows the glass area, not the screen.
     */
    void capture(View content, RectF region, float blurRadiusHostPx) {
        if (snap == null) return;
        snapCanvas.save();
        // Wipe the full reduced-resolution bitmap before clipping the actual capture
        // to the backdrop region plus sampling margin: no stale rectangular strips,
        // and ScrollViews outside the panel do not need to be redrawn.
        snapCanvas.drawColor(0xFF000000, PorterDuff.Mode.SRC);
        snapCanvas.scale(scale, scale);
        // Clip before traversing the expensive screen hierarchy. GlassHost gives
        // this rect a 40dp blur margin; no visible glass path samples the cleared
        // pixels outside it. Clearing the full bitmap above prevents stale strips.
        snapCanvas.clipRect(region.left, region.top, region.right, region.bottom);
        content.draw(snapCanvas);
        snapCanvas.restore();
        captured.set(region);
        int sw = snap.getWidth(), sh = snap.getHeight();
        float r = Math.max(0.5f, blurRadiusHostPx * scale);
        blurNode.setPosition(0, 0, sw, sh);
        blurNode.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
        Canvas rc = blurNode.beginRecording(sw, sh);
        rc.drawBitmap(snap, 0, 0, paint);
        blurNode.endRecording();
        recorded = true;
    }

    /** Fills the clip path (host pixels) with the blurred backdrop. */
    void drawInto(Canvas c, Path clip) {
        c.save();
        c.clipPath(clip);
        drawNode(c);
        c.restore();
    }

    /** Draws the blurred snapshot in host pixels at the canvas origin. */
    void drawNode(Canvas c) {
        c.save();
        c.scale(1f / scale, 1f / scale);
        c.drawRenderNode(blurNode);
        c.restore();
    }
}
