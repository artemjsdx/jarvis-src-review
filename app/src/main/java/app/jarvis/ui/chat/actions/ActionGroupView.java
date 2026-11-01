package app.jarvis.ui.chat.actions;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.data.ChatItem;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;
import app.jarvis.ui.chat.ChatFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * One action block for a run of tool calls. The header is a strip of tool icons (newest on the right, at most
 * STRIP_MAX; a running call shows nine blinking dots, a finished one a grey icon with a check or cross) plus the summary
 * and a chevron. The body lists one SubBlockView per call and a "свернуть всё" chip when several are open.
 */
public final class ActionGroupView extends LinearLayout {
    /** Expansion and first-seen bookkeeping kept by the owner, so a re-rendered chat keeps what the user opened. */
    public interface State {
        boolean isGroupOpen(long key);
        void setGroupOpen(long key, boolean open);
        boolean isSubOpen(long seq);
        void setSubOpen(long seq, boolean open);
        /** true only for a tool call that has just appeared and should animate in. */
        boolean firstSeen(long seq);
    }

    static final int STRIP_MAX = 6;
    private static final long TICK_MS = 500, ANIM_MS = 220;

    private final long key;
    private final List<ChatItem> tools;
    private final LinearLayout strip, body, subs;
    private final TextView summary, collapseAll;
    private final ImageView chevron;
    private final List<SubBlockView> blocks = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshSummary();
            if (anyRunning()) handler.postDelayed(this, TICK_MS);
        }
    };
    private boolean open;

    public ActionGroupView(Context c, final Host host, final State state, List<ChatItem> run) {
        super(c);
        this.tools = run;
        key = run.get(0).seq;
        setOrientation(VERTICAL);
        setBackground(Ui.glass(18));
        Ui.clipToRounded(this, 18);
        setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6));

        LinearLayout header = Ui.row(c);
        header.setBackground(Ui.rippleOn(new ColorDrawable(0), 14));
        header.setPadding(Ui.dp(10), Ui.dp(7), Ui.dp(10), Ui.dp(7));
        header.setClickable(true);
        header.setFocusable(true);
        header.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { toggle(state); } });
        strip = Ui.row(c);
        header.addView(strip, new LayoutParams(-2, -2));
        header.addView(new android.widget.Space(c), new LinearLayout.LayoutParams(0, 0, 1f));
        summary = Ui.text(c, "", Ui.CAPTION, Ui.P.text2);
        summary.setSingleLine(true);
        header.addView(summary, Ui.lpm(-2, -2, 0, 0, Ui.dp(6), 0));
        chevron = Ui.icon(c, R.drawable.ic_chevron_down, 16, Ui.P.text3);
        header.addView(chevron);
        addView(header, new LayoutParams(-1, -2));

        body = Ui.column(c);
        subs = Ui.column(c);
        body.addView(subs, new LayoutParams(-1, -2));
        collapseAll = Ui.text(c, "свернуть всё", Ui.CAPTION, Ui.P.accent);
        collapseAll.setTypeface(Ui.medium);
        collapseAll.setPadding(Ui.dp(10), Ui.dp(5), Ui.dp(10), Ui.dp(5));
        collapseAll.setBackground(Ui.glassPressable(12));
        collapseAll.setVisibility(GONE);
        collapseAll.setClickable(true);
        collapseAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                for (SubBlockView b : blocks) b.setOpen(false, true);
            }
        });
        body.addView(collapseAll, Ui.lpm(-2, -2, Ui.dp(4), Ui.dp(6), 0, Ui.dp(2)));

        boolean[] fresh = new boolean[run.size()];
        for (int i = 0; i < run.size(); i++) fresh[i] = state.firstSeen(run.get(i).seq);

        int n = run.size();
        for (int i = Math.max(0, n - STRIP_MAX); i < n; i++) {
            View slot = slot(c, run.get(i), i == n - 1);
            strip.addView(slot, Ui.lpm(Ui.dp(22), Ui.dp(22), 0, 0, Ui.dp(2), 0));
            if (fresh[i]) {
                slot.setAlpha(0f);
                slot.setTranslationX(Ui.dpf(10));
                slot.animate().alpha(1f).translationX(0f).setDuration(Ui.ms(ANIM_MS)).start();
            }
        }
        for (int i = 0; i < n; i++) {
            SubBlockView sb = new SubBlockView(c, host, run.get(i), state, fresh[i], new Runnable() {
                @Override public void run() { refreshCollapseAll(); }
            });
            blocks.add(sb);
            if (i > 0) {
                View separator = Ui.divider(c);
                subs.addView(separator, Ui.lpm(-1, Ui.dp(1), 18, 3, 18, 3));
            }
            subs.addView(sb, new LayoutParams(-1, -2));
        }

        open = state.isGroupOpen(key);
        body.setVisibility(open ? VISIBLE : GONE);
        chevron.setRotation(open ? 180f : 0f);
        addView(body, new LayoutParams(-1, -2));
        refreshCollapseAll();
        refreshSummary();
        if (anyRunning()) handler.postDelayed(tick, TICK_MS);
    }

    private void toggle(State state) {
        open = !open;
        state.setGroupOpen(key, open);
        chevron.animate().rotation(open ? 180f : 0f).setDuration(Ui.ms(ANIM_MS)).start();
        Expand.set(body, open, Ui.ms(ANIM_MS));
    }

    private View slot(Context c, ChatItem t, boolean rightmost) {
        FrameLayout f = new FrameLayout(c);
        if (t.toolStatus == ChatItem.ToolStatus.RUNNING) {
            ActionDotsView dots = new ActionDotsView(c, 16, Ui.P.accent);
            dots.setActive(true);
            f.addView(dots, new FrameLayout.LayoutParams(Ui.dp(16), Ui.dp(16), Gravity.CENTER));
            if (rightmost) f.setBackground(Ui.glassTinted(8, Ui.P.accentSoft));
            return f;
        }
        f.addView(Ui.icon(c, iconRes(ToolIcons.iconName(t.toolName)), 16, Ui.P.text3),
            new FrameLayout.LayoutParams(Ui.dp(16), Ui.dp(16), Gravity.CENTER));
        // The per-action status is shown on the vertical rail below; duplicating a
        // second check mark on the tool icon made the header look cluttered.
        return f;
    }

    private void refreshCollapseAll() {
        int openCount = 0;
        for (SubBlockView b : blocks) if (b.isOpen()) openCount++;
        collapseAll.setVisibility(openCount > 1 ? VISIBLE : GONE);
    }

    private boolean anyRunning() {
        for (ChatItem t : tools) if (t.toolStatus == ChatItem.ToolStatus.RUNNING) return true;
        return false;
    }

    private void refreshSummary() {
        ActionGrouping.Summary s = ActionGrouping.summarize(tools, System.currentTimeMillis());
        summary.setText(s.count + " " + ActionGrouping.actionsWord(s.count) + " · " + ChatFormat.duration(s.totalMs));
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(tick);
    }

    static int iconRes(String name) {
        switch (name) {
            case "ic_folder": return R.drawable.ic_folder;
            case "ic_search": return R.drawable.ic_search;
            case "ic_globe": return R.drawable.ic_globe;
            case "ic_terminal": return R.drawable.ic_terminal;
            case "ic_brain": return R.drawable.ic_brain;
            case "ic_music": return R.drawable.ic_music;
            case "ic_voice": return R.drawable.ic_voice;
            case "ic_file_text": return R.drawable.ic_file_text;
            case "ic_download": return R.drawable.ic_download;
            case "ic_bell": return R.drawable.ic_bell;
            default: return R.drawable.ic_wrench;
        }
    }
}
