package app.jarvis.dev;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import app.jarvis.R;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Headless UI renderer. Runs inside app_process (real Android framework, no device UI): builds a themed Context
 * backed by this APK's resources and draws views into PNGs, so layouts, drawables and custom views can be checked
 * (and looked at) without installing the app.
 */
public final class PreviewHost {
    private PreviewHost() { }

    public static Context ctx;
    public static float density = 2.75f;
    public static int widthDp = 392, heightDp = 850;
    public static File outDir;
    private static Resources res;
    public static java.io.File filesDir;

    public static void init(String apkPath, File out) throws Exception {
        outDir = out;
        filesDir = new File(System.getenv("TMPDIR") == null ? "/data/local/tmp" : System.getenv("TMPDIR"), "jv-preview-files");
        filesDir.mkdirs();
        step("init");
        out.mkdirs();
        Looper.prepareMainLooper();
        step("looper");
        installFonts();
        stubWindowManager();
        stubSettings();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object t = at.getMethod("systemMain").invoke(null);
        final Context sys = (Context) at.getMethod("getSystemContext").invoke(t);
        step("systemContext ok");
        AssetManager am = AssetManager.class.getDeclaredConstructor().newInstance();
        Method add = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
        add.setAccessible(true);
        add.invoke(am, "/system/framework/framework-res.apk");
        if ((Integer) add.invoke(am, apkPath) == 0) throw new IllegalStateException("cannot load resources from " + apkPath);
        step("assets ok");
        DisplayMetrics dm = new DisplayMetrics();
        dm.setToDefaults();
        dm.density = density;
        dm.scaledDensity = density;
        dm.densityDpi = Math.round(density * 160);
        dm.widthPixels = Math.round(widthDp * density);
        dm.heightPixels = Math.round(heightDp * density);
        Configuration cfg = new Configuration(sys.getResources().getConfiguration());
        cfg.densityDpi = dm.densityDpi;
        cfg.screenWidthDp = widthDp;
        cfg.screenHeightDp = heightDp;
        res = new Resources(am, dm, cfg);
        // the Resources' own metrics can come back zero-sized in app_process; dialogs size themselves from them
        DisplayMetrics live = res.getDisplayMetrics();
        live.density = dm.density;
        live.scaledDensity = dm.scaledDensity;
        live.densityDpi = dm.densityDpi;
        live.widthPixels = dm.widthPixels;
        live.heightPixels = dm.heightPixels;
        step("resources ok");
        final Resources.Theme theme = res.newTheme();
        theme.applyStyle(R.style.Theme_Jarvis, true);
        ctx = new ContextWrapper(sys) {
            @Override public Resources getResources() { return res; }
            @Override public Resources.Theme getTheme() { return theme; }
            @Override public AssetManager getAssets() { return res.getAssets(); }
            // binder calls verify the package against our real uid (Termux), so pose as it
            @Override public String getPackageName() { return "com.termux"; }
            public String getOpPackageName() { return "com.termux"; }
            @Override public Context getApplicationContext() { return this; }
            @Override public java.io.File getFilesDir() { return filesDir; }
            @Override public java.io.File getCacheDir() { return filesDir; }
        };
        step("theme ok");
        app.jarvis.ui.Ui.init(ctx, true);
        step("ui init ok");
        app.jarvis.ui.Ui.reduceMotion = true;
        app.jarvis.ui.motion.Motion.setMode(app.jarvis.ui.motion.Motion.OFF);   // headless: view animators never finish, old screens would stay stacked
    }

    /**
     * Zygote normally preloads the system fonts. In a bare app_process nothing is loaded and Typeface.create() throws
     * (the default handler then SIGKILLs the process silently), and on this vivo the fallback config file is not
     * readable for Termux. Build the system font map by hand from readable font files.
     */
    private static void installFonts() throws Exception {
        Class<?> sf = Class.forName("android.graphics.fonts.SystemFonts");
        Object cfg = sf.getMethod("getSystemPreinstalledFontConfigFromLegacyXml").invoke(null);
        Class<?> fcClass = Class.forName("android.text.FontConfig");
        Object fallback = sf.getMethod("buildSystemFallback", fcClass).invoke(null, cfg);
        Object typefaces = sf.getMethod("buildSystemTypefaces", fcClass, java.util.Map.class).invoke(null, cfg, fallback);
        Method set = android.graphics.Typeface.class.getDeclaredMethod("setSystemFontMap", java.util.Map.class);
        set.setAccessible(true);
        set.invoke(null, typefaces);
        step("fonts ok (legacy fonts.xml, " + ((java.util.Map<?, ?>) typefaces).size() + " families)");
    }

