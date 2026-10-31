package app.jarvis.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;

/**
 * App background: near-black (or soft white) vertical gradient with a few large soft lavender light blobs.
 * Rendered ONCE into a quarter-resolution bitmap and scaled: gives the "frosted glass over light" depth the glass
 * panels need, at zero per-frame cost.
 */
public final class Backdrop extends View {
    private Bitmap bmp;
    private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Rect src = new Rect(), dst = new Rect();

    public Backdrop(Context c) { super(c); setWillNotDraw(false); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w <= 0 || h <= 0) return;
        int bw = Math.max(64, w / 4), bh = Math.max(64, h / 4);
        if (bmp != null) bmp.recycle();
        bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        render(new Canvas(bmp), bw, bh);
    }

    private void render(Canvas c, int w, int h) {
        Ui.Pal pal = Ui.P;
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        g.setShader(new LinearGradient(0, 0, 0, h, pal.bg1, pal.bg0, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, g);
        blob(c, w * 0.10f, h * 0.10f, w * 0.95f, Ui.alpha(pal.blobA, pal.dark ? 0.42f : 0.50f));
        blob(c, w * 0.95f, h * 0.88f, w * 1.00f, Ui.alpha(pal.accent2, pal.dark ? 0.24f : 0.30f));
        blob(c, w * 0.92f, h * 0.18f, w * 0.55f, Ui.alpha(pal.blobB, pal.dark ? 0.10f : 0.34f));
        blob(c, w * 0.05f, h * 0.78f, w * 0.60f, Ui.alpha(pal.blobA, pal.dark ? 0.16f : 0.22f));
    }

    private void blob(Canvas c, float cx, float cy, float r, int color) {
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        int a = android.graphics.Color.alpha(color);
        int rgb = color & 0x00FFFFFF;
        // smooth falloff (approximates a gaussian) instead of a linear ramp, which shows as visible rings
        g.setShader(new RadialGradient(cx, cy, r, new int[]{color, (rgb | (Math.round(a * 0.62f) << 24)), (rgb | (Math.round(a * 0.30f) << 24)),
            (rgb | (Math.round(a * 0.10f) << 24)), (rgb | (Math.round(a * 0.02f) << 24)), rgb}, new float[]{0f, 0.22f, 0.45f, 0.68f, 0.88f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, g);
    }

    @Override protected void onDraw(Canvas c) {
        if (bmp == null) return;
        src.set(0, 0, bmp.getWidth(), bmp.getHeight());
        dst.set(0, 0, getWidth(), getHeight());
        c.drawBitmap(bmp, src, dst, p);
    }
}
