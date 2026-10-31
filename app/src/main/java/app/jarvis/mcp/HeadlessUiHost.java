package app.jarvis.mcp;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import app.jarvis.core.util.J;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.Host;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Screen;
import app.jarvis.ui.Shell;
import app.jarvis.ui.Ui;
import app.jarvis.ui.glass.OffscreenRenderer;
import app.jarvis.ui.theme.Theme;
import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The app's real Shell inside the running process, without any window: frames are rendered on the GPU into an offscreen target
 * (see OffscreenRenderer), touches and keys are dispatched to the Shell's own view tree. The Shell is not attached to the Jarvis
 * listeners, so the owner's window and the agent's navigation never reach it; it moves only when ui_navigate runs.
 *
 * Limits of a view tree that is not attached to a window: View.post/postDelayed runnables do not run (the framework queues them
 * for a window), so clicks and long presses are performed directly after the touch events.
 */
public final class HeadlessUiHost implements UiHost {
    static final int DEFAULT_W = 1080, DEFAULT_H = 2340;
    static final int TOP_INSET_DP = 24, BOTTOM_INSET_DP = 48;
    static final long ACTIVE_MS = 1500;

    private final Jarvis j;
    private final Context themed;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Host host = new HostImpl();

    private Shell shell;                       // main thread only
    private OffscreenRenderer.Session session; // main thread only
    private volatile int w, h;
    private volatile long activeUntil;
    private volatile String screenId = "";
    private volatile long frames, renderNsTotal, lastRenderNs;
    private boolean running;                   // main thread only
    private long vsync;                        // main thread only

    /** @param themed application context with the app theme applied. */
    public HeadlessUiHost(Jarvis j, Context themed) {
        this.j = j;
        this.themed = themed;
    }

    @Override public String id() { return "headless"; }

    @Override public JSONObject describe() {
        JSONObject o = new JSONObject();
        J.put(o, "id", "headless");
        J.put(o, "available", true);
        J.put(o, "open", shell != null);
        J.put(o, "visible", false);
        J.put(o, "kind", "offscreen-gpu");
        J.put(o, "sizePx", new JSONArray().put(w).put(h));
        J.put(o, "screen", screenId);
        J.put(o, "framesRendered", frames);
        J.put(o, "lastRenderMs", Math.round(lastRenderNs / 1e5) / 10.0);
        J.put(o, "avgRenderMs", frames > 0 ? Math.round(renderNsTotal / (double) frames / 1e5) / 10.0 : 0);
        J.put(o, "reason", "Невидимое окно: экран Jarvis без окна, кадры рисуются на GPU. Касания и клавиши идут в дерево видов.");
        return o;
    }

