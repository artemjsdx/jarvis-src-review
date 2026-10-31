package app.jarvis.ui.motion;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.graphics.drawable.GradientDrawable;
import android.view.HapticFeedbackConstants;
import android.view.View;
import app.jarvis.core.design.Bezier;
import app.jarvis.core.design.Spring;

/**
 * Motion kit: durations, curves, enter/exit transitions and the user's motion mode.
 * Modes: FULL (as designed), REDUCED (no movement, fades capped at 80 ms), OFF (every change is instant).
 * Call sites never check the mode themselves: they ask {@link #move} or {@link #fade} for a duration (0 = skip).
 */
public final class Motion {
    private Motion() { }

    public static final int FULL = 0, REDUCED = 1, OFF = 2;
    public static final int D_FAST = 120, D_BASE = 200, D_SLOW = 320, D_XSLOW = 480;
    private static final int REDUCED_FADE_MAX = 80;

    private static volatile int mode = FULL;

    /** The three transition kinds used by overlays. */
    public enum Kind { DIALOG, SHEET, TOAST, POPUP, FADE_THROUGH }

    public static final TimeInterpolator DECELERATE = curve(new Bezier(0.05, 0.7, 0.1, 1.0));
    public static final TimeInterpolator ACCELERATE = curve(new Bezier(0.3, 0.0, 0.8, 0.15));
    public static final TimeInterpolator STANDARD = curve(new Bezier(0.2, 0.0, 0.0, 1.0));
    /** Damped spring (one slight overshoot) for thumbs and other objects that should feel physical. */
    public static final TimeInterpolator SPRING = new SpringInterpolator(new Spring(0.62, 15.0));

    private static final float SLIDE_DP = 24f, RISE_DP = 14f;

    public static int mode() { return mode; }

    public static void setMode(int m) { mode = m; }

    /** "full" | "reduced" | "off" from settings; anything else means full. */
    public static int parse(String pref) {
        if ("off".equals(pref)) return OFF;
        if ("reduced".equals(pref)) return REDUCED;
        return FULL;
    }

    /** Duration of a movement (translation, scale, rotation). */
    public static long move(long base) { return mode == FULL ? base : 0; }

    /** Duration of a pure fade: capped in REDUCED mode, zero in OFF. */
    public static long fade(long base) {
        if (mode == OFF) return 0;
        return mode == REDUCED ? Math.min(base, REDUCED_FADE_MAX) : base;
    }

    private static TimeInterpolator curve(final Bezier b) {
        return new TimeInterpolator() { @Override public float getInterpolation(float t) { return (float) b.at(t); } };
    }

    /** Spring as a {@link TimeInterpolator}, usable with any ValueAnimator or ViewPropertyAnimator. */
    static final class SpringInterpolator implements TimeInterpolator {
        private final Spring spring;

        SpringInterpolator(Spring spring) { this.spring = spring; }

        @Override public float getInterpolation(float t) { return (float) spring.progress(t); }
    }

    // ----------------------------------------------------------------------------------------------- overlays

    /** Brings an overlay panel in. Views must be measured (sheets slide in by their own height). */
    public static void enter(final View v, Kind k) {
        float h = v.getHeight() > 0 ? v.getHeight() : dp(v, 120);
        if (mode == OFF) { reset(v); return; }
        switch (k) {
            case SHEET:
                v.setTranslationY(h);
                v.animate().translationY(0).setDuration(move(D_SLOW)).setInterpolator(DECELERATE).start();
                break;
            case DIALOG:
            case POPUP:
                v.setAlpha(0f);
                v.setScaleX(mode == FULL ? 0.92f : 1f);
                v.setScaleY(mode == FULL ? 0.92f : 1f);
                v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(fade(D_BASE)).setInterpolator(DECELERATE).start();
                break;
            case TOAST:
                v.setAlpha(0f);
                v.setTranslationY(dp(v, RISE_DP) * (mode == FULL ? 1 : 0));
                v.animate().alpha(1f).translationY(0).setDuration(fade(D_BASE)).setInterpolator(DECELERATE).start();
                break;
            default:
                v.setAlpha(0f);
                v.animate().alpha(1f).setDuration(fade(D_BASE)).start();
                break;
        }
    }

