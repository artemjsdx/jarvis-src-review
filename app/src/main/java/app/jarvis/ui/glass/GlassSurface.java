package app.jarvis.ui.glass;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;

/**
 * Rounded glass fill and border. The static wallpaper blur is composited
 * by GlassHost in host coordinates, before UI content is drawn. Drawing it
 * inside this cached background would misalign it while an ancestor scrolls.
 */
public final class GlassSurface extends LayerDrawable {
    private final float radiusDp;
    private final int insetYPx;

    GlassSurface(float radiusDp, Drawable inner, int insetYPx) {
        super(new Drawable[]{inner});
        this.radiusDp = radiusDp;
        this.insetYPx = insetYPx;
        setLayerInset(0, 0, insetYPx, 0, insetYPx);
    }

    public float radiusDp() { return radiusDp; }
    public int insetYPx() { return insetYPx; }
}
