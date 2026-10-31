package app.jarvis.dev.scenes;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.dev.PreviewHost;
import app.jarvis.ui.Backdrop;
import app.jarvis.ui.Ui;
import app.jarvis.ui.glass.GlassHost;
import app.jarvis.ui.glass.GlassLevel;
import app.jarvis.ui.glass.OffscreenRenderer;
import app.jarvis.ui.glass.SegmentedGlass;

/** Cost of the glass path on this phone: GPU frame with glass on and off, and the software snapshot of the content layer. */
public final class GlassBench {
    private GlassBench() { }

    public static void render() throws Exception {
        Ui.reduceMotion = true;
        int w = PreviewHost.px(392), h = PreviewHost.px(850);
        GlassHost host = new GlassHost(PreviewHost.ctx);
        LinearLayout text = Ui.column(PreviewHost.ctx);
        text.setPadding(Ui.dp(18), Ui.dp(40), Ui.dp(18), Ui.dp(40));
        for (int i = 0; i < 40; i++) {
            TextView t = Ui.text(PreviewHost.ctx, "Ответ ассистента " + (i + 1) + ": проверка размытия, текст должен быть виден сквозь стекло.", Ui.BODY);
            text.addView(t, Ui.lpm(-1, -2, 0, 0, 0, 14));
        }
        host.content().addView(new Backdrop(PreviewHost.ctx), new FrameLayout.LayoutParams(-1, -1));
        host.content().addView(text, new FrameLayout.LayoutParams(-1, -1));
        SegmentedGlass seg = new SegmentedGlass(PreviewHost.ctx);
        seg.setItems(new String[]{"Все", "Инфо", "Предупр.", "Ошибки"});
        seg.setSelected(2);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, Ui.dp(48), Gravity.TOP);
        lp.topMargin = Ui.dp(70);
        host.overlay().addView(seg, lp);
        host.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY));
        host.layout(0, 0, w, h);

        android.media.ImageReader reader = android.media.ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2,
            android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT | android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        android.graphics.HardwareRenderer renderer = new android.graphics.HardwareRenderer();
        renderer.setSurface(reader.getSurface());
        android.graphics.RenderNode root = new android.graphics.RenderNode("bench");
        root.setPosition(0, 0, w, h);
        renderer.setContentRoot(root);
        for (String level : new String[]{"full", "reduced", "off"}) {
            GlassLevel.configure(level);
            double[] record = new double[24], gpu = new double[24];
            for (int i = 0; i < record.length; i++) {
                Thread.sleep(45);
                long t0 = System.nanoTime();
                android.graphics.Canvas rc = root.beginRecording(w, h);
                host.draw(rc);
                root.endRecording();
                long t1 = System.nanoTime();
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
                long t2 = System.nanoTime();
                android.media.Image im = reader.acquireNextImage();
                if (im != null) im.close();
                record[i] = (t1 - t0) / 1e6;
                gpu[i] = (t2 - t1) / 1e6;
            }
            System.out.println("[bench] level=" + level + " UI record (incl. snapshot) median ms=" + median(record, 4) + " | GPU sync+draw median ms=" + median(gpu, 4));
        }
        renderer.destroy();
        reader.close();
        GlassLevel.configure("full");

        Bitmap quarter = Bitmap.createBitmap(w / 4, h / 4, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(quarter);
        double[] snap = new double[14];
        for (int i = 0; i < snap.length; i++) {
            long t0 = System.nanoTime();
            c.save();
            c.scale(0.25f, 0.25f);
            host.content().draw(c);
            c.restore();
            snap[i] = (System.nanoTime() - t0) / 1e6;
        }
        System.out.println("[bench] software snapshot of content layer at 1/4 scale, median ms=" + median(snap, 2) + " (" + (w / 4) + "x" + (h / 4) + " px)");
        Ui.reduceMotion = true;
    }

    private static double median(double[] v, int skip) {
        double[] s = java.util.Arrays.copyOfRange(v, skip, v.length);
        java.util.Arrays.sort(s);
        return Math.round(s[s.length / 2] * 100) / 100.0;
    }
}
