package app.jarvis.dev.checks;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.HardwareRenderer;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import java.io.File;
import java.io.FileOutputStream;

/** S0 spike: can RenderEffect blur be rendered offscreen from app_process? Writes spike_*.png to $TMPDIR. */
public final class GlassSpike {
    public static void main(String[] a) {
        try {
            run();
        } catch (Throwable t) {
            System.out.println("SPIKE FAILED: " + t);
            t.printStackTrace(System.out);
        }
        System.out.flush();
        System.exit(0);
    }

    static void run() throws Exception {
        int w = 600, h = 900;
        System.out.println("[spike] sdk=" + android.os.Build.VERSION.SDK_INT);
        RenderNode content = new RenderNode("content");
        content.setPosition(0, 0, w, h);
        Canvas c = content.beginRecording(w, h);
        Paint p = new Paint();
        for (int i = 0; i < 30; i++) {
            p.setColor(i % 2 == 0 ? Color.rgb(230, 40, 60) : Color.rgb(40, 90, 240));
            c.drawRect(0, i * 30, w, i * 30 + 15, p);
        }
        p.setColor(Color.WHITE);
        c.drawCircle(300, 450, 120, p);
        content.endRecording();

        RenderNode blur = new RenderNode("blur");
        blur.setPosition(0, 0, w, h);
        blur.setRenderEffect(RenderEffect.createBlurEffect(40f, 40f, Shader.TileMode.CLAMP));
        Canvas b = blur.beginRecording(w, h);
        b.drawRenderNode(content);
        blur.endRecording();

        System.out.println("[spike] nodes recorded");
        Bitmap plain = render(content, w, h, "plain");
        System.out.println("[spike] plain ok " + plain.getWidth() + "x" + plain.getHeight() + " px(0,0)=" + Integer.toHexString(plain.getPixel(10, 10)));
        Bitmap blurred = render(blur, w, h, "blur");
        System.out.println("[spike] blur ok px(0,0)=" + Integer.toHexString(blurred.getPixel(10, 10)));
        int mid = blurred.getPixel(w / 2, 10);
        System.out.println("[spike] blur px(mid,10)=" + Integer.toHexString(mid) + " plain px(mid,10)=" + Integer.toHexString(plain.getPixel(w / 2, 10)));
        lens(content, blur, w, h);
        RenderNode direct = new RenderNode("direct");
        direct.setPosition(0, 0, w, h);
        direct.setRenderEffect(RenderEffect.createBlurEffect(40f, 40f, Shader.TileMode.CLAMP));
        Canvas d = direct.beginRecording(w, h);
        Paint q = new Paint();
        for (int i = 0; i < 30; i++) { q.setColor(i % 2 == 0 ? Color.rgb(230, 40, 60) : Color.rgb(40, 90, 240)); d.drawRect(0, i * 30, w, i * 30 + 15, q); }
        direct.endRecording();
        Bitmap dOut = render(direct, w, h, "direct");
        System.out.println("[spike] direct-blur px(10,10)=" + Integer.toHexString(dOut.getPixel(10, 10)) + " px(300,100)=" + Integer.toHexString(dOut.getPixel(300, 100)));
        RenderNode wrap = new RenderNode("wrap");
        wrap.setPosition(0, 0, w, h);
        Canvas wc = wrap.beginRecording(w, h);
        wc.drawRenderNode(content);
        wrap.endRecording();
        Bitmap wOut = render(wrap, w, h, "wrap");
        System.out.println("[spike] wrap(no effect, child) px(10,10)=" + Integer.toHexString(wOut.getPixel(10, 10)));
        RenderNode wrapBlur = new RenderNode("wrapblur");
        wrapBlur.setPosition(0, 0, w, h);
        Canvas wbc = wrapBlur.beginRecording(w, h);
        wbc.drawRenderNode(blur);
        wrapBlur.endRecording();
        Bitmap wbOut = render(wrapBlur, w, h, "wrapblur");
        System.out.println("[spike] wrap(child=blur) px(10,10)=" + Integer.toHexString(wbOut.getPixel(10, 10)) + " px(300,100)=" + Integer.toHexString(wbOut.getPixel(300, 100)));
        RenderNode fresh = new RenderNode("fresh");
        fresh.setPosition(0, 0, w, h);
        Canvas fc = fresh.beginRecording(w, h);
        Paint fp = new Paint();
        for (int i = 0; i < 30; i++) { fp.setColor(i % 2 == 0 ? Color.rgb(230, 40, 60) : Color.rgb(40, 90, 240)); fc.drawRect(0, i * 30, w, i * 30 + 15, fp); }
        fresh.endRecording();
        RenderNode freshWrap = new RenderNode("freshwrap");
        freshWrap.setPosition(0, 0, w, h);
        Canvas fwc = freshWrap.beginRecording(w, h);
        fwc.drawRenderNode(fresh);
        freshWrap.endRecording();
        Bitmap fwOut = render(freshWrap, w, h, "freshwrap");
        System.out.println("[spike] FRESH child no effect px(10,10)=" + Integer.toHexString(fwOut.getPixel(10, 10)) + " px(300,100)=" + Integer.toHexString(fwOut.getPixel(300, 100)));
        RenderNode freshBlurNode = new RenderNode("freshblur");
        freshBlurNode.setPosition(0, 0, w, h);
        freshBlurNode.setRenderEffect(RenderEffect.createBlurEffect(40f, 40f, Shader.TileMode.CLAMP));
        Canvas fbc = freshBlurNode.beginRecording(w, h);
        fbc.drawRenderNode(fresh);
        freshBlurNode.endRecording();
        Bitmap fbOut = render(freshBlurNode, w, h, "freshblur");
        System.out.println("[spike] FRESH child + blur px(10,10)=" + Integer.toHexString(fbOut.getPixel(10, 10)) + " px(300,100)=" + Integer.toHexString(fbOut.getPixel(300, 100)));
        RenderNode t6Blur = new RenderNode("t6blur");
        t6Blur.setPosition(0, 0, w, h);
        t6Blur.setRenderEffect(RenderEffect.createBlurEffect(40f, 40f, Shader.TileMode.CLAMP));
        Canvas t6c = t6Blur.beginRecording(w, h);
        t6c.drawRenderNode(fresh);
        t6Blur.endRecording();
        RenderNode t6Root = new RenderNode("t6root");
        t6Root.setPosition(0, 0, w, h);
        Canvas t6r = t6Root.beginRecording(w, h);
        t6r.drawRenderNode(fresh);
        t6r.drawRenderNode(t6Blur);
        t6Root.endRecording();
        Bitmap t6Out = render(t6Root, w, h, "t6");
        System.out.println("[spike] T6 root{fresh, blur(fresh)} px(10,10)=" + Integer.toHexString(t6Out.getPixel(10, 10)) + " px(300,100)=" + Integer.toHexString(t6Out.getPixel(300, 100)));
        RenderNode t1Root = new RenderNode("t1root");
        t1Root.setPosition(0, 0, w, h);
        Canvas t1r = t1Root.beginRecording(w, h);
        t1r.drawRenderNode(t6Blur);
        t1Root.endRecording();
        Bitmap t1Out = render(t1Root, w, h, "t1");
        System.out.println("[spike] T1 root{blur(fresh)} px(10,10)=" + Integer.toHexString(t1Out.getPixel(10, 10)) + " px(300,100)=" + Integer.toHexString(t1Out.getPixel(300, 100)));
        RenderNode t11 = new RenderNode("t11");
        t11.setPosition(0, 0, w, h);
        Canvas t11c = t11.beginRecording(w, h);
        Paint t11p = new Paint();
        for (int i = 0; i < 30; i++) { t11p.setColor(i % 2 == 0 ? Color.rgb(230, 40, 60) : Color.rgb(40, 90, 240)); t11c.drawRect(0, i * 30, w, i * 30 + 15, t11p); }
        t11.endRecording();
        t11.setRenderEffect(RenderEffect.createBlurEffect(40f, 40f, Shader.TileMode.CLAMP));
        RenderNode t11root = new RenderNode("t11root");
        t11root.setPosition(0, 0, w, h);
        Canvas t11rc = t11root.beginRecording(w, h);
        t11rc.drawRenderNode(t11);
        t11root.endRecording();
        Bitmap t11Out = render(t11root, w, h, "t11");
        System.out.println("[spike] T11 child-with-effect drawn in root px(10,10)=" + Integer.toHexString(t11Out.getPixel(10, 10)) + " px(300,100)=" + Integer.toHexString(t11Out.getPixel(300, 100)));
        RenderNode t8 = new RenderNode("t8blur");
        Canvas t8c = t8.beginRecording(w, h);
        t8c.drawRenderNode(fresh);
        t8.endRecording();
        t8.setPosition(0, 0, w, h);
        t8.setRenderEffect(RenderEffect.createBlurEffect(40f, 40f, Shader.TileMode.CLAMP));
        t8.setClipToBounds(false);
        fresh.setPosition(0, 0, w, h);
        Bitmap t8Out = render(t8, w, h, "t8");
        System.out.println("[spike] T8 effect set after record px(10,10)=" + Integer.toHexString(t8Out.getPixel(10, 10)) + " px(300,100)=" + Integer.toHexString(t8Out.getPixel(300, 100)));
        long t0 = System.nanoTime();
        int frames = 0;
        ImageReader rd = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2, HardwareBuffer.USAGE_GPU_COLOR_OUTPUT | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        HardwareRenderer hr = new HardwareRenderer();
        hr.setSurface(rd.getSurface());
        hr.setContentRoot(freshBlurNode);
        for (int i = 0; i < 20; i++) {
            hr.createRenderRequest().setWaitForPresent(true).syncAndDraw();
            Image im = rd.acquireNextImage();
            if (im != null) { im.close(); frames++; }
        }
        System.out.println("[spike] blur frames=" + frames + " avg ms incl readback-less present=" + ((System.nanoTime() - t0) / 1e6 / 20));
        hr.destroy();
        rd.close();
    }

