package app.jarvis.ui.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.ui.chat.list.ScrollMath;
import org.junit.Test;

public class ScrollMathTest {
    @Test
    public void expandAboveViewportKeepsAnchorOnScreen() {
        // anchor was at top 1000 with scroll 900 -> offset 100; a row above grew by 120 -> anchor top 1120
        int offset = 1000 - 900;
        int scroll = ScrollMath.anchoredScroll(1120, offset, 3000, 800);
        assertEquals(1020, scroll);
        assertEquals(100, 1120 - scroll);
    }

    @Test
    public void collapseClampsToRange() {
        assertEquals(0, ScrollMath.anchoredScroll(50, 100, 3000, 800));
        assertEquals(2200, ScrollMath.anchoredScroll(9000, 100, 3000, 800));
    }

    @Test
    public void stickyBottomAndSlack() {
        assertEquals(2200, ScrollMath.maxScroll(3000, 800));
        assertEquals(0, ScrollMath.maxScroll(300, 800));
        assertTrue(ScrollMath.atBottom(2190, 3000, 800, 12));
        assertFalse(ScrollMath.atBottom(2100, 3000, 800, 12));
    }
}
