package app.jarvis.dev.scenes;

import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.ui.Shell;

/** Preview of the "Мост для агента" settings page (the bridge itself is not running in previews). */
public final class McpScenes {
    public static void render() throws Exception {
        Shell shell = PreviewKit.shell("settings");
        PreviewHost.pump(150);
        PreviewHost.render(shell, "mcp_list");
        shell.navigate("settings/mcp");
        PreviewHost.pump(300);
        PreviewHost.render(shell, "mcp_page");
    }
}
