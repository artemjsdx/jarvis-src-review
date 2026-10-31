package app.jarvis.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.ui.motion.Motion;

/**
 * The shared widget kit. Every screen builds from these so the whole app looks like one product: glass cards with a
 * visible border, lavender accents, compact type, custom controls (no system switches/seekbars/dialogs).
 */
public final class Widgets {
    private Widgets() { }

    // ------------------------------------------------------------------------------------------------------ Card / Section
    /** Glass container (vertical LinearLayout); see Ui.card for the gap between cards. */
    public static LinearLayout card(Context c) { return Ui.card(c); }

    /** Small label above a group, e.g. "Подключение". */
    public static TextView sectionTitle(Context c, String t) {
        TextView v = Ui.section(c, t);
        v.setTag("jarvis.section.help");
        return v;
    }

    /** Adds a divider between consecutive rows of a card automatically: call card.addView(row) then Widgets.divide(card). */
    public static void divide(LinearLayout card) {
        View d = Ui.divider(card.getContext());
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) d.getLayoutParams();
        lp.leftMargin = Ui.dp(16);
        card.addView(d, lp);
    }

    // ------------------------------------------------------------------------------------------------------ Row
    /** Settings-style row: optional icon, title, optional subtitle, optional trailing view (or chevron when clickable and trailing==null). */
    public static LinearLayout row(Context c, int iconRes, CharSequence title, CharSequence subtitle, View trailing, final Runnable onClick) {
        LinearLayout r = Ui.row(c);
        r.setPadding(Ui.dp(16), Ui.dp(13), Ui.dp(14), Ui.dp(13));
        if (iconRes != 0) {
            FrameLayout badge = new FrameLayout(c);
            badge.setBackground(Ui.rect(Ui.P.accentSoft, 10));
            badge.addView(Ui.icon(c, iconRes, 18, Ui.P.accent), new FrameLayout.LayoutParams(Ui.dp(18), Ui.dp(18), Gravity.CENTER));
            r.addView(badge, Ui.lpm(Ui.dp(34), Ui.dp(34), 0, 0, 12, 0));
        }
        LinearLayout tx = Ui.column(c);
        TextView t = Ui.text(c, title, Ui.BODY);
        t.setTypeface(Ui.medium);
        tx.addView(t);
        if (subtitle != null && subtitle.length() > 0) {
            TextView s = Ui.text(c, subtitle, Ui.CAPTION, Ui.P.text2);
            s.setTag("subtitle");
            s.setMaxLines(3);
            s.setEllipsize(TextUtils.TruncateAt.END);
            tx.addView(s, Ui.lpm(-2, -2, 0, 3, 0, 0));
        }
        r.addView(tx, Ui.lpw(1f));
        // a bare View as trailing (a "no icon here" placeholder) would measure as match-parent in a row
        if (trailing != null && trailing.getClass() == View.class) trailing = Ui.space(c, 18, 18);
        if (trailing != null) r.addView(trailing, Ui.lpm(-2, -2, 10, 0, 0, 0));
        else if (onClick != null) r.addView(Ui.icon(c, R.drawable.ic_chevron_right, 18, Ui.P.text3), Ui.lpm(-2, -2, 8, 0, 0, 0));
        if (onClick != null) {
            r.setBackground(Ui.rippleOn(new ColorDrawable(0), Ui.S.RADIUS_CARD));
            r.setClickable(true);
            r.setFocusable(true);
            r.setContentDescription(title.toString() + (subtitle == null || subtitle.length() == 0 ? "" : ". " + subtitle));
            r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onClick.run(); } });
        }
        return r;
    }

    public static void setSubtitle(View row, CharSequence s) {
        TextView v = (TextView) row.findViewWithTag("subtitle");
        if (v != null) v.setText(s);
    }

    // ------------------------------------------------------------------------------------------------------ Pill / badge
    public static final int INFO = 0, OK = 1, WARN = 2, ERR = 3, MUTED = 4;

    public static TextView pill(Context c, CharSequence text, int kind) {
        int col = kind == OK ? Ui.P.ok : kind == WARN ? Ui.P.warn : kind == ERR ? Ui.P.err : kind == MUTED ? Ui.P.text3 : Ui.P.accent;
        TextView v = Ui.chip(c, text, col);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        return v;
    }

    // ------------------------------------------------------------------------------------------------------ Toggle
    public static final class Toggle extends View {
        private boolean on;
        private float t;       // 0..1 knob position
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private Listener listener;
        public interface Listener { void onChanged(boolean on); }

        public Toggle(Context c, boolean on, Listener l) {
            super(c);
            this.on = on;
            this.t = on ? 1f : 0f;
            this.listener = l;
            setClickable(true);
            setFocusable(true);
            setLayoutParams(new ViewGroup.LayoutParams(Ui.dp(46), Ui.dp(28)));
            setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { Motion.tick(v); setChecked(!Toggle.this.on, true); } });
        }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(Ui.dp(46), Ui.dp(28)); }

        public boolean isChecked() { return on; }

        /** Thumb slides on a spring while the track colour blends; with motion off the switch snaps. */
        public void setChecked(boolean v, boolean notify) {
            if (v == on) return;
            on = v;
            long d = Motion.move(Motion.D_BASE);
            if (d == 0) { t = on ? 1f : 0f; invalidate(); }
            else {
                ValueAnimator a = ValueAnimator.ofFloat(t, on ? 1f : 0f);
                a.setDuration(d);
                a.setInterpolator(Motion.SPRING);
                a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { @Override public void onAnimationUpdate(ValueAnimator an) { t = (Float) an.getAnimatedValue(); invalidate(); } });
                a.start();
            }
            if (notify && listener != null) listener.onChanged(on);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            r.set(1, 1, w - 1, h - 1);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.mix(Ui.P.dark ? 0x33FFFFFF : 0x331B1740, Ui.P.accent, t));
            c.drawRoundRect(r, h / 2f, h / 2f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, Ui.dpf(0.9f)));
            p.setColor(Ui.mix(Ui.P.strokeHi, 0x00000000, t));
            c.drawRoundRect(r, h / 2f, h / 2f, p);
            float kr = h / 2f - Ui.dpf(4);
            float cx = h / 2f + (w - h) * t;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.mix(Ui.P.dark ? 0xFFE9E6F7 : 0xFFFFFFFF, Ui.P.onAccent == 0 ? 0xFFFFFFFF : (Ui.P.dark ? 0xFF140F2E : 0xFFFFFFFF), t * 0.0f));
            c.drawCircle(cx, h / 2f, kr, p);
        }
    }

    public static Toggle toggle(Context c, boolean on, Toggle.Listener l) { return new Toggle(c, on, l); }

    // ------------------------------------------------------------------------------------------------------ Segmented
    public interface IntListener { void on(int index); }

    /** Pill-shaped choice bar. */
    public static LinearLayout segmented(final Context c, final String[] labels, int selected, final IntListener l) {
        final LinearLayout bar = Ui.row(c);
        bar.setPadding(Ui.dp(3), Ui.dp(3), Ui.dp(3), Ui.dp(3));
        bar.setBackground(Ui.glassTinted(14, Ui.P.field));
        final TextView[] tv = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = Ui.text(c, labels[i], Ui.CAPTION, Ui.P.text2);
            t.setGravity(Gravity.CENTER);
            // setSingleLine(true) made the label vanish inside a weighted (width 0) child on ART; maxLines keeps one line
            t.setMaxLines(1);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(10), Ui.dp(8));
            t.setTypeface(Ui.medium);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    for (int k = 0; k < tv.length; k++) style(tv[k], k == idx);
                    if (l != null) l.on(idx);
                }
            });
            tv[i] = t;
            style(t, i == selected);
            bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return bar;
    }

    private static void style(TextView t, boolean on) {
        t.setBackground(on ? Ui.rect(Ui.P.accent, 11) : null);
        t.setTextColor(on ? Ui.P.onAccent : Ui.P.text2);
    }

    // ------------------------------------------------------------------------------------------------------ Slider
    public interface SliderListener { void onValue(float v, boolean fromUser); }

    public static final class Slider extends View {
        private float min, max, step, value;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private SliderListener l;

        public Slider(Context c, float min, float max, float step, float value, SliderListener l) {
            super(c);
            this.min = min; this.max = max; this.step = step; this.value = snap(value); this.l = l;
            setClickable(true);
        }

        public float getValue() { return value; }
        public void setValue(float v) { value = snap(v); invalidate(); }

        private float snap(float v) {
            v = Math.max(min, Math.min(max, v));
            if (step > 0) v = Math.round((v - min) / step) * step + min;
            return Math.max(min, Math.min(max, v));
        }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(32)); }

        @Override protected void onDraw(Canvas c) {
            float pad = Ui.dpf(12), cy = getHeight() / 2f, w = getWidth() - 2 * pad;
            float f = (value - min) / (max - min);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(Ui.dpf(4));
            p.setColor(Ui.P.field);
            c.drawLine(pad, cy, pad + w, cy, p);
            p.setColor(Ui.P.accent);
            c.drawLine(pad, cy, pad + w * f, cy, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.alpha(Ui.P.accent, 0.25f));
            c.drawCircle(pad + w * f, cy, Ui.dpf(12), p);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(pad + w * f, cy, Ui.dpf(8), p);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            getParent().requestDisallowInterceptTouchEvent(true);
            float pad = Ui.dpf(12), w = getWidth() - 2 * pad;
            float f = Math.max(0f, Math.min(1f, (e.getX() - pad) / w));
            float nv = snap(min + f * (max - min));
            if (nv != value) { value = nv; invalidate(); if (l != null) l.onValue(value, true); }
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) { getParent().requestDisallowInterceptTouchEvent(false); if (l != null) l.onValue(value, true); }
            return true;
        }
    }

    public static Slider slider(Context c, float min, float max, float step, float value, SliderListener l) { return new Slider(c, min, max, step, value, l); }

    // ------------------------------------------------------------------------------------------------------ Field
    /** Glass text field. Multi-line fields scroll internally after maxLines. */
    public static EditText field(Context c, String hint, String initial, boolean multiline, boolean secret) {
        final EditText e = new EditText(c);
        e.setBackground(null);
        e.setHint(hint);
        e.setHintTextColor(Ui.P.text3);
        e.setTextColor(Ui.P.text);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setTypeface(Ui.regular);
        e.setIncludeFontPadding(false);
        e.setText(initial == null ? "" : initial);
        e.setHighlightColor(Ui.P.accentSoft);
        int type = InputType.TYPE_CLASS_TEXT;
        if (multiline) { type |= InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; e.setMaxLines(6); e.setGravity(Gravity.TOP | Gravity.START); }
        else { e.setSingleLine(true); e.setImeOptions(EditorInfo.IME_ACTION_DONE); }
        if (secret) type |= InputType.TYPE_TEXT_VARIATION_PASSWORD;
        else type |= InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS * 0;
        e.setInputType(type);
        e.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
        try {   // lavender caret/handles instead of the platform blue
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                e.setTextCursorDrawable(Ui.cursor());
            }
        } catch (Throwable ignored) { }
        return e;
    }

    /** Field wrapped in a bordered box (use this in forms). The border turns accent while the field has focus. */
    public static FrameLayout fieldBox(Context c, EditText e) {
        final FrameLayout f = new FrameLayout(c);
        final GradientDrawable border = Ui.outlined(Ui.S.RADIUS_FIELD, Ui.P.field, Ui.P.strokeLo);
        f.setBackground(app.jarvis.ui.glass.Glass.surface(Ui.S.RADIUS_FIELD,
            new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                new app.jarvis.ui.GlassDrawable(Ui.S.RADIUS_FIELD, false), border
            }), 0));
        e.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                Motion.stroke(border, Ui.hairline(), hasFocus ? Ui.P.strokeLo : Ui.P.accent, hasFocus ? Ui.P.accent : Ui.P.strokeLo, Motion.D_FAST);
            }
        });
        f.addView(e, new FrameLayout.LayoutParams(-1, -2));
        return f;
    }

    // ------------------------------------------------------------------------------------------------------ Spinner (indeterminate)
    public static final class Spinner extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float angle;
        private final int color;

        public Spinner(Context c, float sizeDp, int color) {
            super(c);
            this.color = color;
            setLayoutParams(new ViewGroup.LayoutParams(Ui.dp(sizeDp), Ui.dp(sizeDp)));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()), sw = Math.max(Ui.dpf(2), s / 9f);
            p.setStrokeWidth(sw);
            r.set(sw, sw, s - sw, s - sw);
            p.setColor(Ui.alpha(color, 0.2f));
            c.drawArc(r, 0, 360, false, p);
            p.setColor(color);
            c.drawArc(r, angle, 100, false, p);
            if (isShown()) { angle = (angle + (Ui.reduceMotion ? 3 : 9)) % 360; postInvalidateOnAnimation(); }
        }
    }

    public static Spinner spinner(Context c, float sizeDp) { return new Spinner(c, sizeDp, Ui.P.accent); }

    // ------------------------------------------------------------------------------------------------------ Empty state
    public static LinearLayout empty(Context c, int icon, String title, String message) {
        LinearLayout l = Ui.column(c);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(Ui.dp(28), Ui.dp(36), Ui.dp(28), Ui.dp(24));
        FrameLayout b = new FrameLayout(c);
        b.setBackground(Ui.glass(22));
        b.addView(Ui.icon(c, icon, 26, Ui.P.accent), new FrameLayout.LayoutParams(Ui.dp(26), Ui.dp(26), Gravity.CENTER));
        l.addView(b, Ui.lp(Ui.dp(56), Ui.dp(56)));
        TextView t = Ui.text(c, title, Ui.BODY);
        t.setTypeface(Ui.semibold);
        t.setGravity(Gravity.CENTER);
        l.addView(t, Ui.lpm(-2, -2, 0, 14, 0, 0));
        TextView m = Ui.text(c, message, Ui.CAPTION, Ui.P.text2);
        m.setGravity(Gravity.CENTER);
        l.addView(m, Ui.lpm(-2, -2, 0, 6, 0, 0));
        return l;
    }

    // ------------------------------------------------------------------------------------------------------ Thin progress
    public static final class Progress extends View {
        private float f;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public Progress(Context c) { super(c); }
        public void set(float v) { f = Math.max(0f, Math.min(1f, v)); invalidate(); }
        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(4)); }
        @Override protected void onDraw(Canvas c) {
            float h = getHeight();
            p.setColor(Ui.P.field);
            c.drawRoundRect(0, 0, getWidth(), h, h / 2, h / 2, p);
            p.setColor(Ui.P.accent);
            c.drawRoundRect(0, 0, getWidth() * f, h, h / 2, h / 2, p);
        }
    }

    // ------------------------------------------------------------------------------------------------------ Page header (sub-pages)
    public static LinearLayout pageHeader(Context c, String title, final Runnable onBack) {
        LinearLayout h = Ui.row(c);
        h.setPadding(Ui.dp(8), Ui.dp(4), Ui.dp(8), Ui.dp(4));
        h.addView(Ui.iconButton(c, R.drawable.ic_chevron_left, 44, Ui.P.text, onBack));
        TextView t = Ui.text(c, title, Ui.TITLE);
        h.addView(t, Ui.lpm(-2, -2, 4, 0, 0, 0));
        return h;
    }
}
