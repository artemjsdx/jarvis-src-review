package app.jarvis.ui.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import app.jarvis.ui.Ui;

/**
 * Refracting lens for the liquid drop, API 33+. The snapshot of the backdrop is blurred and then displaced by a convex-lens shader:
 * stronger near the edge, with a small chromatic split and a specular rim from the top-left. Everything outside the drop is
 * transparent. One RenderNode per lens, recorded directly (no child nodes; see BlurLayer).
 */
final class LensPass {
    private static final String SKSL =
        "uniform shader content;\n" +
        "uniform float2 center;\n" +
        "uniform float2 halfSize;\n" +
        "uniform float radius;\n" +
        "uniform float shift;\n" +
        "half4 main(float2 p) {\n" +
        "    float2 q = p - center;\n" +
        "    float2 d = abs(q) - halfSize + float2(radius);\n" +
        "    float sd = length(max(d, float2(0.0))) + min(max(d.x, d.y), 0.0) - radius;\n" +
        "    float cover = 1.0 - smoothstep(-0.5, 0.5, sd);\n" +
        "    float depth = clamp(-sd / max(radius, 1.0), 0.0, 1.0);\n" +
        "    float bulge = (1.0 - depth) * (1.0 - depth);\n" +
        "    float2 n = normalize(q + float2(0.0001, 0.0));\n" +
        "    float2 off = -n * bulge * shift;\n" +
        "    float2 ca = float2(1.2, 0.0) * bulge;\n" +
        "    float r = content.eval(p + off + ca).r;\n" +
        "    float g = content.eval(p + off).g;\n" +
        "    float b = content.eval(p + off - ca).b;\n" +
        "    float3 col = float3(r, g, b);\n" +
        "    float spec = pow(max(dot(n, normalize(float2(-1.0, -1.0))), 0.0), 4.0) * bulge;\n" +
        "    float edge = 1.0 - smoothstep(0.0, 1.6, -sd);\n" +
        "    float3 outc = min(col + float3(0.55) * spec + float3(0.16) * edge, float3(1.0));\n" +
        "    return half4(half3(outc * cover), half(cover));\n" +
        "}";

    private final RenderNode node = new RenderNode("glass.lens");
    private final RuntimeShader shader = new RuntimeShader(SKSL);
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private float effectScale = -1f;
    private RenderEffect effect;

    /**
     * Draws the lens at host offset (ox, oy). shapeLocal is the drop in view coordinates; the view is w x h pixels.
     * snap is the backdrop snapshot, s its scale against host pixels.
     */
    void draw(Canvas c, Bitmap snap, float s, float ox, float oy, int w, int h, RectF shapeLocal, float radiusLocal) {
        int nw = Math.max(1, Math.round(w * s)), nh = Math.max(1, Math.round(h * s));
        if (effect == null || effectScale != s) {
            float blurPx = Ui.dpf(10f) * s;
            effect = RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"),
                RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP));
            effectScale = s;
        }
        node.setPosition(0, 0, nw, nh);
        node.setRenderEffect(effect);
        Canvas rc = node.beginRecording(nw, nh);
        rc.drawBitmap(snap, -ox * s, -oy * s, paint);
        node.endRecording();
        shader.setFloatUniform("center", shapeLocal.centerX() * s, shapeLocal.centerY() * s);
        shader.setFloatUniform("halfSize", shapeLocal.width() / 2f * s, shapeLocal.height() / 2f * s);
        shader.setFloatUniform("radius", radiusLocal * s);
        shader.setFloatUniform("shift", Ui.dpf(9f) * s);
        c.save();
        c.translate(ox, oy);
        c.scale(1f / s, 1f / s);
        c.drawRenderNode(node);
        c.restore();
    }
}
