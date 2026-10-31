package app.jarvis.dev.scenes;

import android.view.ViewGroup;
import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.ui.Shell;
import app.jarvis.ui.motion.Motion;

/**
 * Regression check (fails the preview run): after any series of navigations exactly one screen may stay attached.
 * With motion off the exit animation's end action runs synchronously, and an earlier version of Shell.navigate assigned `current`
 * only afterwards, so every old screen stayed attached and the screens piled up (found by the CI emulator run).
 */
public final class ShellStackScenes {
    private ShellStackScenes() { }

    public static void render() throws Exception {
        PreviewKit.jarvis();
        int saved = Motion.mode();
        try {
            for (int mode : new int[]{Motion.OFF, Motion.REDUCED}) {
                Motion.setMode(mode);
                Shell s = PreviewKit.shell("home");
                PreviewHost.pump(300);
                java.lang.reflect.Field f = Shell.class.getDeclaredField("screenHost");
                f.setAccessible(true);
                ViewGroup host = (ViewGroup) f.get(s);
                StringBuilder log = new StringBuilder("mode " + mode + ": ");
                for (String id : new String[]{"agent", "files", "terminal", "studio", "log", "settings", "home", "agent", "home", "settings/mcp"}) {
                    s.navigate(id);
                    PreviewHost.pump(mode == Motion.OFF ? 60 : 400);
                    log.append(id).append('=').append(host.getChildCount()).append(' ');
                    if (host.getChildCount() != 1) throw new IllegalStateException("screens piled up after navigating to " + id + ": " + log);
                }
                System.out.println("[scene] shell stack ok: " + log);
            }
        } finally {
            Motion.setMode(saved);
        }
    }
}
