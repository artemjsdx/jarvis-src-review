package app.jarvis.dev.checks;

import android.widget.EditText;

/** Diagnoses which EditText variants crash in the headless preview (debug-only). */
public final class SettingsFieldProbe {
    public static void main(String[] a) throws Exception {
        try {
            app.jarvis.dev.PreviewHost.init(a[0], new java.io.File(a[1]));
        } catch (Throwable t) {
            System.out.println("INIT FAILED: " + t);
            t.printStackTrace(System.out);
            System.exit(2);
        }
        android.content.Context c = app.jarvis.dev.PreviewHost.ctx;
        probe("plain new EditText", new Runnable() { @Override public void run() { new EditText(c); } });
        probe("field plain", new Runnable() { @Override public void run() { app.jarvis.ui.Widgets.field(c, "h", "", false, false); } });
        probe("field secret", new Runnable() { @Override public void run() { app.jarvis.ui.Widgets.field(c, "h", "", false, true); } });
        probe("field number", new Runnable() { @Override public void run() {
            EditText e = app.jarvis.ui.Widgets.field(c, "h", "1", false, false);
            e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        } });
        probe("field multiline", new Runnable() { @Override public void run() { app.jarvis.ui.Widgets.field(c, "h", "", true, false); } });
        System.out.println("PROBE DONE");
    }

    private static void probe(String name, Runnable r) {
        try {
            r.run();
            System.out.println("OK   " + name);
        } catch (Throwable t) {
            System.out.println("FAIL " + name + ": " + t);
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(26, st.length); i++) System.out.println("     at " + st[i]);
        }
        System.out.flush();
    }
}
