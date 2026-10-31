package app.jarvis.core.design;

/**
 * Both colour schemes as plain ARGB values. No Android classes: the contrast rules are checked on the JVM
 * (PaletteContrastTest), and ui.Ui.Pal copies these values at runtime.
 */
public final class PaletteSpec {
    private PaletteSpec() { }

    public static final class Colors {
        public boolean dark;
        public int bg0, bg1, glassTop, glassBottom, strokeHi, strokeLo, text, text2, text3, accent, accent2, accentSoft, onAccent,
            ok, warn, err, scrim, field, bubbleUser, divider, ripple, blobA, blobB;
    }

    public static Colors dark() {
        Colors p = new Colors();
        p.dark = true;
        p.bg0 = 0xFF07070B; p.bg1 = 0xFF0F0E1A;
        p.glassTop = 0x1FFFFFFF; p.glassBottom = 0x0DFFFFFF;
        p.strokeHi = 0x47FFFFFF; p.strokeLo = 0x2EFFFFFF;
        p.text = 0xFFF4F2FF; p.text2 = 0xFFB4B0CC; p.text3 = 0xFFA7A2BF;
        p.accent = 0xFFB9A6FF; p.accent2 = 0xFF7C5CFF; p.accentSoft = 0x33B9A6FF; p.onAccent = 0xFF140F2E;
        p.ok = 0xFF8FD6B0; p.warn = 0xFFE8C77A; p.err = 0xFFF08A9A;
        p.scrim = 0xA6040408; p.field = 0x26FFFFFF; p.bubbleUser = 0x40B9A6FF; p.divider = 0x2EFFFFFF; p.ripple = 0x33B9A6FF;
        p.blobA = 0xFF5B3FD8; p.blobB = 0xFFB9A6FF;
        return p;
    }

    public static Colors light() {
        Colors p = new Colors();
        p.dark = false;
        p.bg0 = 0xFFF6F5FB; p.bg1 = 0xFFEAE8F6;
        p.glassTop = 0xB3FFFFFF; p.glassBottom = 0x80FFFFFF;
        p.strokeHi = 0xCCFFFFFF; p.strokeLo = 0x2E1B1740;
        p.text = 0xFF14122B; p.text2 = 0xFF4F4B6B; p.text3 = 0xFF5E5974;
        p.accent = 0xFF6A4BEB; p.accent2 = 0xFF8F78FF; p.accentSoft = 0x266A4BEB; p.onAccent = 0xFFFFFFFF;
        p.ok = 0xFF2E8B64; p.warn = 0xFF9A7110; p.err = 0xFFC2374F;
        p.scrim = 0x8C14122B; p.field = 0x14140B2B; p.bubbleUser = 0x266A4BEB; p.divider = 0x1F1B1740; p.ripple = 0x266A4BEB;
        p.blobA = 0xFFB9A6FF; p.blobB = 0xFFD9CFFF;
        return p;
    }

    /** WCAG 2.x contrast ratio between two colours; alpha is ignored (callers pass opaque surfaces). */
    public static double contrast(int fg, int bg) {
        double a = luminance(fg), b = luminance(bg);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    static double luminance(int argb) {
        return 0.2126 * linear((argb >> 16) & 0xFF) + 0.7152 * linear((argb >> 8) & 0xFF) + 0.0722 * linear(argb & 0xFF);
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
