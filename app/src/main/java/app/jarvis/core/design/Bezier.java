package app.jarvis.core.design;

/** Cubic Bezier easing curve (CSS-style control points x1,y1,x2,y2), evaluated for progress x in [0,1]. */
public final class Bezier {
    private final double ax, bx, cx, ay, by, cy;

    public Bezier(double x1, double y1, double x2, double y2) {
        cx = 3 * x1; bx = 3 * (x2 - x1) - cx; ax = 1 - cx - bx;
        cy = 3 * y1; by = 3 * (y2 - y1) - cy; ay = 1 - cy - by;
    }

    /** Eased progress for linear progress x. Clamped to [0,1]; monotone when the control points are in [0,1]. */
    public double at(double x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        double lo = 0, hi = 1, s = x;
        for (int i = 0; i < 50; i++) {
            double err = sampleX(s) - x;
            if (Math.abs(err) < 1e-7) break;
            if (err < 0) lo = s; else hi = s;
            s = (lo + hi) / 2;
        }
        return Math.max(0, Math.min(1, sampleY(s)));
    }

    private double sampleX(double s) { return ((ax * s + bx) * s + cx) * s; }

    private double sampleY(double s) { return ((ay * s + by) * s + cy) * s; }
}