    /** Takes an overlay panel out and runs {@code end} when it is gone (also when motion is off). */
    public static void exit(final View v, Kind k, final Runnable end) {
        float h = v.getHeight() > 0 ? v.getHeight() : dp(v, 120);
        if (mode == OFF) { if (end != null) end.run(); return; }
        switch (k) {
            case SHEET:
                v.animate().translationY(h).setDuration(move(D_BASE)).setInterpolator(ACCELERATE).withEndAction(runOnce(end)).start();
                break;
            case DIALOG:
            case POPUP:
                v.animate().alpha(0f).scaleX(mode == FULL ? 0.96f : 1f).scaleY(mode == FULL ? 0.96f : 1f).setDuration(fade(D_FAST))
                    .setInterpolator(ACCELERATE).withEndAction(runOnce(end)).start();
                break;
            case TOAST:
                v.animate().alpha(0f).translationY(dp(v, 10) * (mode == FULL ? 1 : 0)).setDuration(fade(D_FAST)).setInterpolator(ACCELERATE)
                    .withEndAction(runOnce(end)).start();
                break;
            default:
                v.animate().alpha(0f).setDuration(fade(D_FAST)).withEndAction(runOnce(end)).start();
                break;
        }
    }

    // ----------------------------------------------------------------------------------------------- screens

    /**
     * Screen change in the dock order: shared-axis slide plus fade (dir = +1 towards the right neighbour, -1 towards the left).
     * dir 0 (a screen outside the dock, e.g. settings): fade-through with a small rise.
     */
    public static void screenEnter(View v, int dir) {
        if (mode == OFF) { reset(v); return; }
        if (dir == 0) {
            v.setAlpha(0f);
            v.setTranslationY(dp(v, RISE_DP) * (mode == FULL ? 1 : 0));
            v.animate().alpha(1f).translationY(0).setDuration(fade(D_BASE)).setInterpolator(DECELERATE).start();
            return;
        }
        v.setAlpha(0f);
        v.setTranslationX(dp(v, SLIDE_DP) * dir * (mode == FULL ? 1 : 0));
        v.animate().alpha(1f).translationX(0).setDuration(fade(D_SLOW)).setInterpolator(DECELERATE).start();
    }

    public static void screenExit(final View v, int dir, final Runnable end) {
        if (mode == OFF) { if (end != null) end.run(); return; }
        float dx = dir == 0 ? 0 : -dp(v, SLIDE_DP) * dir * (mode == FULL ? 1 : 0);
        v.animate().alpha(0f).translationX(dx).setDuration(fade(D_BASE)).setInterpolator(ACCELERATE).withEndAction(runOnce(end)).start();
    }

    // ----------------------------------------------------------------------------------------------- controls

    /** Animates a border (or any stroke) colour; a no-op in OFF mode. */
    public static void stroke(final GradientDrawable g, final int width, int from, final int to, long baseMs) {
        if (mode == OFF || baseMs <= 0) { g.setStroke(width, to); return; }
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(fade(baseMs));
        a.setInterpolator(STANDARD);
        final int start = from;
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator an) {
                g.setStroke(width, blend(start, to, (Float) an.getAnimatedValue()));
            }
        });
        a.start();
    }

    /** Haptic tick for toggles and presses (the view decides whether haptics are available). */
    public static void tick(View v) { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); }

    static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24) & 0xFF, ra = (a >> 16) & 0xFF, ga = (a >> 8) & 0xFF, ba = a & 0xFF;
        int ab = (b >>> 24) & 0xFF, rb = (b >> 16) & 0xFF, gb = (b >> 8) & 0xFF, bb = b & 0xFF;
        return Math.round(aa + (ab - aa) * t) << 24 | Math.round(ra + (rb - ra) * t) << 16
            | Math.round(ga + (gb - ga) * t) << 8 | Math.round(ba + (bb - ba) * t);
    }

    // ----------------------------------------------------------------------------------------------- helpers

    private static void reset(View v) {
        v.setAlpha(1f);
        v.setTranslationX(0);
        v.setTranslationY(0);
        v.setScaleX(1f);
        v.setScaleY(1f);
    }

    private static Runnable runOnce(final Runnable r) {
        return new Runnable() { @Override public void run() { if (r != null) r.run(); } };
    }

    private static float dp(View v, float dp) { return dp * v.getResources().getDisplayMetrics().density; }
}
