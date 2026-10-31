package app.jarvis.ui.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.PixelFormat;
import android.graphics.RenderNode;
import android.graphics.HardwareRenderer;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.view.View;

/**
 * Draws a laid-out view tree on the GPU without a window. Blur and lens effects only exist on the hardware path, so previews
 * and the headless UI host use this to show the real glass. Requires API 29+ (the blur itself needs 31).
 */
public final class OffscreenRenderer {
    private OffscreenRenderer() { }

    public static Bitmap render(View v, int w, int h) {
        try (Session s = new Session(w, h)) {
            s.draw(v);
            return s.readback();
        }
    }

    /**
     * A reusable GPU target of one size: frames can be drawn many times (animations) and read back only when a picture is
     * needed. The caller lays the view out first; {@link #draw} only records and renders it.
     */
    public static final class Session implements AutoCloseable {
        private final int w, h;
        private final RenderNode root;
        private final ImageReader reader;
        private final HardwareRenderer renderer;
        private Image last;

        public Session(int w, int h) {
            this.w = w;
            this.h = h;
            root = new RenderNode("glass.offscreen");
            root.setPosition(0, 0, w, h);
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2,
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
            renderer = new HardwareRenderer();
            renderer.setSurface(reader.getSurface());
            renderer.setContentRoot(root);
            renderer.setLightSourceGeometry(0, 0, 0, 1);
            renderer.setLightSourceAlpha(0, 0);
        }

        /** Records {@code v} into the target and renders one frame; the previous frame is released. */
        public void draw(View v) {
            Canvas rc = root.beginRecording(w, h);
            try { v.draw(rc); } finally { root.endRecording(); }
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
            Image next = reader.acquireNextImage();
            if (next == null) throw new IllegalStateException("offscreen render produced no image");
            if (last != null) last.close();
            last = next;
        }

        /** A software copy of the last frame drawn. */
        public Bitmap readback() {
            if (last == null) throw new IllegalStateException("nothing drawn yet");
            HardwareBuffer hb = last.getHardwareBuffer();
            Bitmap hw = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB));
            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
            hw.recycle();
            hb.close();
            return sw;
        }

        @Override public void close() {
            if (last != null) last.close();
            last = null;
            renderer.destroy();
            reader.close();
        }
    }
}
