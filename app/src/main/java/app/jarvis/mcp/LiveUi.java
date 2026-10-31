package app.jarvis.mcp;

import android.app.Activity;
import java.lang.ref.WeakReference;

/** Remembers the app's window for the live UI host. MainActivity calls these from its lifecycle (three one-line hooks). */
public final class LiveUi {
    private LiveUi() { }

    private static volatile WeakReference<Activity> ref = new WeakReference<>(null);
    private static volatile boolean resumed;

    public static void resumed(Activity a, boolean on) { ref = new WeakReference<>(a); resumed = on; }

    public static void gone(Activity a) { if (ref.get() == a) { ref = new WeakReference<>(null); resumed = false; } }

    static Activity activity() { return ref.get(); }

    static boolean visible() { return resumed && ref.get() != null; }
}
