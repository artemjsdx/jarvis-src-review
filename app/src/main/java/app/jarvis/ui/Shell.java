package app.jarvis.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.log.Journal;
import app.jarvis.rt.AState;
import app.jarvis.rt.Jarvis;
import app.jarvis.data.Settings;
import app.jarvis.rt.JarvisListener;
import app.jarvis.ui.motion.Motion;
import app.jarvis.ui.theme.ViewState;
import app.jarvis.ui.glass.Glass;
import app.jarvis.ui.glass.GlassHost;
import app.jarvis.ui.glass.GlassLevel;
import app.jarvis.ui.glass.LiquidIndicator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * App chrome: backdrop, slim header (title, live assistant state, settings gear), the current screen, a floating glass dock
 * and the overlay layer. Pure view code (no Activity needed), so the headless preview can render any screen inside it.
 */
public final class Shell extends FrameLayout {
    private final Host host;
    private final Jarvis j;
    private final FrameLayout screenHost;
    private final LinearLayout header, dockRow;
    private final FrameLayout dock;
    private final LiquidIndicator dockIndicator;
    private final GlassHost glass;
    private final TextView titleView, stateView;
    private final View stateDot;
    private final OverlayHost overlays;
    private final Map<String, Screen> screens = new HashMap<>();
    private final List<View> dockItems = new ArrayList<>();
    private final Settings.Listener settingsListener = new Settings.Listener() {
        @Override public void onSettingChanged(String key) {
            if (GlassLevel.KEY.equals(key)) { GlassLevel.configure(j.settings.get(GlassLevel.KEY, "auto")); invalidateTree(Shell.this); }
        }
    };
    private Screen current;
    private String currentId = "", currentArg = "";
    private String dockSelected = "";
    private int insetTop, insetBottom, imeBottom;

    private final JarvisListener listener = new JarvisListener() {
        @Override public void onState(AState s, String detail) { showState(s); }
        @Override public void onToast(String m, int kind) { overlays.toast(m, kind); }
        @Override public void onNavigate(String target) { navigate(target); }
        @Override public void onPermissionNeeded(String perm) { host.requestPermission(perm); }
    };

