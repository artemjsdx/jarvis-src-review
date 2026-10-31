package app.jarvis.ui.chat.list;

/** Pure scroll arithmetic for the chat container: anchored restore after a height change above the viewport, stick-to-bottom. */
public final class ScrollMath {
    private ScrollMath() { }

    public static int maxScroll(int contentHeight, int viewportHeight) {
        return Math.max(0, contentHeight - viewportHeight);
    }

    /**
     * Scroll that keeps an anchor row at the same screen offset. anchorOffset = anchor.top - scrollY when it was captured;
     * anchorTopNow = the anchor's top after the layout pass.
     */
    public static int anchoredScroll(int anchorTopNow, int anchorOffset, int contentHeight, int viewportHeight) {
        return clamp(anchorTopNow - anchorOffset, 0, maxScroll(contentHeight, viewportHeight));
    }

    public static boolean atBottom(int scrollY, int contentHeight, int viewportHeight, int slack) {
        return scrollY >= maxScroll(contentHeight, viewportHeight) - slack;
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
