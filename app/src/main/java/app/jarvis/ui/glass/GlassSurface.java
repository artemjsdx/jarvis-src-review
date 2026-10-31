package app.jarvis.ui.glass;

import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import app.jarvis.ui.Ui;

/**
 * Background of a glass surface. In the content layer it blurs the wallpaper behind it ({@link GlassHost#drawBackdrop}); in the
 * overlay layer the host's overlay pass already blurs everything behind the surface, so it draws only its fill.
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

    @Override public void draw(Canvas c) {
        View v = Glass.viewOf(this);
        GlassHost host = v == null ? null : Glass.hostOf(v);
        if (host != null) host.drawBackdrop(c, v, getBounds().left, getBounds().top + insetYPx, getBounds().right, getBounds().bottom - insetYPx,
            Ui.dpf(radiusDp));
        super.draw(c);
    }
}
