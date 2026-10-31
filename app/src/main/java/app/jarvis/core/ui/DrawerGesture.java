package app.jarvis.core.ui;

/**
 * Decides when a touch becomes a drawer drag. A closed drawer is opened only by a rightward drag that starts inside the
 * left edge zone; an open drawer is closed by a leftward drag from anywhere. The drag must be clearly horizontal so
 * vertical scrolling and horizontal scrolling of code blocks keep their own touches.
 */
public final class DrawerGesture {
    private static final float FLING_PX_PER_S = 800f;

    private final float edgePx, slopPx;
    private float downX, downY;
    private boolean open, armed;

    public DrawerGesture(float edgePx, float slopPx) {
        this.edgePx = edgePx;
        this.slopPx = slopPx;
    }

    public void down(float x, float y, boolean drawerOpen) {
        downX = x;
        downY = y;
        open = drawerOpen;
        armed = drawerOpen || x <= edgePx;
    }

    public boolean claims(float x, float y) {
        if (!armed) return false;
        float dx = x - downX, dy = y - downY;
        if (Math.abs(dx) < slopPx || Math.abs(dx) < 2f * Math.abs(dy)) return false;
        return open ? dx <= -slopPx : dx >= slopPx;
    }

    /** After release: opens when the panel is past halfway, or when it was flung open (or refused a fling closed). */
    public static boolean settleOpen(float fraction, float velocityPxPerS) {
        if (velocityPxPerS > FLING_PX_PER_S) return true;
        if (velocityPxPerS < -FLING_PX_PER_S) return false;
        return fraction >= 0.5f;
    }
}
