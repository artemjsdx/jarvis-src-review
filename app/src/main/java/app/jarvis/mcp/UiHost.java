package app.jarvis.mcp;

import org.json.JSONObject;

/** A surface the UI tools operate on: the visible app window (live) or, after spike S1, a private virtual display (headless). */
interface UiHost {
    /** Thrown by hosts that exist only as a skeleton; mapped to NOT_IMPLEMENTED. */
    final class NotImplemented extends RuntimeException {
        NotImplemented(String m) { super(m); }
    }

    /** Thrown when the host exists but cannot serve the call now (no window yet, app in background). Message is user-safe Russian. */
    class Unavailable extends Exception {
        Unavailable(String m) { super(m); }
    }

    /** The window is not on screen (app closed, in background, or not started): the hint for the user is in the message. */
    final class NotVisible extends Unavailable {
        NotVisible(String m) { super(m); }
    }

    /** Element locator: any combination of text/desc/res/tag/cls/path, plus index among matches. */
    final class Selector {
        String text = "", desc = "", res = "", tag = "", cls = "", path = "";
        int index;
        boolean empty() { return text.isEmpty() && desc.isEmpty() && res.isEmpty() && tag.isEmpty() && cls.isEmpty() && path.isEmpty(); }

        static Selector from(JSONObject o) {
            Selector s = new Selector();
            if (o == null) return s;
            s.text = o.optString("text", "");
            s.desc = o.optString("desc", "");
            s.res = o.optString("res", "");
            s.tag = o.optString("tag", "");
            s.cls = o.optString("class", "");
            s.path = o.optString("path", "");
            s.index = o.optInt("index", 0);
            return s;
        }

        @Override public String toString() {
            StringBuilder sb = new StringBuilder();
            if (!text.isEmpty()) sb.append("text=").append(text).append(' ');
            if (!desc.isEmpty()) sb.append("desc=").append(desc).append(' ');
            if (!res.isEmpty()) sb.append("res=").append(res).append(' ');
            if (!tag.isEmpty()) sb.append("tag=").append(tag).append(' ');
            if (!cls.isEmpty()) sb.append("class=").append(cls).append(' ');
            if (!path.isEmpty()) sb.append("path=").append(path).append(' ');
            return sb.toString().trim();
        }
    }

    String id();

    /** Short state for ui_hosts: available, visible, sizePx, reason. */
    JSONObject describe();

    /** Makes the host ready (the headless one creates its shell); returns describe(). */
    JSONObject open(int width, int height) throws Exception;

    JSONObject tree(int maxDepth, boolean onlyVisible) throws Exception;

    /** Encoded image bytes. */
    byte[] screenshot(float scale, String format) throws Exception;

    /** @return the bounds (x, y, w, h) of what was hit, as JSON. */
    JSONObject tap(Selector sel, float x, float y, boolean longPress) throws Exception;

    void swipe(float x1, float y1, float x2, float y2, long durationMs) throws Exception;

    JSONObject type(String text, Selector sel, boolean replace) throws Exception;

    void key(int keyCode) throws Exception;

    /** Polls until the selector matches or the time is up; returns the match (bounds etc.) or null. */
    JSONObject waitFor(Selector sel, long timeoutMs) throws Exception;

    void navigate(String screen) throws Exception;

    /** Samples a view property once per frame for durationMs. */
    JSONObject animProbe(Selector sel, String property, long durationMs) throws Exception;
}
