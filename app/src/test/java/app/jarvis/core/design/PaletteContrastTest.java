package app.jarvis.core.design;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PaletteContrastTest {
    private static void check(String theme, PaletteSpec.Colors p) {
        String[] names = {"text", "text2", "text3", "accent"};
        int[] fg = {p.text, p.text2, p.text3, p.accent};
        double[] need = {4.5, 4.5, 3.0, 3.0};
        int[] surfaces = {p.bg0, p.bg1};
        for (int s = 0; s < surfaces.length; s++) {
            for (int i = 0; i < fg.length; i++) {
                double r = PaletteSpec.contrast(fg[i], surfaces[s]);
                String msg = String.format("%s %s on bg%d: %.2f < %.1f", theme, names[i], s, r, need[i]);
                assertTrue(msg, r >= need[i]);
            }
        }
    }

    @Test public void darkThemeMeetsWcag() { check("dark", PaletteSpec.dark()); }

    @Test public void lightThemeMeetsWcag() { check("light", PaletteSpec.light()); }

    @Test public void knownReferenceRatios() {
        // white on black is 21:1 by definition; guards the luminance formula itself
        assertTrue(Math.abs(PaletteSpec.contrast(0xFFFFFFFF, 0xFF000000) - 21.0) < 0.01);
        assertTrue(Math.abs(PaletteSpec.contrast(0xFF777777, 0xFF777777) - 1.0) < 1e-9);
    }

    @Test public void themesDifferInPolarity() {
        assertTrue(PaletteSpec.dark().dark);
        assertTrue(!PaletteSpec.light().dark);
    }
}
