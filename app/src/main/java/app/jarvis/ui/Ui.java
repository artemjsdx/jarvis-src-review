package app.jarvis.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.core.design.PaletteSpec;
import app.jarvis.ui.motion.Motion;
import app.jarvis.ui.glass.Glass;

/**
 * Design tokens and view factories. Black/white base, lavender accents, translucent "glass" surfaces with distinct
 * borders. Everything is drawn by us: no AndroidX, no XML layouts, no system dialogs, no blue ripples.
 */
public final class Ui {
    private Ui() { }

    public static float D = 3f;
    public static Typeface regular = Typeface.DEFAULT, medium = Typeface.DEFAULT, semibold = Typeface.DEFAULT_BOLD, mono = Typeface.MONOSPACE;
    public static boolean reduceMotion;
    public static Pal P = Pal.dark();

    public static final int TITLE = 0, BODY = 1, CAPTION = 2, MONO = 3;

    /** Palette in runtime form; the values come from app.jarvis.core.design.PaletteSpec (contrast-tested there). */
    public static final class Pal {
        public boolean dark;
        public int bg0, bg1, glassTop, glassBottom, strokeHi, strokeLo, text, text2, text3, accent, accent2, accentSoft, onAccent,
            ok, warn, err, scrim, field, bubbleUser, divider, ripple, blobA, blobB;

        static Pal of(PaletteSpec.Colors c) {
            Pal p = new Pal();
            p.dark = c.dark;
            p.bg0 = c.bg0; p.bg1 = c.bg1; p.glassTop = c.glassTop; p.glassBottom = c.glassBottom;
            p.strokeHi = c.strokeHi; p.strokeLo = c.strokeLo; p.text = c.text; p.text2 = c.text2; p.text3 = c.text3;
            p.accent = c.accent; p.accent2 = c.accent2; p.accentSoft = c.accentSoft; p.onAccent = c.onAccent;
            p.ok = c.ok; p.warn = c.warn; p.err = c.err; p.scrim = c.scrim; p.field = c.field; p.bubbleUser = c.bubbleUser;
            p.divider = c.divider; p.ripple = c.ripple; p.blobA = c.blobA; p.blobB = c.blobB;
            return p;
        }

        public static Pal dark() { return of(PaletteSpec.dark()); }

        public static Pal light() { return of(PaletteSpec.light()); }
    }

    public static void init(Context c, boolean dark) {
        D = c.getResources().getDisplayMetrics().density;
        P = dark ? Pal.dark() : Pal.light();
        regular = Typeface.create("sans-serif", Typeface.NORMAL);
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        semibold = Typeface.create("sans-serif-medium", Typeface.BOLD);
        mono = Typeface.create("monospace", Typeface.NORMAL);
    }

    /** Swaps the palette; views built earlier keep their colours until rebuilt (Shell.applyTheme does that). */
    public static void setDark(boolean dark) { P = dark ? Pal.dark() : Pal.light(); }

    /** pref: "full" | "reduced" | "off". Full motion becomes off when Android has animations switched off system-wide. */
    public static void refreshMotion(Context c, String pref) {
        int m = Motion.parse(pref);
        if (m == Motion.FULL && systemAnimationsOff(c)) m = Motion.OFF;
        Motion.setMode(m);
        reduceMotion = m != Motion.FULL;
    }

    private static boolean systemAnimationsOff(Context c) {
        try { return Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f; }
        catch (Throwable ignored) { return false; }
    }

    public static int dp(float v) { return Math.round(v * D); }
    public static float dpf(float v) { return v * D; }
    public static long ms(long base) { return Motion.move(base); }

    /** Spacing scale in dp, all on a 4 dp grid. Screens should use these instead of literals. */
    public static final class S {
        private S() { }
        public static final int XS = 4, SM = 8, MD = 12, LG = 16, XL = 24;
        /** Space between cards: each card is drawn inset by half of it on both sides, so the layout itself stays unchanged. */
        public static final int GAP = MD;
        public static final int RADIUS_CARD = 18, RADIUS_FIELD = 14, RADIUS_BUTTON = 14, RADIUS_CHIP = 20;
    }

    /** Width of the standard hairline border (0.75 dp, at least one pixel). */
    public static int hairline() { return Math.max(1, dp(0.75f)); }

    public static int alpha(int c, float a) { return (c & 0x00FFFFFF) | (Math.max(0, Math.min(255, Math.round(a * 255))) << 24); }

