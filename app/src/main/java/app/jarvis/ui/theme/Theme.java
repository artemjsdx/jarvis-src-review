package app.jarvis.ui.theme;

import android.content.res.Configuration;
import android.view.View;
import android.view.Window;

/** The theme choice (dark | light | system) and the system bars that follow the resolved theme. */
public final class Theme {
    public static final String DARK = "dark", LIGHT = "light", SYSTEM = "system";

    private Theme() { }

    public static boolean resolveDark(String mode, Configuration cfg) {
        if (LIGHT.equals(mode)) return false;
        if (SYSTEM.equals(mode)) return cfg != null && (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return true;
    }

    /** Dark theme wants light system-bar icons; the flags are set on the decor view and survive activity rebuilds. */
    public static void applyWindow(Window w, boolean dark) {
        if (w == null) return;
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        int lightIcons = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(dark ? flags & ~lightIcons : flags | lightIcons);
    }
}
