package app.jarvis.dev.scenes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import app.jarvis.R;
import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.ui.Shell;
import app.jarvis.ui.Ui;

/**
 * Brand evidence on the real runtime: the adaptive launcher layers clipped to circle, squircle and rounded-square masks
 * at 192 px and 48 px (the monochrome layer on a dark tile too), and Home with the orb skin in both themes.
 */
public final class BrandScenes {
    private BrandScenes() { }

    private static final String[] SHAPES = {"circle", "squircle", "rounded"};

    public static void render() throws Exception {
        iconMasks();
        for (boolean dark : new boolean[]{true, false}) {
            Ui.setDark(dark);
            Shell s = new Shell(PreviewKit.host());
            s.setInsets(PreviewHost.px(30), PreviewHost.px(24), 0);
            s.navigate("home");
            PreviewHost.pump(700);
            PreviewHost.render(s, "brand_home_" + (dark ? "dark" : "light"));
        }
        Ui.setDark(true);
    }

    private static void iconMasks() throws Exception {
        Context c = PreviewHost.ctx;
        final Drawable bg = c.getResources().getDrawable(R.drawable.ic_launcher_background);
        final Drawable fg = c.getResources().getDrawable(R.drawable.ic_launcher_foreground);
        final Drawable mono = c.getResources().getDrawable(R.drawable.ic_launcher_monochrome);
        View sheet = new View(c) {
            @Override protected void onDraw(Canvas cv) {
                cv.drawColor(0xFF1E1E22);
                for (int i = 0; i < SHAPES.length; i++) {
                    int x = 24 + i * 232;
                    adaptive(cv, bg, fg, SHAPES[i], x, 24, 192);
                    adaptive(cv, bg, fg, SHAPES[i], x + 72, 250, 48);
                    panel(cv, x + 60, 408, 72, 0xFFF3EEF7);
                    adaptive(cv, bg, fg, SHAPES[i], x + 72, 420, 48);
                    monochrome(cv, mono, SHAPES[i], x, 520, 192);
                }
            }
        };
        PreviewHost.render(sheet, "brand_icon_masks", 400, 320);
    }

    private static void panel(Canvas cv, int x, int y, int s, int color) {
        cv.save();
        cv.clipRect(x, y, x + s, y + s);
        cv.drawColor(color);
        cv.restore();
    }

    /** Both layers are 108 units wide; the mask clips them the way a launcher clips the adaptive icon. */
    private static void adaptive(Canvas cv, Drawable bg, Drawable fg, String shape, int x, int y, int s) {
        cv.save();
        cv.clipPath(mask(shape, x, y, s));
        bg.setBounds(x, y, x + s, y + s);
        bg.draw(cv);
        fg.setBounds(x, y, x + s, y + s);
        fg.draw(cv);
        cv.restore();
    }

    private static void monochrome(Canvas cv, Drawable mono, String shape, int x, int y, int s) {
        cv.save();
        cv.clipPath(mask(shape, x, y, s));
        cv.drawColor(0xFF2A2A34);
        mono.setBounds(x, y, x + s, y + s);
        mono.draw(cv);
        cv.restore();
    }

    private static Path mask(String shape, int x, int y, int s) {
        Path p = new Path();
        RectF r = new RectF(x, y, x + s, y + s);
        if (shape.equals("circle")) {
            p.addOval(r, Path.Direction.CW);
        } else {
            float rx = s * (shape.equals("squircle") ? 0.36f : 0.18f);
            p.addRoundRect(r, rx, rx, Path.Direction.CW);
        }
        return p;
    }
}