    /** Blends two colours; t is clamped, because spring curves overshoot past 1 and extrapolated channels wrap around. */
    public static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return Color.argb(Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t), Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
            Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t), Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    // ------------------------------------------------------------------------------------------ text
    public static TextView text(Context c, CharSequence t, int kind, int color) {
        TextView v = new TextView(c);
        v.setText(t);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        switch (kind) {
            case TITLE: v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20); v.setTypeface(semibold); break;
            case CAPTION: v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12); v.setTypeface(regular); break;
            case MONO: v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); v.setTypeface(mono); break;
            default: v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15); v.setTypeface(regular); break;
        }
        v.setLineSpacing(0, kind == BODY ? 1.18f : 1.1f);
        v.setHighlightColor(P.accentSoft);
        return v;
    }

    public static TextView text(Context c, CharSequence t, int kind) { return text(c, t, kind, kind == CAPTION ? P.text2 : P.text); }

    // ----------------------------------------------------------------------------------------- icons
    public static ImageView icon(Context c, int res, float sizeDp, int color) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setColorFilter(color);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setLayoutParams(new ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    // --------------------------------------------------------------------------------------- layouts
    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    /**
     * Row aligned to the top edge. A centred row shifts a child by (topMargin - bottomMargin) / 2 when the margins are
     * asymmetric, which made labels drift and clip in the voice and settings cards; use this when the margins are not symmetric.
     */
    public static LinearLayout rowTop(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.TOP);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    public static LinearLayout.LayoutParams lpw(float weight) { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight); }

    /** Weighted child (width 0 + weight) with margins in dp. Use this instead of lpm(0, ...): a zero width without weight is invisible. */
    public static LinearLayout.LayoutParams lpwm(float weight, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    public static LinearLayout.LayoutParams lpm(int w, int h, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    /**
     * Empty spacer. It is an android.widget.Space on purpose: a bare View with WRAP_CONTENT measures like MATCH_PARENT
     * (View.getDefaultSize returns the whole available size for AT_MOST), so "space + lpw(1f)" inside a row used to make
     * the row as tall as its parent (the Files toolbar swallowed the whole screen and left the list 0 px high).
     */
    public static View space(Context c, float w, float h) {
        View v = new android.widget.Space(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(w), dp(h)));
        return v;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(P.divider);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.75f))));
        return v;
    }

    // -------------------------------------------------------------------------------------- surfaces
    public static GradientDrawable rect(int color, float rDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dpf(rDp));
        return g;
    }

    /**
     * Text caret: a 2dp bar in the accent colour. A GradientDrawable without a size reports intrinsic width -1, which the text editor
     * treats as "nothing to draw": the caret was invisible (and therefore never seemed to blink) in every field that used rect(..., 1).
     */
    public static GradientDrawable cursor() {
        GradientDrawable g = rect(P.accent, 1);
        g.setSize(Math.max(2, dp(2)), -1);
        return g;
    }

    public static Drawable rippleOn(Drawable content, float rDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dpf(rDp));
        return new RippleDrawable(ColorStateList.valueOf(P.ripple), content, mask);
    }

    /** Glass card/background with a distinct 1dp gradient border. */
    public static Drawable glass(float rDp) { return Glass.surface(rDp, new GlassDrawable(rDp, false), 0); }

    public static Drawable glassPressable(float rDp) { return Glass.surface(rDp, rippleOn(new GlassDrawable(rDp, false), rDp), 0); }

    /** Accent-filled button surface (lavender gradient) with a hairline edge. */
    public static Drawable accentFill(float rDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{P.accent, mix(P.accent, P.accent2, 0.55f)});
        g.setCornerRadius(dpf(rDp));
        g.setStroke(hairline(), alpha(P.accent2, 0.6f));
        return rippleOn(g, rDp);
    }

    /** Flat rounded box with a visible hairline border: fields, chips, input rows. */
    public static GradientDrawable outlined(float rDp, int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dpf(rDp));
        g.setStroke(hairline(), stroke);
        return g;
    }

    /** Card surface: glass inset by half the gap, so neighbouring cards show a S.GAP space between them. */
    public static Drawable cardSurface(float rDp) {
        return Glass.surface(rDp, new GlassDrawable(rDp, false), dp(S.GAP / 2f));
    }

    /**
     * Page scroller with the system scrollbar OFF: the default thumb is tinted with the accent and reads as a hard-edged
     * violet stripe over the cards at the right edge. The app draws no scrollbar anywhere else either.
     */
    public static ScrollView scroll(Context c) {
        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.setHorizontalScrollBarEnabled(false);
        sv.setScrollbarFadingEnabled(true);
        return sv;
    }

    /** Clips everything a view draws (children, ripples) to its rounded outline: a row's press highlight cannot spill past the card edge. */
    public static void clipToRounded(View v, float rDp) {
        v.setClipToOutline(true);
        final float r = dpf(rDp);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r); }
        });
    }

    /** Vertical container for a group of rows. Give it children with padding, and let divide() add hairlines between rows. */
    public static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setBackground(cardSurface(S.RADIUS_CARD));
        clipToRounded(l, S.RADIUS_CARD);
        return l;
    }

    /** Section caption above a group ("Подключение"): small caps, grid-aligned padding. */
    public static TextView section(Context c, CharSequence title) {
        TextView v = text(c, title.toString().toUpperCase(java.util.Locale.ROOT), CAPTION, P.text3);
        v.setTypeface(medium);
        v.setLetterSpacing(0.08f);
        v.setPadding(dp(S.SM - 2), dp(S.LG + S.XS), 0, dp(S.SM));
        return v;
    }

    /** Press feedback for any clickable view: scale to 0.97 while pressed, spring back on release. Click handling is unaffected. */
    @SuppressWarnings("ClickableViewAccessibility")
    public static void pressable(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(Motion.fade(Motion.D_FAST)).setInterpolator(Motion.STANDARD).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f).setDuration(Motion.move(Motion.D_BASE)).setInterpolator(Motion.SPRING).start();
                        break;
                    default: break;
                }
                return false;
            }
        });
    }

    public static void pressScale(View v) { pressable(v); }

    // ---------------------------------------------------------------------------------------- buttons
    public static final int BTN_PRIMARY = 0, BTN_GLASS = 1, BTN_GHOST = 2, BTN_DANGER = 3;

    public static LinearLayout button(Context c, CharSequence label, int iconRes, int style, final Runnable onClick) {
        LinearLayout b = row(c);
        b.setGravity(Gravity.CENTER);
        int fg = style == BTN_PRIMARY ? P.onAccent : style == BTN_DANGER ? P.err : P.text;
        b.setPadding(dp(label == null || label.length() == 0 ? 11 : 16), dp(10), dp(label == null || label.length() == 0 ? 11 : 16), dp(10));
        if (iconRes != 0) {
            ImageView ic = icon(c, iconRes, 18, style == BTN_GHOST ? P.text2 : fg);
            b.addView(ic);
        }
        if (label != null && label.length() > 0) {
            TextView t = text(c, label, BODY, fg);
            t.setTypeface(medium);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (iconRes != 0) lp.leftMargin = dp(8);
            b.addView(t, lp);
        }
        b.setBackground(style == BTN_PRIMARY ? accentFill(14) : style == BTN_GHOST ? rippleOn(new ColorDrawable(0), 14) : glassPressable(14));
        if (style == BTN_DANGER) b.setBackground(glassPressable(14));
        b.setClickable(true);
        b.setFocusable(true);
        if (onClick != null) b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onClick.run(); } });
        pressScale(b);
        return b;
    }

    public static FrameLayout iconButton(Context c, int iconRes, float sizeDp, int color, final Runnable onClick) {
        FrameLayout f = new FrameLayout(c);
        ImageView ic = icon(c, iconRes, sizeDp * 0.5f, color);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(sizeDp * 0.5f), dp(sizeDp * 0.5f), Gravity.CENTER);
        f.addView(ic, lp);
        f.setBackground(rippleOn(new ColorDrawable(0), sizeDp / 2f));
        f.setClickable(true);
        f.setFocusable(true);
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        if (onClick != null) f.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onClick.run(); } });
        pressScale(f);
        return f;
    }

    /** Small rounded tag / chip. */
    public static TextView chip(Context c, CharSequence t, int color) {
        TextView v = text(c, t, CAPTION, color);
        v.setTypeface(medium);
        v.setPadding(dp(10), dp(5), dp(10), dp(5));
        v.setBackground(outlined(S.RADIUS_CHIP, alpha(color, 0.14f), alpha(color, 0.35f)));
        v.setSingleLine(true);
        return v;
    }

    public static void fadeIn(View v, long ms) {
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(Motion.fade(ms)).start();
    }

    public static ValueAnimator loop(long ms, ValueAnimator.AnimatorUpdateListener l) {
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(Math.max(1, ms));
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setInterpolator(new android.view.animation.LinearInterpolator());
        a.addUpdateListener(l);
        return a;
    }
}
