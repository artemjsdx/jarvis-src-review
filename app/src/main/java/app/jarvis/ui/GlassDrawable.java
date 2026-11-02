package app.jarvis.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * "Liquid glass" without runtime blur (too costly on a weak GPU): a vertical translucent gradient fill, a diagonal
 * gradient hairline border (bright at the lit corner, faint opposite), and a 1px inner top highlight. Drawn from
 * cached shaders; no allocation in draw().
 */
public final class GlassDrawable extends Drawable {
    // Neutral frosting is above the wallpaper glow but below the glass highlight,
    // text and controls. Without it the purple wallpaper shows through at full strength.
    private final Paint underlay = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), border = new Paint(Paint.ANTI_ALIAS_FLAG), hi = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF(), inner = new RectF();
    private final float radius, stroke;
    private final boolean strong;
    private boolean overlayFrost;
    private int glow;

    public GlassDrawable(float radiusDp, boolean strong) {
        this.radius = Ui.dpf(radiusDp);
        this.stroke = Math.max(1f, Ui.dpf(0.9f));
        this.strong = strong;
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(stroke);
        hi.setStyle(Paint.Style.STROKE);
        hi.setStrokeWidth(Math.max(1f, Ui.dpf(0.6f)));
    }

    /** Overlay panels should reveal the genuinely blurred screen instead of layering two almost opaque fills. */
    public GlassDrawable overlayFrost() { overlayFrost = true; invalidateSelf(); return this; }

    /** Optional accent glow tint blended into the border (e.g. active/listening). */
    public GlassDrawable glow(int color) { glow = color; invalidateSelf(); return this; }

    @Override protected void onBoundsChange(android.graphics.Rect b) {
        r.set(b);
        r.inset(stroke / 2f, stroke / 2f);
        inner.set(r);
        inner.inset(Ui.dpf(1.2f), Ui.dpf(1.2f));
        Ui.Pal p = Ui.P;
        // Keep a little translucency for backdrop blur, but screen-space accent
        // blobs may never read as an overlay ON TOP of a neutral card.
        // Real background blur must remain visible through the card fill.
        // Previously the 86-92% opaque underlay hid the frosted wallpaper almost completely.
        underlay.setColor(overlayFrost ? (p.dark ? 0x5224232C : 0x6CF6F5FA) :
            (p.dark ? (strong ? 0xAA24232C : 0x9224232B) : 0xA0F6F5FA));
        float topOpacity = overlayFrost ? (p.dark ? 0.11f : 0.20f) :
            (p.dark ? (strong ? 0.22f : 0.16f) : 0.25f);
        float bottomOpacity = overlayFrost ? (p.dark ? 0.07f : 0.13f) :
            (p.dark ? 0.09f : 0.18f);
        int top = Ui.alpha(p.glassTop, topOpacity);
        int bot = Ui.alpha(p.glassBottom, bottomOpacity);
        fill.setShader(new LinearGradient(0, b.top, 0, b.bottom, top, bot, Shader.TileMode.CLAMP));
        int hiC = glow != 0 ? glow : p.strokeHi;
        border.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom, hiC, glow != 0 ? Ui.alpha(glow, 0.25f) : p.strokeLo, Shader.TileMode.CLAMP));
        hi.setShader(new LinearGradient(b.left, 0, b.right, 0, new int[]{0x00FFFFFF, p.dark ? 0x2EFFFFFF : 0x99FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas c) {
        if (r.isEmpty()) return;
        c.drawRoundRect(r, radius, radius, underlay);
        c.drawRoundRect(r, radius, radius, fill);
        c.drawRoundRect(r, radius, radius, border);
        // inner top highlight (specular edge of the glass)
        if (radius > Ui.dpf(6)) c.drawLine(inner.left + radius * 0.6f, inner.top, inner.right - radius * 0.6f, inner.top, hi);
    }

    @Override public void setAlpha(int a) { underlay.setAlpha(a); fill.setAlpha(a); border.setAlpha(a); hi.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { underlay.setColorFilter(cf); fill.setColorFilter(cf); border.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