    static final String SKSL =
        "uniform shader content;\n" +
        "uniform float2 center;\n" +
        "uniform float2 halfSize;\n" +
        "uniform float radius;\n" +
        "half4 main(float2 p) {\n" +
        "  float2 q = p - center;\n" +
        "  float2 d = abs(q) - halfSize + float2(radius);\n" +
        "  float sd = length(max(d, float2(0.0))) + min(max(d.x, d.y), 0.0) - radius;\n" +
        "  float edge = 1.0 - smoothstep(0.0, 36.0, -sd);\n" +
        "  float2 disp = normalize(q + float2(0.001)) * edge * 26.0;\n" +
        "  half4 base = content.eval(p);\n" +
        "  half4 lensed = content.eval(p - disp * step(sd, 0.0));\n" +
        "  float rim = smoothstep(-3.0, 0.0, sd) * (1.0 - smoothstep(0.0, 2.5, sd));\n" +
        "  return mix(base, lensed, step(sd, 0.0)) + half4(rim * 0.35);\n" +
        "}";

    static void lens(RenderNode content, RenderNode blur, int w, int h) throws Exception {
        android.graphics.RuntimeShader shader = new android.graphics.RuntimeShader(SKSL);
        shader.setFloatUniform("center", w / 2f, h / 2f);
        shader.setFloatUniform("halfSize", 220f, 120f);
        shader.setFloatUniform("radius", 60f);
        RenderEffect lensEffect = RenderEffect.createRuntimeShaderEffect(shader, "content");
        RenderEffect blurEffect = RenderEffect.createBlurEffect(24f, 24f, Shader.TileMode.CLAMP);
        RenderNode lensNode = new RenderNode("lens");
        lensNode.setPosition(0, 0, w, h);
        lensNode.setRenderEffect(RenderEffect.createChainEffect(lensEffect, blurEffect));
        Canvas l = lensNode.beginRecording(w, h);
        l.drawRenderNode(content);
        lensNode.endRecording();
        Bitmap out = render(lensNode, w, h, "lens");
        System.out.println("[spike] lens ok px(mid,mid)=" + Integer.toHexString(out.getPixel(w / 2, h / 2)));
    }

    static Bitmap render(RenderNode node, int w, int h, String name) throws Exception {
        ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        HardwareRenderer r = new HardwareRenderer();
        r.setSurface(reader.getSurface());
        r.setContentRoot(node);
        r.setLightSourceGeometry(0, 0, 0, 1);
        r.setLightSourceAlpha(0, 0);
        r.createRenderRequest().setWaitForPresent(true).syncAndDraw();
        Image img = reader.acquireNextImage();
        if (img == null) throw new IllegalStateException("no image from " + name);
        HardwareBuffer hb = img.getHardwareBuffer();
        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB));
        Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
        File out = new File(System.getenv("TMPDIR") == null ? "/data/local/tmp" : System.getenv("TMPDIR"), "spike_" + name + ".png");
        try (FileOutputStream os = new FileOutputStream(out)) { sw.compress(Bitmap.CompressFormat.PNG, 100, os); }
        System.out.println("[spike] wrote " + out.getPath());
        hb.close();
        img.close();
        hw.recycle();
        r.destroy();
        reader.close();
        return sw;
    }
}
