package app.jarvis.mcp;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Checkable;
import android.widget.EditText;
import android.widget.TextView;
import app.jarvis.core.util.J;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * View-tree walker shared by the live and the headless host. Bounds are relative to the root, computed from the layout, so the
 * same code works on a window-less tree (where getLocationInWindow is meaningless).
 */
final class UiWalk {
    static final int MAX_NODES = 4000;

    private UiWalk() { }

    static JSONObject tree(View root, int maxDepth, boolean onlyVisible) {
        int[] count = {0};
        JSONObject r = node(root, root, "0", 0, maxDepth, onlyVisible, count);
        JSONObject o = new JSONObject();
        J.put(o, "size", new JSONArray().put(root.getWidth()).put(root.getHeight()));
        J.put(o, "nodes", count[0]);
        J.put(o, "truncated", count[0] >= MAX_NODES);
        J.put(o, "root", r);
        return o;
    }

    private static JSONObject node(View root, View v, String path, int depth, int maxDepth, boolean onlyVisible, int[] count) {
        count[0]++;
        JSONObject o = new JSONObject();
        J.put(o, "path", path);
        J.put(o, "class", v.getClass().getSimpleName().isEmpty() ? v.getClass().getName() : v.getClass().getSimpleName());
        String res = resName(v);
        if (!res.isEmpty()) J.put(o, "res", res);
        if (v.getTag() instanceof CharSequence) J.put(o, "tag", v.getTag().toString());
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) J.put(o, "text", clip(t.toString(), 300));
            if (v instanceof EditText && ((TextView) v).getHint() != null) J.put(o, "hint", ((TextView) v).getHint().toString());
        }
        if (v.getContentDescription() != null) J.put(o, "desc", v.getContentDescription().toString());
        J.put(o, "bounds", boundsArray(v, root));
        boolean shown = v.getVisibility() == View.VISIBLE && v.getAlpha() > 0.01f && v.getWidth() > 0 && v.getHeight() > 0;
        J.put(o, "visible", shown);
        if (!v.isEnabled()) J.put(o, "enabled", false);
        if (v.isClickable()) J.put(o, "clickable", true);
        if (v.isFocused()) J.put(o, "focused", true);
        if (v instanceof Checkable) J.put(o, "checked", ((Checkable) v).isChecked());
        if (v.getAlpha() < 0.99f) J.put(o, "alpha", Math.round(v.getAlpha() * 100) / 100.0);
        if (v instanceof ViewGroup && depth < maxDepth) {
            ViewGroup g = (ViewGroup) v;
            JSONArray kids = new JSONArray();
            for (int i = 0; i < g.getChildCount() && count[0] < MAX_NODES; i++) {
                View c = g.getChildAt(i);
                if (onlyVisible && c.getVisibility() != View.VISIBLE) continue;
                kids.put(node(root, c, path + "." + i, depth + 1, maxDepth, onlyVisible, count));
            }
            if (kids.length() > 0) J.put(o, "children", kids);
        } else if (v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 0) {
            J.put(o, "childCount", ((ViewGroup) v).getChildCount());
        }
        return o;
    }

    static String resName(View v) {
        if (v.getId() == View.NO_ID) return "";
        try { return v.getResources().getResourceEntryName(v.getId()); } catch (Throwable t) { return ""; }
    }

    private static String clip(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }

    /** Top-left of v in root coordinates: layout position, translation, and the scroll of every ancestor. */
    static int[] origin(View v, View root) {
        int x = 0, y = 0;
        View c = v;
        while (c != null && c != root) {
            x += c.getLeft() + Math.round(c.getTranslationX());
            y += c.getTop() + Math.round(c.getTranslationY());
            View p = c.getParent() instanceof View ? (View) c.getParent() : null;
            if (p != null) {
                x -= p.getScrollX();
                y -= p.getScrollY();
            }
            c = p;
        }
        return new int[]{x, y};
    }

    static JSONArray boundsArray(View v, View root) {
        int[] o = origin(v, root);
        return new JSONArray().put(o[0]).put(o[1]).put(v.getWidth()).put(v.getHeight());
    }

    static boolean matches(View v, String path, UiHost.Selector s) {
        if (!s.path.isEmpty() && !s.path.equals(path)) return false;
        if (!s.res.isEmpty() && !s.res.equals(resName(v))) return false;
        if (!s.cls.isEmpty() && !s.cls.equalsIgnoreCase(v.getClass().getSimpleName())) return false;
        if (!s.tag.isEmpty() && !(v.getTag() instanceof CharSequence && v.getTag().toString().contains(s.tag))) return false;
        if (!s.desc.isEmpty()) {
            CharSequence d = v.getContentDescription();
            if (d == null || !d.toString().toLowerCase(Locale.ROOT).contains(s.desc.toLowerCase(Locale.ROOT))) return false;
        }
        if (!s.text.isEmpty()) {
            if (!(v instanceof TextView)) return false;
            String t = ((TextView) v).getText().toString().toLowerCase(Locale.ROOT);
            String want = s.text.toLowerCase(Locale.ROOT);
            if (want.startsWith("=") ? !t.equals(want.substring(1)) : !t.contains(want)) return false;
        }
        return true;
    }

    private static void collect(View v, String path, UiHost.Selector s, List<View> out, int limit) {
        if (out.size() >= limit) return;
        if (matches(v, path, s) && v.getVisibility() == View.VISIBLE && v.getWidth() > 0) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), path + "." + i, s, out, limit);
        }
    }

    static View find(View root, UiHost.Selector s) throws UiHost.Unavailable {
        if (s.empty()) throw new UiHost.Unavailable("Не задан локатор (text, desc, res, tag, class или path).");
        List<View> hits = new ArrayList<>();
        collect(root, "0", s, hits, s.index + 1);
        if (hits.size() <= s.index) throw new UiHost.Unavailable("Элемент не найден: " + s);
        return hits.get(s.index);
    }

    /** The element itself or its nearest clickable ancestor: where a tap on it lands. */
    static View clickTarget(View v) {
        for (View c = v; c != null; c = c.getParent() instanceof View ? (View) c.getParent() : null) {
            if (c.isClickable()) return c;
        }
        return v;
    }

    static JSONObject boundsOf(View v, View root) {
        JSONObject o = new JSONObject();
        J.put(o, "class", v.getClass().getSimpleName());
        J.put(o, "res", resName(v));
        J.put(o, "bounds", boundsArray(v, root));
        return o;
    }

    static byte[] encode(android.graphics.Bitmap bmp, String format) {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        android.graphics.Bitmap.CompressFormat cf = "png".equals(format) ? android.graphics.Bitmap.CompressFormat.PNG
            : "webp".equals(format) ? (android.os.Build.VERSION.SDK_INT >= 30 ? android.graphics.Bitmap.CompressFormat.WEBP_LOSSY : android.graphics.Bitmap.CompressFormat.WEBP)
            : android.graphics.Bitmap.CompressFormat.JPEG;
        bmp.compress(cf, 82, bo);
        return bo.toByteArray();
    }

    static JSONArray sample(long t, double v) {
        try { return new JSONArray().put(t).put(v); } catch (org.json.JSONException e) { return new JSONArray(); }
    }

    /** Min/max/changes of a sampled series, plus the raw samples [ms, value]. */
    static JSONObject summarize(String property, JSONArray samples) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        int changes = 0;
        double prev = Double.NaN;
        for (int i = 0; i < samples.length(); i++) {
            double val = samples.optJSONArray(i).optDouble(1);
            min = Math.min(min, val);
            max = Math.max(max, val);
            if (!Double.isNaN(prev) && Math.abs(val - prev) > 1e-6) changes++;
            prev = val;
        }
        JSONObject o = new JSONObject();
        J.put(o, "property", property);
        J.put(o, "frames", samples.length());
        J.put(o, "min", min);
        J.put(o, "max", max);
        J.put(o, "changedFrames", changes);
        J.put(o, "avgFrameMs", samples.length() > 1 ? samples.optJSONArray(samples.length() - 1).optLong(0) / (double) (samples.length() - 1) : 0);
        J.put(o, "samples", samples);
        return o;
    }

    static double read(View v, String p) {
        switch (p) {
            case "alpha": return v.getAlpha();
            case "translationX": return v.getTranslationX();
            case "translationY": return v.getTranslationY();
            case "scaleX": return v.getScaleX();
            case "scaleY": return v.getScaleY();
            case "rotation": return v.getRotation();
            case "x": return v.getX();
            case "y": return v.getY();
            case "width": return v.getWidth();
            case "height": return v.getHeight();
            default: return Double.NaN;
        }
    }
}
