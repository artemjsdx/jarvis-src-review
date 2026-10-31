package app.jarvis.ui.glass;

import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import app.jarvis.ui.GlassDrawable;
import app.jarvis.ui.Host;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Ui;

/**
 * Public entry points of the glass kit. A glass surface is a view whose background is a {@link GlassSurface}. In the content
 * layer it blurs the wallpaper behind it; a glass view in the overlay layer (see {@link #floating}, {@link #pinBottomEnd}, dialogs)
 * blurs everything behind it, including the screen content.
 */
public final class Glass {
    private Glass() { }

    /** Tag stored on an overlay glass view: its corner radius. */
    public static final class Mark {
        final float radiusDp;

        Mark(float radiusDp) { this.radiusDp = radiusDp; }
    }

    /** Translucent tint plus the glass border: the standard background of a glass surface. */
    public static Drawable background(float radiusDp) {
        return surface(radiusDp, new LayerDrawable(new Drawable[]{Ui.rect(Ui.P.dark ? 0x59121018 : 0x80FFFFFF, radiusDp), new GlassDrawable(radiusDp, true)}), 0);
    }

    /** A glass background around inner (fill, border, ripple). insetYPx shrinks the surface vertically, as cards are drawn inset. */
    public static GlassSurface surface(float radiusDp, Drawable inner, int insetYPx) {
        return new GlassSurface(radiusDp, inner, insetYPx);
    }

    /** Declares v as an overlay glass surface with the given corner radius (dp). */
    public static void mark(View v, float radiusDp) { v.setTag(new Mark(radiusDp)); }

    static Mark markOf(View v) {
        Object t = v.getTag();
        return t instanceof Mark ? (Mark) t : null;
    }

    /** The GlassHost this view is drawn in, or null (no blur, plain fallback). */
    public static GlassHost hostOf(View v) {
        for (ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p instanceof GlassHost) return (GlassHost) p;
        return null;
    }

    /** The view a drawable is attached to (through wrapping drawables such as ripples), or null. */
    static View viewOf(Drawable d) {
        Drawable.Callback cb = d.getCallback();
        while (cb instanceof Drawable) cb = ((Drawable) cb).getCallback();
        return cb instanceof View ? (View) cb : null;
    }

    /**
     * Adds a floating glass view (a button, a pill) to the overlay layer above the screen. Margins are in dp and gravity is a
     * Gravity constant.
     */
    public static void floating(Host host, View v, int gravity, int leftDp, int topDp, int rightDp, int bottomDp, float radiusDp) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            gravity == 0 ? Gravity.BOTTOM | Gravity.END : gravity);
        lp.leftMargin = Ui.dp(leftDp);
        lp.topMargin = Ui.dp(topDp);
        lp.rightMargin = Ui.dp(rightDp);
        lp.bottomMargin = Ui.dp(bottomDp);
        mark(v, radiusDp);
        if (v.getBackground() == null) v.setBackground(background(radiusDp));
        host.overlays().addView(v, lp);
    }

    /**
     * Keeps v (a square of sizeDp) over the bottom-end corner of anchor, a layout placeholder in the content layer, and draws it in
     * the overlay layer so it blurs the chat behind it. v is attached while anchor is attached to a window.
     */
    public static void pinBottomEnd(final Host host, final View v, final View anchor, int sizeDp, final int marginDp) {
        final int size = Ui.dp(sizeDp), margin = Ui.dp(marginDp);
        final OverlayHost overlay = host.overlays();
        final PointF origin = new PointF();
        final ViewTreeObserver.OnPreDrawListener sync = new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                GlassHost gh = hostOf(anchor);
                if (gh == null) return true;
                originInHost(anchor, gh, origin);
                v.setTranslationX(origin.x + anchor.getWidth() - size - margin);
                v.setTranslationY(origin.y + anchor.getHeight() - size - margin);
                return true;
            }
        };
        final ViewTreeObserver[] vto = {null};
        final Runnable attach = new Runnable() {
            @Override public void run() {
                if (v.getParent() == null) overlay.addView(v);
                vto[0] = anchor.getViewTreeObserver();
                vto[0].addOnPreDrawListener(sync);
            }
        };
        mark(v, sizeDp / 2f);
        v.setLayoutParams(new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.START));
        anchor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View a) { attach.run(); }

            @Override public void onViewDetachedFromWindow(View a) {
                if (vto[0] != null && vto[0].isAlive()) vto[0].removeOnPreDrawListener(sync);
                vto[0] = null;
                if (v.getParent() == overlay) overlay.removeView(v);
            }
        });
        if (anchor.isAttachedToWindow()) attach.run();
    }

    /**
     * Writes the top-left corner of v in the coordinates of the host (ancestor view). Translation and the scroll of every ancestor are
     * applied, so the point is where the view is drawn; the view's own scale is not.
     */
    static void originInHost(View v, View host, PointF out) {
        float x = 0, y = 0;
        View cur = v;
        while (cur != null && cur != host) {
            x += cur.getLeft() + cur.getTranslationX();
            y += cur.getTop() + cur.getTranslationY();
            ViewParent p = cur.getParent();
            if (p instanceof View) {
                x -= ((View) p).getScrollX();
                y -= ((View) p).getScrollY();
            }
            cur = p instanceof View ? (View) p : null;
        }
        out.set(x, y);
    }

    /** Rectangle of v in the coordinates of the host; the view's own scale is applied around its centre. */
    static void rectInHost(View v, View host, RectF out) {
        PointF o = new PointF();
        originInHost(v, host, o);
        float cx = o.x + v.getWidth() / 2f, cy = o.y + v.getHeight() / 2f;
        float hw = v.getWidth() * v.getScaleX() / 2f, hh = v.getHeight() * v.getScaleY() / 2f;
        out.set(cx - hw, cy - hh, cx + hw, cy + hh);
    }
}
