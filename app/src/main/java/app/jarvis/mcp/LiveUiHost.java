package app.jarvis.mcp;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.widget.EditText;
import app.jarvis.core.util.J;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.glass.OffscreenRenderer;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** The window the owner sees: view tree, screenshots, injected touches/keys and text through the real view hierarchy. */
final class LiveUiHost implements UiHost {
    private final Jarvis j;
    private final Handler ui = new Handler(Looper.getMainLooper());

    LiveUiHost(Jarvis j) { this.j = j; }

    @Override public String id() { return "live"; }

    static boolean present() { return LiveUi.activity() != null; }

    @Override public JSONObject open(int width, int height) { return describe(); }

    @Override public JSONObject describe() {
        JSONObject o = new JSONObject();
        J.put(o, "id", "live");
        Activity a = LiveUi.activity();
        J.put(o, "available", a != null);
        J.put(o, "visible", LiveUi.visible());
        if (a == null) J.put(o, "reason", "Окно приложения не создано: откройте Jarvis на телефоне (ничего нельзя делать с экраном, которого нет).");
        else if (!LiveUi.visible()) J.put(o, "reason", "Приложение в фоне: дерево доступно, снимок рисуется программно (без размытия), касания дойдут до видов.");
        else {
            try {
                View d = a.getWindow().getDecorView();
                J.put(o, "sizePx", new JSONArray().put(d.getWidth()).put(d.getHeight()));
            } catch (Throwable ignored) { }
        }
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

    private View decor() throws UiHost.Unavailable {
        Activity a = LiveUi.activity();
        if (a == null) throw new UiHost.NotVisible("Окно Jarvis не открыто: откройте приложение на телефоне.");
        return a.getWindow().getDecorView();
    }

    // ---- tree -----------------------------------------------------------------------------------------------------
    @Override public JSONObject tree(final int maxDepth, final boolean onlyVisible) throws Exception {
        return onUi(new Callable<JSONObject>() { @Override public JSONObject call() throws Exception {
            View root = decor();
            JSONObject o = UiWalk.tree(root, maxDepth, onlyVisible);
            J.put(o, "host", "live");
            J.put(o, "visible", LiveUi.visible());
            return o;
        } });
    }

    // ---- screenshot -----------------------------------------------------------------------------------------------
    @Override public byte[] screenshot(final float scale, final String format) throws Exception {
        final View d = onUi(new Callable<View>() { @Override public View call() throws Exception { return decor(); } });
        final int w = Math.max(1, d.getWidth()), h = Math.max(1, d.getHeight());
        Bitmap full = null;
        if (LiveUi.visible() && Build.VERSION.SDK_INT >= 26) full = pixelCopy(w, h);
        if (full == null) full = offscreen(d, w, h);
        Bitmap out = full;
        if (scale > 0 && scale < 0.999f) out = Bitmap.createScaledBitmap(full, Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), true);
        return UiWalk.encode(out, format);
    }

    /** GPU render of the decor view without a window; software draw when the GPU path is unavailable. */
    private Bitmap offscreen(final View d, final int w, final int h) throws Exception {
        try {
            return onUi(new Callable<Bitmap>() { @Override public Bitmap call() { return OffscreenRenderer.render(d, w, h); } });
        } catch (Throwable gpuUnavailable) {
            final Bitmap soft = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            onUi(new Callable<Void>() { @Override public Void call() { d.draw(new Canvas(soft)); return null; } });
            return soft;
        }
    }

    private Bitmap pixelCopy(int w, int h) {
        try {
            final Activity a = LiveUi.activity();
            if (a == null) return null;
            final Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final int[] result = {-1};
            final CountDownLatch latch = new CountDownLatch(1);
            PixelCopy.request(a.getWindow(), bmp, new PixelCopy.OnPixelCopyFinishedListener() {
                @Override public void onPixelCopyFinished(int copyResult) { result[0] = copyResult; latch.countDown(); }
            }, ui);
            if (!latch.await(5, TimeUnit.SECONDS) || result[0] != PixelCopy.SUCCESS) return null;
            return bmp;
        } catch (Throwable t) { return null; }
    }

