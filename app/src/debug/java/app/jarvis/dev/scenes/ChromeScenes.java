package app.jarvis.dev.scenes;

import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.ui.Shell;

public final class ChromeScenes {
    public static void render() throws Exception {
        Shell s = PreviewKit.shell("home");
        PreviewHost.render(s, "chrome_home");
        s.navigate("settings");
        PreviewHost.pump(50);
        PreviewHost.render(s, "chrome_settings");
    }
}