    /** View's ViewConfiguration asks IWindowManager.hasNavigationBar(); the "window" service is unreachable here. */
    private static void stubWindowManager() throws Exception {
        Class<?> iwm = Class.forName("android.view.IWindowManager");
        Object proxy = java.lang.reflect.Proxy.newProxyInstance(iwm.getClassLoader(), new Class<?>[]{iwm}, new java.lang.reflect.InvocationHandler() {
            @Override public Object invoke(Object p, Method m, Object[] args) {
                Class<?> r = m.getReturnType();
                if (r == boolean.class) return Boolean.FALSE;
                if (r == int.class) return 0;
                if (r == long.class) return 0L;
                if (r == float.class) return 0f;
                return null;
            }
        });
        Field f = Class.forName("android.view.WindowManagerGlobal").getDeclaredField("sWindowManagerService");
        f.setAccessible(true);
        f.set(null, proxy);
        step("window manager stubbed");
    }

    /**
     * vivo's framework reads Settings.Global from View.<init>; without a registered app the content-provider lookup
     * throws. Plant a do-nothing IContentProvider in the Settings holders so every lookup yields "not set".
     */
    private static void stubSettings() throws Exception {
        Class<?> icp = Class.forName("android.content.IContentProvider");
        Object proxy = java.lang.reflect.Proxy.newProxyInstance(icp.getClassLoader(), new Class<?>[]{icp}, new java.lang.reflect.InvocationHandler() {
            @Override public Object invoke(Object p, Method m, Object[] args) {
                Class<?> r = m.getReturnType();
                if (r == boolean.class) return Boolean.FALSE;
                if (r == int.class) return 0;
                if (r == long.class) return 0L;
                return null;
            }
        });
        for (String cn : new String[]{"android.provider.Settings$Global", "android.provider.Settings$System", "android.provider.Settings$Secure", "android.provider.Settings$Config"}) {
            Field h = Class.forName(cn).getDeclaredField("sProviderHolder");
            h.setAccessible(true);
            Object holder = h.get(null);
            Field cp = holder.getClass().getDeclaredField("mContentProvider");
            cp.setAccessible(true);
            cp.set(holder, proxy);
        }
        step("settings stubbed");
    }

    public static void step(String s) { System.out.println("[preview] " + s); System.out.flush(); }

    public static int px(float dp) { return Math.round(dp * density); }

    /** Lays out and draws the view at the given size (in dp, 0 = default screen size) into outDir/name.png. */
    public static File render(View v, String name, int wDp, int hDp) throws Exception {
        int w = px(wDp > 0 ? wDp : widthDp), h = px(hDp > 0 ? hDp : heightDp);
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
        Bitmap b;
        try {
            b = app.jarvis.ui.glass.OffscreenRenderer.render(v, w, h);
        } catch (Throwable gpuUnavailable) {
            step("GPU render unavailable, software path: " + gpuUnavailable);
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            v.draw(new Canvas(b));
        }
        File f = new File(outDir, name + ".png");
        try (FileOutputStream os = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.PNG, 100, os); }
        b.recycle();
        System.out.println("rendered " + f.getName() + " " + w + "x" + h);
        return f;
    }

    public static File render(View v, String name) throws Exception { return render(v, name, 0, 0); }

    /**
     * Lets posted work and animations run for ms, then returns. Uses a nested message loop on the main thread
     * (Looper.quit() would end the main looper for good).
     */
    public static void pump(long ms) {
        final long end = android.os.SystemClock.uptimeMillis() + ms;
        final Handler h = new Handler(Looper.getMainLooper());
        final boolean[] done = {false};
        h.postAtTime(new Runnable() { @Override public void run() { done[0] = true; } }, end);
        try {
            Method next = android.os.MessageQueue.class.getDeclaredMethod("next");
            next.setAccessible(true);
            Method dispatch = Handler.class.getDeclaredMethod("dispatchMessage", android.os.Message.class);
            dispatch.setAccessible(true);
            Method recycle = android.os.Message.class.getDeclaredMethod("recycleUnchecked");
            recycle.setAccessible(true);
            android.os.MessageQueue q = Looper.getMainLooper().getQueue();
            while (!done[0]) {
                android.os.Message m = (android.os.Message) next.invoke(q);
                if (m == null) break;
                Field tf = android.os.Message.class.getDeclaredField("target");
                tf.setAccessible(true);
                Handler target = (Handler) tf.get(m);
                if (target != null) target.dispatchMessage(m);
                recycle.invoke(m);
            }
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