    // ---- threading ------------------------------------------------------------------------------------------------
    private <T> T onUi(Callable<T> c) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return c.call();
        FutureTask<T> t = new FutureTask<>(c);
        ui.post(t);
        try { return t.get(15, TimeUnit.SECONDS); }
        catch (ExecutionException e) { Throwable cause = e.getCause(); if (cause instanceof Exception) throw (Exception) cause; throw e; }
    }

    // ---- lifecycle ------------------------------------------------------------------------------------------------
    /** Opens the invisible window, or resizes it (the screen is kept). */
    @Override public JSONObject open(final int width, final int height) throws Exception {
        return onUi(new Callable<JSONObject>() { @Override public JSONObject call() {
            openNow(width > 0 ? width : DEFAULT_W, height > 0 ? height : DEFAULT_H);
            keepAlive(0);
            return describe();
        } });
    }

    private void ensureOpen() { if (shell == null) openNow(DEFAULT_W, DEFAULT_H); }

    private void openNow(int nw, int nh) {
        if (shell != null && nw == w && nh == h) return;
        if (session != null) session.close();
        w = nw;
        h = nh;
        session = new OffscreenRenderer.Session(w, h);
        if (shell == null) {
            boolean dark = Theme.resolveDark(j.settings.get("ui.theme", Theme.DARK), themed.getResources().getConfiguration());
            Ui.init(themed, dark);
            Ui.refreshMotion(themed, j.settings.get("ui.motion", "full"));
            shell = new Shell(host);
            shell.setInsets(Ui.dp(TOP_INSET_DP), Ui.dp(BOTTOM_INSET_DP), 0);
            navigateNow("home");
        }
        layoutShell();
    }

    /** Stops the frame driver and releases the GPU target; the next call reopens. */
    public void close() throws Exception {
        onUi(new Callable<Void>() { @Override public Void call() {
            if (shell != null) shell.destroy();
            shell = null;
            if (session != null) session.close();
            session = null;
            return null;
        } });
    }

    // ---- frames ---------------------------------------------------------------------------------------------------
    /** Keeps the frame driver ticking for ACTIVE_MS (plus extraMs) from now. Safe from any thread. */
    private void keepAlive(long extraMs) {
        activeUntil = Math.max(activeUntil, SystemClock.uptimeMillis() + ACTIVE_MS + extraMs);
        ui.post(new Runnable() { @Override public void run() {
            if (!running && shell != null) {
                running = true;
                Choreographer.getInstance().postFrameCallback(driver);
            }
        } });
    }

    private final Choreographer.FrameCallback driver = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (shell == null || SystemClock.uptimeMillis() > activeUntil) { running = false; return; }
            try {
                if ((vsync++ & 1) == 0) renderFrame();
            } catch (Throwable t) {
                // a failed frame must not take the app's main looper down; the next call restarts the driver
                running = false;
                activeUntil = 0;
                return;
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void layoutShell() {
        if (shell.isLayoutRequested() || shell.getMeasuredWidth() != w || shell.getMeasuredHeight() != h) {
            shell.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        }
        shell.layout(0, 0, w, h);
    }

    private void renderFrame() {
        long t0 = System.nanoTime();
        layoutShell();
        session.draw(shell);
        long ns = System.nanoTime() - t0;
        lastRenderNs = ns;
        renderNsTotal += ns;
        frames++;
    }

    // ---- screens --------------------------------------------------------------------------------------------------
    private void navigateNow(String target) {
        shell.navigate(target);
        screenId = target;
    }

    @Override public void navigate(final String screen) throws Exception {
        keepAlive(0);
        onUi(new Callable<Void>() { @Override public Void call() {
            ensureOpen();
            navigateNow(screen);
            return null;
        } });
    }

    @Override public JSONObject tree(final int maxDepth, final boolean onlyVisible) throws Exception {
        keepAlive(0);
        return onUi(new Callable<JSONObject>() { @Override public JSONObject call() {
            ensureOpen();
            layoutShell();
            JSONObject o = UiWalk.tree(shell, maxDepth, onlyVisible);
            J.put(o, "host", "headless");
            J.put(o, "screen", screenId);
            J.put(o, "visible", false);
            return o;
        } });
    }

    @Override public byte[] screenshot(final float scale, final String format) throws Exception {
        keepAlive(0);
        android.graphics.Bitmap full = onUi(new Callable<android.graphics.Bitmap>() { @Override public android.graphics.Bitmap call() {
            ensureOpen();
            renderFrame();
            return session.readback();
        } });
        android.graphics.Bitmap out = full;
        if (scale > 0 && scale < 0.999f) out = android.graphics.Bitmap.createScaledBitmap(full, Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), true);
        return UiWalk.encode(out, format);
    }

    // ---- input ----------------------------------------------------------------------------------------------------
    private void dispatch(int action, float x, float y, long down) {
        MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { shell.dispatchTouchEvent(e); } finally { e.recycle(); }
    }

    /** The deepest visible view under (x, y) in root coordinates. */
    static View hitTest(View v, float x, float y, View root) {
        if (!(v instanceof ViewGroup)) return v;
        ViewGroup g = (ViewGroup) v;
        for (int i = g.getChildCount() - 1; i >= 0; i--) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE) continue;
            int[] o = UiWalk.origin(c, root);
            if (x >= o[0] && y >= o[1] && x < o[0] + c.getWidth() && y < o[1] + c.getHeight()) return hitTest(c, x, y, root);
        }
        return v;
    }

    @Override public JSONObject tap(final UiHost.Selector sel, final float x0, final float y0, final boolean longPress) throws Exception {
        keepAlive(0);
        final float[] xy = {x0, y0};
        final View[] target = new View[1];
        final JSONObject hit = new JSONObject();
        final long down = SystemClock.uptimeMillis();
        onUi(new Callable<Void>() { @Override public Void call() throws Exception {
            ensureOpen();
            layoutShell();
            View v;
            if (!sel.empty()) {
                v = UiWalk.find(shell, sel);
                int[] o = UiWalk.origin(v, shell);
                xy[0] = o[0] + v.getWidth() / 2f;
                xy[1] = o[1] + v.getHeight() / 2f;
                J.put(hit, "class", v.getClass().getSimpleName());
                J.put(hit, "res", UiWalk.resName(v));
            } else {
                v = hitTest(shell, xy[0], xy[1], shell);
            }
            target[0] = UiWalk.clickTarget(v);
            dispatch(MotionEvent.ACTION_DOWN, xy[0], xy[1], down);
            return null;
        } });
        Thread.sleep(longPress ? 700 : 60);
        onUi(new Callable<Void>() { @Override public Void call() {
            dispatch(MotionEvent.ACTION_UP, xy[0], xy[1], down);
            View t = target[0];
            if (longPress && t.isLongClickable()) t.performLongClick();
            else if (!longPress && t.isClickable()) t.performClick();
            return null;
        } });
        J.put(hit, "x", xy[0]);
        J.put(hit, "y", xy[1]);
        return hit;
    }

    @Override public void swipe(final float x1, final float y1, final float x2, final float y2, long durationMs) throws Exception {
        keepAlive(durationMs);
        final long down = SystemClock.uptimeMillis();
        onUi(new Callable<Void>() { @Override public Void call() {
            ensureOpen();
            layoutShell();
            dispatch(MotionEvent.ACTION_DOWN, x1, y1, down);
            return null;
        } });
        int steps = (int) Math.max(4, Math.min(60, durationMs / 16));
        for (int i = 1; i <= steps; i++) {
            float f = i / (float) steps;
            Thread.sleep(Math.max(1, durationMs / steps));
            final float x = x1 + (x2 - x1) * f, y = y1 + (y2 - y1) * f;
            onUi(new Callable<Void>() { @Override public Void call() {
                dispatch(MotionEvent.ACTION_MOVE, x, y, down);
                return null;
            } });
        }
        onUi(new Callable<Void>() { @Override public Void call() {
            dispatch(MotionEvent.ACTION_UP, x2, y2, down);
            return null;
        } });
    }

    @Override public JSONObject type(final String text, final UiHost.Selector sel, final boolean replace) throws Exception {
        keepAlive(0);
        return onUi(new Callable<JSONObject>() { @Override public JSONObject call() throws Exception {
            ensureOpen();
            layoutShell();
            View target = !sel.empty() ? UiWalk.find(shell, sel) : shell.findFocus();
            if (!(target instanceof EditText)) throw new UiHost.Unavailable(sel.empty() ? "Нет поля с фокусом: задайте локатор поля." : "Найденный элемент не поле ввода: " + sel);
            EditText et = (EditText) target;
            et.requestFocus();
            String old = et.getText().toString();
            String next = replace ? text : old + text;
            et.setText(next);
            et.setSelection(next.length());
            return UiWalk.boundsOf(et, shell);
        } });
    }

    @Override public void key(final int keyCode) throws Exception {
        keepAlive(0);
        onUi(new Callable<Void>() { @Override public Void call() {
            ensureOpen();
            if (keyCode == KeyEvent.KEYCODE_BACK) { shell.onBack(); return null; }
            long now = SystemClock.uptimeMillis();
            shell.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            shell.dispatchKeyEvent(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0));
            return null;
        } });
    }

    @Override public JSONObject waitFor(final UiHost.Selector sel, long timeoutMs) throws Exception {
        keepAlive(timeoutMs);
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (true) {
            try {
                return onUi(new Callable<JSONObject>() { @Override public JSONObject call() throws Exception {
                    ensureOpen();
                    layoutShell();
                    return UiWalk.boundsOf(UiWalk.find(shell, sel), shell);
                } });
            } catch (UiHost.Unavailable e) {
                if (SystemClock.uptimeMillis() > end) return null;
            }
            Thread.sleep(100);
        }
    }

    // ---- animation probe ------------------------------------------------------------------------------------------
    @Override public JSONObject animProbe(final UiHost.Selector sel, final String property, final long durationMs) throws Exception {
        keepAlive(durationMs);
        final View v = onUi(new Callable<View>() { @Override public View call() throws Exception {
            ensureOpen();
            layoutShell();
            return UiWalk.find(shell, sel);
        } });
        final JSONArray samples = new JSONArray();
        final CountDownLatch done = new CountDownLatch(1);
        final long[] t0 = {0};
        final Choreographer.FrameCallback cb = new Choreographer.FrameCallback() {
            @Override public void doFrame(long frameTimeNanos) {
                if (t0[0] == 0) t0[0] = frameTimeNanos;
                long t = (frameTimeNanos - t0[0]) / 1_000_000L;
                double rv = UiWalk.read(v, property);
                samples.put(UiWalk.sample(t, Double.isNaN(rv) ? 0 : rv));
                if (t >= durationMs) { done.countDown(); return; }
                Choreographer.getInstance().postFrameCallback(this);
            }
        };
        ui.post(new Runnable() { @Override public void run() { Choreographer.getInstance().postFrameCallback(cb); } });
        if (!done.await(durationMs + 3000, TimeUnit.MILLISECONDS)) throw new UiHost.Unavailable("Кадры не приходят (окно не отрисовывается?).");
        return UiWalk.summarize(property, samples);
    }

    // ---- conveniences for the debug check (no package-private types in signatures) ---------------------------------
    static UiHost.Selector selector(String key, String value) {
        UiHost.Selector s = new UiHost.Selector();
        switch (key) {
            case "text": s.text = value; break;
            case "desc": s.desc = value; break;
            case "res": s.res = value; break;
            case "tag": s.tag = value; break;
            case "class": s.cls = value; break;
            case "path": s.path = value; break;
            default: throw new IllegalArgumentException("unknown locator " + key);
        }
        return s;
    }

    public JSONObject tapBy(String key, String value) throws Exception { return tap(selector(key, value), 0, 0, false); }

    public JSONObject typeBy(String key, String value, String text) throws Exception { return type(text, selector(key, value), false); }

    public JSONObject probeBy(String key, String value, String property, long durationMs) throws Exception {
        return animProbe(selector(key, value), property, durationMs);
    }

    /** What a window would give the screens, routed to this invisible shell. */
    private final class HostImpl implements Host {
        @Override public Context context() { return themed; }
        @Override public android.app.Activity activity() { return null; }
        @Override public Jarvis jarvis() { return j; }
        @Override public OverlayHost overlays() { return shell.overlays(); }
        @Override public void navigate(String target) { navigateNow(target); }
        @Override public void pickDocument(Screen requester, int requestCode, String[] mimeTypes, boolean multiple) { note("Выбор файлов в невидимом окне недоступен."); }
        @Override public void requestPermission(String androidPermission) { }
        @Override public boolean hasPermission(String androidPermission) {
            try { return themed.checkSelfPermission(androidPermission) == android.content.pm.PackageManager.PERMISSION_GRANTED; }
            catch (Throwable t) { return false; }
        }
        @Override public void hideKeyboard() { }
        @Override public void copyToClipboard(String label, String text) { note("Буфер обмена из невидимого окна не меняется."); }
        @Override public void shareFile(File f, String mime) { note("Отправка файла из невидимого окна недоступна."); }
        @Override public void openUrl(String url) { note("Открытие ссылок из невидимого окна отключено."); }
        @Override public void openBatterySettings() { note("Системные настройки из невидимого окна не открываются."); }
        @Override public int bottomInset() { return Ui.dp(BOTTOM_INSET_DP); }
        @Override public int topInset() { return Ui.dp(TOP_INSET_DP); }
        @Override public void startActivityForResult(Screen requester, Intent intent, int requestCode) { note("Системные окна из невидимого окна не открываются."); }
        @Override public boolean persistsLastScreen() { return false; }

        private void note(String ru) {
            if (shell != null) shell.overlays().toast(ru, 1);
        }
    }
}
