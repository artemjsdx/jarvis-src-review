package app.jarvis.dev;

import java.io.File;

/**
 * Entry point: Preview <apk> <outDir> <SceneClass>...  Each scene class has a public static void render() that builds
 * views and calls PreviewHost.render(...). Scene classes live in app/src/debug/java/app/jarvis/dev/scenes/.
 */
public final class Preview {
    public static void main(String[] a) throws Exception {
        try {
            PreviewHost.init(a[0], new File(a[1]));
        } catch (Throwable t) {
            System.out.println("INIT FAILED: " + t);
            t.printStackTrace(System.out);
            System.out.flush();
            System.exit(2);
        }
        int failed = 0;
        for (int i = 2; i < a.length; i++) {
            String cn = a[i].indexOf('.') < 0 ? "app.jarvis.dev.scenes." + a[i] : a[i];
            try {
                Class.forName(cn).getMethod("render").invoke(null);
            } catch (Throwable t) {
                failed++;
                Throwable c = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
                System.out.println("SCENE FAILED " + cn + ": " + c);
                int _n = 0; for (StackTraceElement e : c.getStackTrace()) { if (_n++ > 45) break; System.out.println("    at " + e); }
            }
        }
        System.out.println(failed == 0 ? "PREVIEW OK" : "PREVIEW FAILURES: " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