    public Shell(Host host) {
        super(host.context());
        this.host = host;
        this.j = host.jarvis();
        Context c = getContext();
        GlassLevel.configure(j.settings.get(GlassLevel.KEY, "auto"));
        glass = new GlassHost(c);
        addView(glass, new FrameLayout.LayoutParams(-1, -1));
        Backdrop backdrop = new Backdrop(c);
        glass.content().addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        glass.setBackdrop(backdrop);
        LinearLayout col = Ui.column(c);
        header = Ui.row(c);
        header.setPadding(Ui.dp(18), Ui.dp(6), Ui.dp(8), Ui.dp(6));
        titleView = Ui.text(c, "", Ui.TITLE);
        header.addView(titleView, Ui.lpw(1f));
        stateDot = new View(c);
        stateDot.setBackground(Ui.rect(Ui.P.accent, 5));
        header.addView(stateDot, Ui.lpm(Ui.dp(8), Ui.dp(8), 0, 0, 8, 0));
        stateView = Ui.text(c, "", Ui.CAPTION);
        header.addView(stateView, Ui.lpm(-2, -2, 0, 0, 6, 0));
        header.addView(Ui.iconButton(c, R.drawable.ic_settings, 44, Ui.P.text2, new Runnable() { @Override public void run() { navigate("settings"); } }));
        col.addView(header, new LinearLayout.LayoutParams(-1, -2));
        screenHost = new FrameLayout(c);
        col.addView(screenHost, new LinearLayout.LayoutParams(-1, 0, 1f));
        glass.content().addView(col, new FrameLayout.LayoutParams(-1, -1));

        dockIndicator = new LiquidIndicator(c);
        dockRow = Ui.row(c);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6));
        // the dock is as large as its row; the drop is sized to it, so a match-parent child cannot inflate the glass surface
        dock = new FrameLayout(c) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                measureChild(dockRow, widthSpec, heightSpec);
                int w = dockRow.getMeasuredWidth(), h = dockRow.getMeasuredHeight();
                dockIndicator.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                setMeasuredDimension(w, h);
            }

            @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
                dockIndicator.layout(0, 0, getWidth(), getHeight());
                dockRow.layout(0, 0, getWidth(), getHeight());
            }
        };
        dock.setBackground(new LayerDrawable(new Drawable[]{Ui.rect(Ui.P.dark ? 0x73121018 : 0x8CFFFFFF, 26), new GlassDrawable(26, true)}));
        Glass.mark(dock, 26);
        dock.addView(dockIndicator);
        dock.addView(dockRow);
        dock.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) { retargetDock(); }
        });
        for (int i = 0; i < Screens.DOCK.length; i++) {
            final String id = Screens.DOCK[i];
            LinearLayout item = Ui.row(c);
            item.setGravity(Gravity.CENTER);
            item.setPadding(Ui.dp(10), Ui.dp(9), Ui.dp(10), Ui.dp(9));
            item.setTag(id);
            ImageView ic = Ui.icon(c, Screens.DOCK_ICONS[i], 22, Ui.P.text2);
            ic.setTag("ic");
            item.addView(ic);
            TextView lb = Ui.text(c, Screens.DOCK_LABELS[i], Ui.CAPTION, Ui.P.text);
            lb.setTag("lb");
            lb.setVisibility(View.GONE);
            lb.setSingleLine(true);
            item.addView(lb, Ui.lpm(-2, -2, 6, 0, 0, 0));
            item.setClickable(true);
            item.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { navigate(id); } });
            Ui.pressScale(item);
            dockRow.addView(item, new LinearLayout.LayoutParams(-2, -2));
            dockItems.add(item);
        }
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dlp.bottomMargin = Ui.dp(10);
        glass.overlay().addView(dock, dlp);
        overlays = new OverlayHost(c);
        glass.overlay().addView(overlays, new FrameLayout.LayoutParams(-1, -1));
        showState(j.state());
    }

    /** Glass views re-record their drawing when they are invalidated, so a level change must reach every child. */
    private static void invalidateTree(View v) {
        v.invalidate();
        if (v instanceof ViewGroup) for (int i = 0, n = ((ViewGroup) v).getChildCount(); i < n; i++) invalidateTree(((ViewGroup) v).getChildAt(i));
    }

    public OverlayHost overlays() { return overlays; }
    public LiquidIndicator dockIndicator() { return dockIndicator; }
    public Screen current() { return current; }
    public String currentId() { return currentId; }
    public String currentArg() { return currentArg; }
    public int bottomInset() { return imeBottom > Ui.dp(80) ? imeBottom : insetBottom; }
    public int topInset() { return insetTop; }

    public void attach() {
        j.addListener(listener);
        j.settings.addListener(settingsListener);
    }

    public void destroy() {
        j.removeListener(listener);
        if (current != null) current.onHide();
        j.settings.removeListener(settingsListener);
        for (Screen s : screens.values()) { try { s.onDestroy(); } catch (Throwable ignored) { } }
    }

    /** Copies field text and scroll offsets of the visible screen from the shell it replaces (theme switch). */
    public void copyStateFrom(Shell old) {
        if (old == null || current == null || old.current == null || !old.currentId.equals(currentId)) return;
        ViewState.copy(old.current.view(), current.view());
    }

    /** top/bottom = system bar insets; ime = extra bottom height taken by the keyboard (0 when hidden). */
    public void setInsets(int top, int bottom, int ime) {
        insetTop = top; insetBottom = bottom; imeBottom = ime;
        boolean keyboard = ime > Ui.dp(80);
        header.setPadding(Ui.dp(18), insetTop + Ui.dp(6), Ui.dp(8), Ui.dp(6));
        dock.setVisibility(keyboard ? View.GONE : View.VISIBLE);
        int dockSpace = keyboard ? 0 : Ui.dp(10 + 58 + 6);
        FrameLayout.LayoutParams dlp = (FrameLayout.LayoutParams) dock.getLayoutParams();
        dlp.bottomMargin = insetBottom + Ui.dp(10);
        dock.setLayoutParams(dlp);
        screenHost.setPadding(0, 0, 0, keyboard ? ime : insetBottom + dockSpace);
        overlays.setInsets(insetTop, keyboard ? ime : insetBottom);
        if (current != null) current.onInsets(insetTop, keyboard ? ime : insetBottom + dockSpace);
    }

    private void showState(AState s) {
        stateView.setText(s.label);
        int col = s == AState.ERROR ? Ui.P.err : s == AState.DONE ? Ui.P.ok : s == AState.IDLE ? Ui.P.text3 : Ui.P.accent;
        stateDot.setBackground(Ui.rect(col, 5));
        stateView.setTextColor(s == AState.IDLE ? Ui.P.text3 : Ui.P.text2);
    }

    private void styleDock(String id) {
        for (View item : dockItems) {
            boolean on = item.getTag().equals(id);
            ((ImageView) item.findViewWithTag("ic")).setColorFilter(on ? Ui.P.accent : Ui.P.text2);
            item.findViewWithTag("lb").setVisibility(on ? View.VISIBLE : View.GONE);
        }
        dockSelected = id;
        retargetDock();
    }

    /** Moves the liquid drop under the selected item; before the first layout there is nothing to aim at. */
    private void retargetDock() {
        for (View item : dockItems) {
            if (!item.getTag().equals(dockSelected) || dockRow.getWidth() == 0) continue;
            float l = dockRow.getLeft() + item.getLeft(), t = dockRow.getTop() + item.getTop();
            dockIndicator.moveTo(l, t, l + item.getWidth(), t + item.getHeight());
        }
    }

    public void navigate(String target) {
        if (target == null || target.isEmpty()) return;
        String id = target, arg = "";
        int s = target.indexOf('/');
        if (s > 0) { id = target.substring(0, s); arg = target.substring(s + 1); }
        Screen next = screens.get(id);
        if (next == null) {
            try { next = Screens.create(id, host); } catch (Throwable t) {
                j.journal.e(Journal.Cat.SYSTEM, "Не удалось создать экран " + id, t);
                overlays.toast("Экран «" + id + "» не открылся: " + t.getClass().getSimpleName(), 2);
                return;
            }
            if (next == null) { overlays.toast("Неизвестный раздел: " + id, 2); return; }
            screens.put(id, next);
        }
        overlays.dismissAll();
        if (next == current) { next.onShow(arg); return; }
        final Screen old = current;
        final View nv = next.view();
        final int dir = direction(currentId, id);
        if (nv.getParent() != null) ((ViewGroup) nv.getParent()).removeView(nv);
        screenHost.addView(nv, new FrameLayout.LayoutParams(-1, -1));
        // The new screen becomes current BEFORE the exit animation starts: with motion off (system animations disabled, or "off" in the
        // settings) Motion.screenExit runs the end action synchronously, and that action only removes the old view when it is no longer
        // current. Assigning afterwards left every old screen attached and the screens piled up on top of each other.
        current = next;
        currentId = id;
        currentArg = arg;
        if (old != null) {
            old.onHide();
            final View ov = old.view();
            // Cache the outgoing screen during the transition to avoid repeatedly
            // recording a complex scroll hierarchy on every animation frame.
            if (!Ui.reduceMotion) ov.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            Motion.screenExit(ov, dir, new Runnable() {
                @Override public void run() {
                    // the old screen may have been navigated back to while it was leaving: keep it then
                    if (ov.getParent() == screenHost && (current == null || current.view() != ov)) screenHost.removeView(ov);
                    ov.setAlpha(1f); ov.setTranslationX(0); ov.setTranslationY(0);
                    ov.setLayerType(View.LAYER_TYPE_NONE, null);
                }
            });
        }
        Motion.screenEnter(nv, dir);
        String title = next.title();
        header.setVisibility(title == null ? View.GONE : View.VISIBLE);
        titleView.setText(title == null ? "" : title);
        // a screen outside the dock (settings) must not clear the dock: hiding the selected label makes every item narrower
        // while the liquid drop keeps the old width and covers two icons
        if (java.util.Arrays.asList(Screens.DOCK).contains(id)) styleDock(id);
        host.hideKeyboard();
        next.onShow(arg);
        if (!id.equals("settings") && host.persistsLastScreen()) j.settings.set("ui.lastScreen", id);
        setInsets(insetTop, insetBottom, imeBottom);
    }

    /** +1 when moving right along the dock, -1 when moving left, 0 when either screen is not in the dock. */
    private static int direction(String from, String to) {
        int a = java.util.Arrays.asList(Screens.DOCK).indexOf(from), b = java.util.Arrays.asList(Screens.DOCK).indexOf(to);
        if (a < 0 || b < 0 || a == b) return 0;
        return b > a ? 1 : -1;
    }

    /** @return true if consumed; false means "at home with nothing to close": the activity should leave. */
    public boolean onBack() {
        if (overlays.onBack()) return true;
        if (current != null && current.onBack()) return true;
        if (!"home".equals(currentId) && current != null) { navigate("home"); return true; }
        return false;
    }
}
