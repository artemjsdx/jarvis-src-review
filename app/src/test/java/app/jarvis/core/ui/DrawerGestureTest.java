package app.jarvis.core.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DrawerGestureTest {
    private static final float EDGE = 24, SLOP = 16;

    @Test
    public void rightwardDragFromEdgeOpensClosedDrawer() {
        DrawerGesture g = new DrawerGesture(EDGE, SLOP);
        g.down(10, 300, false);
        assertFalse(g.claims(20, 300));
        assertTrue(g.claims(30, 302));
    }

    @Test
    public void dragStartedOutsideEdgeIsLeftToContent() {
        DrawerGesture g = new DrawerGesture(EDGE, SLOP);
        g.down(120, 300, false);
        assertFalse(g.claims(300, 300));
    }

    @Test
    public void verticalOrDiagonalDragIsNotClaimed() {
        DrawerGesture g = new DrawerGesture(EDGE, SLOP);
        g.down(10, 300, false);
        assertFalse(g.claims(10, 400));
        assertFalse(g.claims(30, 340));
    }

    @Test
    public void leftwardDragClosesOpenDrawerFromAnywhere() {
        DrawerGesture g = new DrawerGesture(EDGE, SLOP);
        g.down(300, 300, true);
        assertFalse(g.claims(290, 300));
        assertTrue(g.claims(270, 301));
        g.down(300, 300, true);
        assertFalse(g.claims(340, 300));
    }

    @Test
    public void settleByPositionOrFling() {
        assertTrue(DrawerGesture.settleOpen(0.6f, 0));
        assertFalse(DrawerGesture.settleOpen(0.4f, 0));
        assertTrue(DrawerGesture.settleOpen(0.1f, 2000));
        assertFalse(DrawerGesture.settleOpen(0.9f, -2000));
    }
}