    // ---- input ----------------------------------------------------------------------------------------------------
    private void touch(final int action, final float x, final float y, final long down) throws Exception {
        onUi(new Callable<Void>() { @Override public Void call() throws Exception {
            MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
            e.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try { decor().dispatchTouchEvent(e); } finally { e.recycle(); }
            return null;
        } });
    }

    @Override public JSONObject tap(final UiHost.Selector sel, float x, float y, boolean longPress) throws Exception {
        JSONObject hit;
        if (!sel.empty()) {
            final float[] c = new float[2];
            hit = onUi(new Callable<JSONObject>() { @Override public JSONObject call() throws Exception {
                View v = UiWalk.find(decor(), sel);
                int[] loc = UiWalk.origin(v, decor());
                c[0] = loc[0] + v.getWidth() / 2f;
                c[1] = loc[1] + v.getHeight() / 2f;
                return UiWalk.boundsOf(v, decor());
            } });
            x = c[0];
            y = c[1];
        } else hit = new JSONObject();
        long down = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, x, y, down);
        Thread.sleep(longPress ? 700 : 60);
        touch(MotionEvent.ACTION_UP, x, y, down);
        J.put(hit, "x", x);
        J.put(hit, "y", y);
        return hit;
    }

    @Override public void swipe(float x1, float y1, float x2, float y2, long durationMs) throws Exception {
        long down = SystemClock.uptimeMillis();
        int steps = (int) Math.max(4, Math.min(60, durationMs / 16));
        touch(MotionEvent.ACTION_DOWN, x1, y1, down);
        for (int i = 1; i <= steps; i++) {
            float f = i / (float) steps;
            Thread.sleep(Math.max(1, durationMs / steps));
            touch(MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * f, y1 + (y2 - y1) * f, down);
        }
        touch(MotionEvent.ACTION_UP, x2, y2, down);
    }

    @Override public JSONObject type(final String text, final UiHost.Selector sel, final boolean replace) throws Exception {
        return onUi(new Callable<JSONObject>() { @Override public JSONObject call() throws Exception {
            View target = !sel.empty() ? UiWalk.find(decor(), sel) : decor().findFocus();
            if (!(target instanceof EditText)) throw new UiHost.Unavailable(sel.empty() ? "Нет поля с фокусом: задайте локатор поля." : "Найденный элемент не поле ввода: " + sel);
            EditText et = (EditText) target;
            et.requestFocus();
            String old = et.getText().toString();
            String next = replace ? text : old + text;
            et.setText(next);
            et.setSelection(next.length());
            return UiWalk.boundsOf(et, decor());
        } });
    }

    @Override public void key(final int keyCode) throws Exception {
        onUi(new Callable<Void>() { @Override public Void call() throws Exception {
            View d = decor();
            long now = SystemClock.uptimeMillis();
            d.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            d.dispatchKeyEvent(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0));
            return null;
        } });
    }

    @Override public JSONObject waitFor(final UiHost.Selector sel, long timeoutMs) throws Exception {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (true) {
            try {
                return onUi(new Callable<JSONObject>() { @Override public JSONObject call() throws Exception { return UiWalk.boundsOf(UiWalk.find(decor(), sel), decor()); } });
            } catch (UiHost.Unavailable e) {
                if (SystemClock.uptimeMillis() > end) return null;
            }
            Thread.sleep(100);
        }
    }

    @Override public void navigate(String screen) { j.navigate(screen); }

    // ---- animation probe ------------------------------------------------------------------------------------------
    @Override public JSONObject animProbe(final UiHost.Selector sel, final String property, final long durationMs) throws Exception {
        final View v = onUi(new Callable<View>() { @Override public View call() throws Exception { return UiWalk.find(decor(), sel); } });
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
}
