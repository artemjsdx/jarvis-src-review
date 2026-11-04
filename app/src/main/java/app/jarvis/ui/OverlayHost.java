package app.jarvis.ui;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.ui.motion.Motion;
import app.jarvis.ui.glass.Glass;
import app.jarvis.ui.glass.GlassHost;
import app.jarvis.ui.glass.GlassLevel;
import java.util.ArrayList;

/**
 * All in-app windows live here: dialogs, bottom sheets and toasts, in our own style (glass panel, distinct border,
 * scrim, enter/exit animation). System permission prompts and the file picker are the only Android windows we use.
 */
public final class OverlayHost extends FrameLayout {
    public static final class Layer {
        View scrim, panel;
        boolean sheet, cancelable = true;
        Runnable onDismiss;
        boolean closing;
    }

    private final ArrayList<Layer> stack = new ArrayList<>();
    private final Handler h = new Handler(Looper.getMainLooper());
    private int bottomInset, topInset;
    private View toast;

    public OverlayHost(Context c) {
        super(c);
        setClipChildren(false);
    }

    public void setInsets(int top, int bottom) { topInset = top; bottomInset = bottom; }

    public boolean hasOverlay() { return !stack.isEmpty(); }

    /** @return true if the back press was consumed. */
    public boolean onBack() {
        if (stack.isEmpty()) return false;
        Layer top = stack.get(stack.size() - 1);
        if (top.cancelable) dismiss(top);
        return true;
    }

    /** Panels are translucent so the host can blur what is behind them (see Glass.mark). */
    private Drawable panelBg(float r) {
        // RenderEffect is unavailable on older devices and intentionally disabled in OFF mode.
        // Without blur the same translucency would expose legible text and the dock
        // THROUGH dialog content. Use an opaque-enough fallback in that case.
        boolean blurred = GlassLevel.blur();
        Drawable base = Ui.rect(blurred ?
            (Ui.P.dark ? 0x3A121019 : 0x60FBFAFF) :
            (Ui.P.dark ? 0xDF121019 : 0xEAFBFAFF), r);
        return new LayerDrawable(new Drawable[]{base, new GlassDrawable(r, true).overlayFrost()});
    }

    public Layer showDialog(View content, boolean cancelable, Runnable onDismiss) { return show(content, false, cancelable, onDismiss); }
    public Layer showSheet(View content, boolean cancelable, Runnable onDismiss) { return show(content, true, cancelable, onDismiss); }

