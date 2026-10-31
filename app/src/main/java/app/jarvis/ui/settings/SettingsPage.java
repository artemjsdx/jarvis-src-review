package app.jarvis.ui.settings;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.Host;
import app.jarvis.ui.Screen;

/**
 * One sub-page inside the settings screen. Built once, kept by the screen; onShow/onHide bracket visibility.
 * Pages are created by the registry in SettingsScreen (ids "llm", "music", …) so other agents can add more.
 */
public abstract class SettingsPage {
    protected final Host host;
    protected final Context ctx;
    protected final Jarvis j;
    private Screen screen;
    private View root;

    protected SettingsPage(Host host) {
        this.host = host;
        this.ctx = host.context();
        this.j = host.jarvis();
    }

    /** Set by SettingsScreen so pages can request system pickers and receive their results. */
    public void attach(Screen screen) { this.screen = screen; }

    /** The owning settings screen (for host.pickDocument(page-screen, requestCode, …) results relay). */
    public Screen screen() { return screen; }

    /** Title shown in the page header. */
    public abstract String title();

    /** Build the content view (the screen adds the header with the back button). */
    protected abstract View build();

    public final View view() {
        if (root == null) root = build();
        return root;
    }

    public void onShow() { }

    public void onHide() { }

    /** @return true if consumed (e.g. an inner sheet was open — closing it is handled by overlays; rarely needed). */
    public boolean onBack() { return false; }

    public void onActivityResult(int requestCode, int resultCode, Intent data) { }

    public void onPermissionResult(String permission, boolean granted) { }
}
