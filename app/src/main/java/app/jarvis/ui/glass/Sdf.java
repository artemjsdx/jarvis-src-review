package app.jarvis.ui.glass;

/**
 * Signed distance to an axis-aligned rounded rectangle: negative inside, zero on the edge, positive outside.
 * The same formula is used in the refraction shader; this copy exists so the geometry can be tested on the JVM.
 */
public final class Sdf {
    private Sdf() { }

    /**
     * @param cx,cy centre of the rectangle
     * @param hx,hy half extents before rounding (the rounded shape is therefore (hx,hy) plus r on every side)
     * @param r corner radius, clamped by the caller to at most min(hx,hy)
     */
    public static float roundedRect(float px, float py, float cx, float cy, float hx, float hy, float r) {
        float qx = Math.abs(px - cx) - hx + r;
        float qy = Math.abs(py - cy) - hy + r;
        float ax = Math.max(qx, 0f), ay = Math.max(qy, 0f);
        return (float) Math.sqrt(ax * ax + ay * ay) + Math.min(Math.max(qx, qy), 0f) - r;
    }
}
