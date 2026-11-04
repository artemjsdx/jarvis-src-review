package app.jarvis.ui.glass;

import android.os.Build;

/**
 * Glass quality, from the user's setting {@code ui.glass}. AUTO starts at FULL and steps down to REDUCED (never silently OFF) when the
 * measured frame time of glass animations stays above budget (see {@link FrameGovernor}).
 */
public enum GlassLevel {
    AUTO("auto"), FULL("full"), REDUCED("reduced"), OFF("off");

    /** Settings key; values are the enum keys above. Missing or unknown values mean AUTO. */
    public static final String KEY = "ui.glass";

    public final String key;

    private static volatile GlassLevel preferred = AUTO;
    private static volatile GlassLevel automatic = FULL;

    GlassLevel(String key) { this.key = key; }

    public static GlassLevel parse(String value) {
        for (GlassLevel l : values()) if (l.key.equals(value)) return l;
        return AUTO;
    }

    /** Applies the user's choice; AUTO restarts from FULL. */
    public static void configure(String value) {
        preferred = parse(value);
        automatic = FULL;
    }

    public static GlassLevel preferred() { return preferred; }

    /** The level the renderer uses now. Blur needs RenderEffect (API 31+); below that glass is the translucent gradient. */
    public static GlassLevel effective() {
        if (Build.VERSION.SDK_INT < 31) return OFF;
        return preferred == AUTO ? automatic : preferred;
    }

    /** Blur is on at FULL and REDUCED. */
    public static boolean blur() { return effective() != OFF; }

    /** Refraction (AGSL lens) only at FULL on API 33+. */
    public static boolean refraction() { return effective() == FULL && Build.VERSION.SDK_INT >= 33; }

    /** Called by the governor only while the preference is AUTO. @return true if the level changed. */
    static boolean stepDown() {
        GlassLevel now = automatic;
        if (now == FULL) automatic = REDUCED;
        else return false; // Never silently remove backdrop blur from glass cards.
        return true;
    }
}
