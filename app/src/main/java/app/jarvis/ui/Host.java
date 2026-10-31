package app.jarvis.ui;

import android.app.Activity;
import android.content.Intent;
import app.jarvis.rt.Jarvis;
import java.io.File;

/** What a screen may ask of the hosting activity. */
public interface Host {
    /** Themed context to build views with (the activity; in headless previews a themed fake). */
    android.content.Context context();

    /** The real activity, or null in headless previews. */
    Activity activity();

    Jarvis jarvis();

    /** In-app dialogs, sheets and toasts. */
    OverlayHost overlays();

    /** "home","agent","files","terminal","studio","log","settings","settings/voice",... */
    void navigate(String target);

    /** Opens the system document picker; the result arrives in Screen.onActivityResult(requestCode,...). */
    void pickDocument(Screen requester, int requestCode, String[] mimeTypes, boolean multiple);

    void requestPermission(String androidPermission);

    boolean hasPermission(String androidPermission);

    void hideKeyboard();

    void copyToClipboard(String label, String text);

    /** Shares/opens a sandbox file through the system chooser (read-only content grant). */
    void shareFile(File f, String mime);

    void openUrl(String url);

    /** Opens the Android system screen for ignoring battery optimizations for this app. */
    void openBatterySettings();

    /** Bottom inset (navigation bar) and top inset (status bar) in px, already applied to the content area by the host. */
    int bottomInset();

    int topInset();

    void startActivityForResult(Screen requester, Intent intent, int requestCode);

    /** Re-reads the theme setting and rebuilds the window (default: nothing, for headless hosts). */
    default void applyTheme() { }

    /** False for a shell nobody sees (the MCP headless host): its navigation must not overwrite the owner's last screen. */
    default boolean persistsLastScreen() { return true; }
}
