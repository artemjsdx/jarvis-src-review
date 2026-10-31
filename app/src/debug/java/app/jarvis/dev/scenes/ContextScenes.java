package app.jarvis.dev.scenes;

import android.view.View;
import android.widget.FrameLayout;
import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;
import app.jarvis.ui.settings.ContextPage;
import app.jarvis.ui.settings.SettingsPage;
import app.jarvis.ui.settings.TriggersPage;

/** «Контекст и промпт» and «Фразы-триггеры» pages, dark and light, rendered standalone with the real settings data. */
public final class ContextScenes {
    private ContextScenes() { }

    public static void render() throws Exception {
        PreviewHost.pump(150);
        // the context page is long (report + knobs + reset button): a tall frame shows all of it
        page(true, "context_dark", new ContextPage(PreviewKit.host()), 1400);
        page(false, "context_light", new ContextPage(PreviewKit.host()), 1400);
        page(true, "triggers_dark", new TriggersPage(PreviewKit.host()), 850);
        page(false, "triggers_light", new TriggersPage(PreviewKit.host()), 850);
    }

    private static void page(boolean dark, String name, SettingsPage p, int heightDp) throws Exception {
        Ui.init(PreviewHost.ctx, dark);
        Host h = PreviewKit.host();
        FrameLayout bg = new FrameLayout(PreviewHost.ctx);
        bg.setBackgroundColor(Ui.P.bg0);
        View v = p.view();
        bg.addView(v, new FrameLayout.LayoutParams(-1, -1));
        p.onShow();
        PreviewHost.pump(600);
        PreviewHost.render(bg, name, 392, heightDp);
    }
}
