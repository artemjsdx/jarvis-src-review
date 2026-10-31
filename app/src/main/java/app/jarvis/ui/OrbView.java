package app.jarvis.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.View;

/**
 * Animated Jarvis mark: an open ring (the "listening aperture"), a thin counter-ring and a glowing core. The
 * assistant state changes its motion; {@link #setLevel} feeds microphone/speech energy. Draws only while shown and
 * throttles to ~20 fps when idle so it costs almost nothing in the background.
 */
public final class OrbView extends View {
    public enum Mode { IDLE, LISTENING, THINKING, TOOL, SPEAKING, ERROR }

    private Mode mode = Mode.IDLE;
    private volatile float levelTarget;
    private float level, spin, spin2, t, modeBlend = 1f, lastMs;
    private long lastFrame;
    private Mode prev = Mode.IDLE;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), ring2 = new Paint(Paint.ANTI_ALIAS_FLAG), core = new Paint(Paint.ANTI_ALIAS_FLAG),
        halo = new Paint(Paint.ANTI_ALIAS_FLAG), wave = new Paint(Paint.ANTI_ALIAS_FLAG), tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float cx, cy, R;
    private Bitmap skin;
    private BitmapShader skinShader;
    private final Matrix skinMatrix = new Matrix();
    private final Paint skinPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private float skinAngle;

    public OrbView(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        ring2.setStyle(Paint.Style.STROKE);
        ring2.setStrokeCap(Paint.Cap.ROUND);
        wave.setStyle(Paint.Style.STROKE);
        tick.setStyle(Paint.Style.STROKE);
        tick.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setMode(Mode m) {
        if (m == mode) return;
        prev = mode;
        mode = m;
        modeBlend = 0f;
        invalidate();
    }

    public Mode getMode() { return mode; }

    /** Optional picture for the orb body: square, circular alpha, drawn over the halo and under the rings; null restores the procedural core. */
    public void setSkin(Bitmap b) {
        skin = b;
        skinShader = b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        skinPaint.setShader(skinShader);
        invalidate();
    }

    /** 0..1 energy; safe to call from any thread. */
    public void setLevel(float v) { levelTarget = Math.max(0f, Math.min(1f, v)); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        cx = w / 2f;
        cy = h / 2f;
        R = Math.min(w, h) / 2f * 0.58f;   // leave room for halo and ripples inside the bounds (no clipping)
        Ui.Pal p = Ui.P;
        halo.setShader(new RadialGradient(cx, cy, R * 1.72f, new int[]{Ui.alpha(p.accent2, 0.46f), Ui.alpha(p.accent2, 0.20f), Ui.alpha(p.accent, 0.06f), 0x00000000}, new float[]{0.2f, 0.5f, 0.82f, 1f}, Shader.TileMode.CLAMP));
        ring.setStrokeWidth(R * 0.075f);
        ring.setShader(new SweepGradient(cx, cy, new int[]{Ui.alpha(p.accent, 0.15f), p.accent, 0xFFFFFFFF, p.accent2, Ui.alpha(p.accent, 0.15f)}, new float[]{0f, 0.25f, 0.5f, 0.78f, 1f}));
        ring2.setStrokeWidth(R * 0.022f);
        ring2.setShader(new SweepGradient(cx, cy, new int[]{0x00000000, Ui.alpha(p.accent, 0.8f), 0x00000000}, new float[]{0f, 0.5f, 1f}));
        wave.setStrokeWidth(R * 0.02f);
        tick.setStrokeWidth(R * 0.07f);
        tick.setColor(p.accent);
        core.setShader(new RadialGradient(cx - R * 0.10f, cy - R * 0.12f, R * 0.42f, new int[]{0xFFFFFFFF, 0xFFE3DBFF, p.accent, p.accent2}, new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        t += dt;
        if (modeBlend < 1f) modeBlend = Math.min(1f, modeBlend + dt * 3.2f);
        level += (levelTarget - level) * Math.min(1f, dt * 14f);
        float spinSpeed, spin2Speed;
        switch (mode) {
            case LISTENING: spinSpeed = 18f; spin2Speed = -10f; break;
            case THINKING: spinSpeed = 170f; spin2Speed = -95f; break;
            case TOOL: spinSpeed = 260f; spin2Speed = -140f; break;
            case SPEAKING: spinSpeed = 40f; spin2Speed = -22f; break;
            case ERROR: spinSpeed = 0f; spin2Speed = 0f; break;
            default: spinSpeed = 7f; spin2Speed = -4f; break;
        }
        float amp = Ui.reduceMotion ? 0.15f : 1f;
        spinSpeed *= amp;
        spin2Speed *= amp;
        spin = (spin + spinSpeed * dt) % 360f;
        spin2 = (spin2 + spin2Speed * dt) % 360f;
        float skinSpin = mode == Mode.THINKING ? 10f : mode == Mode.TOOL ? 16f : 0f;
        skinAngle = (skinAngle + skinSpin * amp * dt) % 360f;

        float breathe = (float) (0.5 + 0.5 * Math.sin(t * (mode == Mode.IDLE ? 1.1 : 2.4)));
        float energy = mode == Mode.SPEAKING ? Math.max(level, 0.25f + 0.2f * (float) Math.sin(t * 7.0)) : level;
        boolean err = mode == Mode.ERROR;
        int haloAlpha = Math.round(255 * (0.55f + 0.35f * breathe + 0.4f * energy));
        halo.setAlpha(Math.min(255, haloAlpha));
        float hs = 0.94f + 0.05f * breathe + 0.04f * energy;     // stays inside the view: R*1.72*hs <= half-size
        c.save();
        c.scale(hs, hs, cx, cy);
        c.drawCircle(cx, cy, R * 1.72f, halo);
        c.restore();
        if (skin != null) drawSkin(c, breathe, energy, amp);

        // ripples (listening reacts to mic level, speaking radiates)
        if (mode == Mode.LISTENING || mode == Mode.SPEAKING) {
            for (int i = 0; i < 3; i++) {
                float ph = (t * (mode == Mode.SPEAKING ? 0.7f : 0.45f) + i / 3f) % 1f;
                float rr = R * (0.55f + ph * (0.55f + 0.35f * energy));
                wave.setColor(Ui.alpha(Ui.P.accent, (1f - ph) * (0.10f + 0.5f * energy)));
                c.drawCircle(cx, cy, rr, wave);
            }
        }

        // counter ring
        c.save();
        c.rotate(spin2, cx, cy);
        float r2 = R * 0.80f;
        rect.set(cx - r2, cy - r2, cx + r2, cy + r2);
        ring2.setAlpha(Math.round(255 * (mode == Mode.IDLE ? 0.55f : 0.9f)));
        c.drawArc(rect, 0, 360, false, ring2);
        c.restore();

        // main open ring: gap size grows when listening (aperture), closes when thinking
        float gap = mode == Mode.LISTENING ? 70f + 40f * energy : mode == Mode.THINKING || mode == Mode.TOOL ? 38f : 56f;
        float pulse = mode == Mode.LISTENING ? 1f + 0.10f * energy : 1f + 0.015f * breathe;
        c.save();
        c.rotate(spin - 90f + gap / 2f, cx, cy);
        float rr = R * pulse;
        rect.set(cx - rr, cy - rr, cx + rr, cy + rr);
        ring.setAlpha(err ? 120 : 255);
        c.drawArc(rect, 0, 360f - gap, false, ring);
        c.restore();

        // core (the skin replaces it when one is set)
        if (skin == null) {
            float cs = R * (0.40f + 0.025f * breathe + 0.12f * energy + (mode == Mode.THINKING ? 0.03f * (float) Math.sin(t * 9) : 0f));
            c.save();
            c.translate(cx, cy);
            c.scale(cs / (R * 0.42f), cs / (R * 0.42f));
            c.translate(-cx, -cy);
            if (err) core.setColorFilter(new android.graphics.PorterDuffColorFilter(Ui.alpha(Ui.P.err, 0.55f), android.graphics.PorterDuff.Mode.SRC_ATOP));
            else core.setColorFilter(null);
            c.drawCircle(cx, cy, R * 0.42f, core);
            c.restore();
        }

        // schedule next frame only while visible; idle is throttled
        if (isShown() && getWindowVisibility() == View.VISIBLE) {
            if (mode == Mode.IDLE && !Ui.reduceMotion) postInvalidateDelayed(50);
            else if (mode == Mode.IDLE) postInvalidateDelayed(250);
            else postInvalidateOnAnimation();
        }
    }

    /** One matrix per frame: breathing and energy scale the picture, thinking and tool work turn it slowly. */
    private void drawSkin(Canvas c, float breathe, float energy, float amp) {
        float k = 1f + amp * (0.012f * breathe + 0.02f * energy);
        float s = 2f * R / skin.getWidth() * k;
        skinMatrix.setScale(s, s);
        skinMatrix.postTranslate(cx - skin.getWidth() * s / 2f, cy - skin.getHeight() * s / 2f);
        skinMatrix.postRotate(skinAngle, cx, cy);
        skinShader.setLocalMatrix(skinMatrix);
        c.drawCircle(cx, cy, R * k, skinPaint);
    }

    @Override protected void onVisibilityChanged(View v, int vis) { super.onVisibilityChanged(v, vis); if (vis == VISIBLE) { lastFrame = 0; invalidate(); } }
    @Override protected void onWindowVisibilityChanged(int vis) { super.onWindowVisibilityChanged(vis); if (vis == VISIBLE) { lastFrame = 0; invalidate(); } }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); lastFrame = 0; invalidate(); }
}
