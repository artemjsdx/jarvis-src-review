package app.jarvis.ui;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import app.jarvis.rt.Jarvis;

/**
 * One top-level destination. The view is created once (lazily) and kept; onShow/onHide bracket visibility so a screen
 * can start/stop listeners and animations. Everything runs on the main thread.
 */
public abstract class Screen {
    protected final Host host;
    protected final Context ctx;
    protected final Jarvis j;
    private View root;

    protected Screen(Host host) {
        this.host = host;
        this.ctx = host.context();
        this.j = host.jarvis();
    }

    /** Build the view tree. Called once. */
    protected abstract View build();

    public final View view() {
        if (root == null) root = build();
        return root;
    }

    /** Header title; null = no header (the screen draws its own). */
    public abstract String title();

    /** @param arg the part after the first '/' of the navigation target ("voice" for "settings/voice"), or "". */
    public void onShow(String arg) { }

    public void onHide() { }

    /** @return true if the back press was consumed (e.g. closed a sub-page). */
    public boolean onBack() { return false; }

    public void onActivityResult(int requestCode, int resultCode, Intent data) { }

    public void onPermissionResult(String permission, boolean granted) { }

    public void onDestroy() { }

    /** Called when window insets/keyboard change the usable height. */
    public void onInsets(int top, int bottom) { }
}
