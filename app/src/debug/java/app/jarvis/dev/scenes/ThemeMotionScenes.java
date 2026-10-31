package app.jarvis.dev.scenes;

import android.widget.FrameLayout;
import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.ui.Backdrop;
import app.jarvis.ui.Shell;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;

/**
 * Theme and motion evidence on the real runtime: the main screens in both themes, the theme rebuild path (a fresh shell
 * with the visible screen's state copied over, as MainActivity.rebuildTheme does), and toggle frames during the spring.
 */
public final class ThemeMotionScenes {
    private ThemeMotionScenes() { }

    public static void render() throws Exception {
        PreviewKit.shell("home");
        for (boolean dark : new boolean[]{true, false}) {
            String tag = dark ? "dark" : "light";
            Ui.setDark(dark);
            for (String target : new String[]{"home", "agent", "settings"}) {
                Shell s = shellFor(target);
                PreviewHost.render(s, "theme_" + target + "_" + tag);
            }
        }
        Ui.setDark(true);
        Shell before = shellFor("settings/appearance");
        Ui.setDark(false);
        Shell after = shellFor("settings/appearance");
        after.copyStateFrom(before);
        PreviewHost.render(after, "theme_flip_appearance_light");
        app.jarvis.ui.motion.Motion.setMode(app.jarvis.ui.motion.Motion.FULL);
        try { toggleFrames(); } finally {
            app.jarvis.ui.motion.Motion.setMode(app.jarvis.ui.motion.Motion.OFF);
            Ui.setDark(true);       // later scenes (alphabetical order) must not inherit the light theme
        }
    }

    private static Shell shellFor(String target) throws Exception {
        Shell s = new Shell(PreviewKit.host());
        s.setInsets(PreviewHost.px(30), PreviewHost.px(24), 0);
        s.navigate(target);
        PreviewHost.pump(700);
        return s;
    }

    private static void toggleFrames() throws Exception {
        FrameLayout root = new FrameLayout(PreviewHost.ctx);
        root.addView(new Backdrop(PreviewHost.ctx), new FrameLayout.LayoutParams(-1, -1));
        Widgets.Toggle t = Widgets.toggle(PreviewHost.ctx, false, null);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(46), Ui.dp(28), android.view.Gravity.CENTER);
        root.addView(t, lp);
        PreviewHost.render(root, "motion_toggle_f0", 120, 60);
        t.setChecked(true, false);
        long start = android.os.SystemClock.uptimeMillis();
        int frame = 1;
        for (long at : new long[]{40, 90, 150, 260}) {
            long wait = at - (android.os.SystemClock.uptimeMillis() - start);
            if (wait > 0) PreviewHost.pump(wait);
            PreviewHost.render(root, "motion_toggle_f" + frame++, 120, 60);
        }
    }
}