    private Layer show(View content, final boolean sheet, boolean cancelable, Runnable onDismiss) {
        final Layer l = new Layer();
        l.sheet = sheet;
        l.cancelable = cancelable;
        l.onDismiss = onDismiss;
        l.scrim = new View(getContext());
        l.scrim.setBackgroundColor(Ui.P.scrim);
        l.scrim.setAlpha(0f);
        l.scrim.setContentDescription(cancelable ? "Закрыть всплывающее окно" : "Фон всплывающего окна");
        l.scrim.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { if (l.cancelable) dismiss(l); } });
        addView(l.scrim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout panel = new FrameLayout(getContext());
        panel.setClickable(true);
        // The panel blocks touch-through but is not itself an action.
        // Its child controls remain individually accessible to TalkBack.
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        panel.setBackground(panelBg(sheet ? 26 : 22));
        Glass.mark(panel, sheet ? 26 : 22);
        panel.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int maxW = Math.min(getResources().getDisplayMetrics().widthPixels - Ui.dp(32), Ui.dp(460));
        LayoutParams lp = new LayoutParams(sheet ? ViewGroup.LayoutParams.MATCH_PARENT : maxW, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = sheet ? Gravity.BOTTOM : Gravity.CENTER;
        if (!sheet) { lp.topMargin = topInset + Ui.dp(16); lp.bottomMargin = bottomInset + Ui.dp(16); }
        else panel.setPadding(0, 0, 0, bottomInset);
        l.panel = panel;
        addView(panel, lp);
        stack.add(l);
        GlassHost glassHost = Glass.hostOf(this);
        if (glassHost != null) glassHost.setModalBackdropBlur(true);

        l.scrim.animate().alpha(1f).setDuration(Motion.fade(Motion.D_BASE)).start();
        if (sheet) {
            panel.setVisibility(INVISIBLE);
            panel.post(new Runnable() {
                @Override public void run() {
                    l.panel.setVisibility(VISIBLE);
                    Motion.enter(l.panel, Motion.Kind.SHEET);
                }
            });
            enableSwipeDismiss(l);
        } else {
            Motion.enter(panel, Motion.Kind.DIALOG);
        }
        return l;
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void enableSwipeDismiss(final Layer l) {
        final float[] down = new float[2];
        final VelocityTracker[] vt = {null};
        final boolean[] dragging = {false};
        l.panel.setOnTouchListener(new OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        down[0] = e.getRawX(); down[1] = e.getRawY(); dragging[0] = false;
                        vt[0] = VelocityTracker.obtain(); vt[0].addMovement(e);
                        return false;
                    case MotionEvent.ACTION_MOVE: {
                        if (vt[0] != null) vt[0].addMovement(e);
                        float dy = e.getRawY() - down[1];
                        if (!dragging[0] && dy > Ui.dp(14) && Math.abs(e.getRawX() - down[0]) < dy && e.getY() < Ui.dp(64)) dragging[0] = true;
                        if (dragging[0]) { l.panel.setTranslationY(Math.max(0, dy)); return true; }
                        return false;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        boolean was = dragging[0];
                        if (vt[0] != null) {
                            vt[0].computeCurrentVelocity(1000);
                            float vy = vt[0].getYVelocity();
                            vt[0].recycle();
                            vt[0] = null;
                            if (was) {
                                if (l.panel.getTranslationY() > l.panel.getHeight() * 0.3f || vy > 1400) { if (l.cancelable) dismiss(l); else l.panel.animate().translationY(0).setDuration(Ui.ms(160)).start(); }
                                else l.panel.animate().translationY(0).setDuration(Ui.ms(160)).start();
                            }
                        }
                        dragging[0] = false;
                        return was;
                    }
                    default: return false;
                }
            }
        });
    }

    public void dismiss(final Layer l) {
        if (l == null || l.closing) return;
        l.closing = true;
        stack.remove(l);
        l.scrim.animate().alpha(0f).setDuration(Motion.fade(Motion.D_FAST)).start();
        Motion.exit(l.panel, l.sheet ? Motion.Kind.SHEET : Motion.Kind.DIALOG, new Runnable() {
            @Override public void run() {
                removeView(l.panel);
                removeView(l.scrim);
                GlassHost glassHost = Glass.hostOf(OverlayHost.this);
                if (glassHost != null) glassHost.setModalBackdropBlur(!stack.isEmpty());
                if (l.onDismiss != null) l.onDismiss.run();
            }
        });
    }

    public void dismissAll() { for (Layer l : new ArrayList<>(stack)) dismiss(l); }

    // ---------------------------------------------------------------------------------------------- toast
    public static final int INFO = 0, OK = 1, ERR = 2;

    public void toast(String msg, int kind) {
        if (toast != null) { removeView(toast); toast = null; }
        Context c = getContext();
        LinearLayout row = Ui.row(c);
        row.setPadding(Ui.dp(14), Ui.dp(11), Ui.dp(16), Ui.dp(11));
        int col = kind == OK ? Ui.P.ok : kind == ERR ? Ui.P.err : Ui.P.accent;
        ImageView ic = Ui.icon(c, kind == OK ? R.drawable.ic_ok : kind == ERR ? R.drawable.ic_alert : R.drawable.ic_info, 18, col);
        row.addView(ic);
        TextView t = Ui.text(c, msg, Ui.BODY);
        t.setMaxLines(3);
        row.addView(t, Ui.lpm(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10, 0, 0, 0));
        row.setBackground(panelBg(18));
        Glass.mark(row, 18);
        final View v = row;
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = bottomInset + Ui.dp(84);
        lp.leftMargin = lp.rightMargin = Ui.dp(20);
        addView(v, lp);
        toast = v;
        Motion.enter(v, Motion.Kind.TOAST);
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (toast != v) return;
                Motion.exit(v, Motion.Kind.TOAST, new Runnable() {
                    @Override public void run() { removeView(v); if (toast == v) toast = null; }
                });
            }
        }, kind == ERR ? 4200 : 2400);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        // while any overlay is open, nothing underneath may receive touches
        return super.dispatchTouchEvent(e);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) { return false; }
}
