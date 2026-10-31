package app.jarvis.ui.glass;

import static org.junit.Assert.*;

import app.jarvis.ui.glass.Sdf;
import org.junit.Test;

public class SdfTest {
    private static final float EPS = 1e-4f;

    @Test public void insideIsNegativeAndDepthIsDistanceToNearestEdge() {
        // rect 40x20 around (50,50), corner radius 5: the short side is 10 px from the centre to the edge
        assertEquals(-10f, Sdf.roundedRect(50, 50, 50, 50, 20, 10, 5), EPS);
    }

    @Test public void edgeIsZeroAndOutsideIsDistance() {
        assertEquals(0f, Sdf.roundedRect(70, 50, 50, 50, 20, 10, 5), EPS);
        assertEquals(10f, Sdf.roundedRect(80, 50, 50, 50, 20, 10, 5), EPS);
    }

    @Test public void cornerIsRounded() {
        // the sharp corner (70,60) lies outside the rounded shape by r(sqrt2-1)
        float expected = 5f * ((float) Math.sqrt(2) - 1f);
        assertEquals(expected, Sdf.roundedRect(70, 60, 50, 50, 20, 10, 5), EPS);
        assertEquals(0f, Sdf.roundedRect(70, 60, 50, 50, 20, 10, 0), EPS);
    }

    @Test public void zeroRadiusIsAPlainBox() {
        assertEquals(0f, Sdf.roundedRect(70, 60, 50, 50, 20, 10, 0), EPS);
    }
}
