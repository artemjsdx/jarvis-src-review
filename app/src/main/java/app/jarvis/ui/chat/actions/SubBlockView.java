package app.jarvis.ui.chat.actions;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.data.ChatItem;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;
import app.jarvis.ui.chat.ChatFormat;
import java.util.Locale;

/** One action inside an action group: a one-line head (status, icon, title, duration) that opens args and result. */
public final class SubBlockView extends LinearLayout {
    private static final long ANIM_MS = 200;

    private final ChatItem item;
    private final ActionGroupView.State state;
    private final Runnable onToggle;
    private final LinearLayout details;
    private final ImageView chevron;
    private boolean open;

    SubBlockView(Context c, Host host, ChatItem it, ActionGroupView.State state, boolean fresh, Runnable onToggle) {
        super(c);
        this.item = it;
        this.state = state;
        this.onToggle = onToggle;
        setOrientation(VERTICAL);
        open = state.isSubOpen(it.seq);

        LinearLayout head = Ui.row(c);
        head.setTag(it.seq);
        head.setClickable(true);
        head.setFocusable(true);
        head.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(8), Ui.dp(6));
        head.setBackground(Ui.rect(Ui.P.dark ? 0x12FFFFFF : 0x0D1B1740, 10));
        head.addView(statusSlot(c, it), Ui.lpm(Ui.dp(14), Ui.dp(14), 0, 0, Ui.dp(8), 0));
        head.addView(Ui.icon(c, ActionGroupView.iconRes(ToolIcons.iconName(it.toolName)), 14, Ui.P.text2),
            Ui.lpm(Ui.dp(14), Ui.dp(14), 0, 0, Ui.dp(6), 0));
        TextView title = Ui.text(c, ChatFormat.toolTitle(it.toolName), Ui.CAPTION, Ui.P.text);
        title.setTypeface(Ui.medium);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(title, Ui.lpw(1f));
        if (it.durationMs > 0) head.addView(Ui.text(c, ChatFormat.duration(it.durationMs), Ui.CAPTION, Ui.P.text3), Ui.lpm(-2, -2, Ui.dp(8), 0, 0, 0));
        chevron = Ui.icon(c, R.drawable.ic_chevron_down, 14, Ui.P.text3);
        chevron.setRotation(open ? 180f : 0f);
        head.addView(chevron, Ui.lpm(Ui.dp(14), Ui.dp(14), Ui.dp(6), 0, 0, 0));
        head.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setOpen(!open, true); } });
        addView(head, new LayoutParams(-1, -2));

        details = Ui.column(c);
        details.setPadding(Ui.dp(10), Ui.dp(4), Ui.dp(10), Ui.dp(8));
        details.setVisibility(open ? View.VISIBLE : View.GONE);
        details.addView(caption(c, "Аргументы"));
        details.addView(mono(c, pretty(item.toolArgs, "{}")), Ui.lpm(-1, -2, 0, Ui.dp(4), 0, 0));
        String res = ChatFormat.pretty(item.text);
        if (!res.isEmpty()) {
            details.addView(caption(c, "Результат"), Ui.lpm(-2, -2, 0, Ui.dp(8), 0, 0));
            details.addView(mono(c, res), Ui.lpm(-1, -2, 0, Ui.dp(4), 0, 0));
        }
        View copy = Ui.button(c, "Копировать", R.drawable.ic_copy, Ui.BTN_GHOST, null);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.copyToClipboard("Инструмент " + item.toolName, "$ " + pretty(item.toolArgs, "{}") + "\n→ " + (item.text == null ? "" : item.text));
            }
        });
        details.addView(copy, Ui.lpm(-2, -2, 0, Ui.dp(6), 0, 0));
        addView(details, new LayoutParams(-1, -2));

        if (fresh) {
            setAlpha(0f);
            setTranslationY(Ui.dpf(8));
            animate().alpha(1f).translationY(0f).setDuration(Ui.ms(ANIM_MS)).start();
        }
    }

    /** Opens or closes the details; the state store remembers it across re-renders of the chat. */
    void setOpen(boolean v, boolean animate) {
        if (v == open) return;
        open = v;
        state.setSubOpen(item.seq, v);
        chevron.animate().rotation(v ? 180f : 0f).setDuration(Ui.ms(ANIM_MS)).start();
        Expand.set(details, v, animate ? Ui.ms(ANIM_MS) : 0);
        if (onToggle != null) onToggle.run();
    }

    boolean isOpen() { return open; }

    private static View statusSlot(Context c, ChatItem it) {
        if (it.toolStatus == ChatItem.ToolStatus.RUNNING) {
            ActionDotsView dots = new ActionDotsView(c, 14, Ui.P.accent);
            dots.setActive(true);
            return dots;
        }
        int res, col;
        if (it.toolStatus == ChatItem.ToolStatus.DONE) { res = R.drawable.ic_ok; col = Ui.P.ok; }
        else if (it.toolStatus == ChatItem.ToolStatus.INTERRUPTED) { res = R.drawable.ic_warning; col = Ui.P.warn; }
        else { res = R.drawable.ic_fail; col = Ui.P.err; }
        return Ui.icon(c, res, 14, col);
    }

    private static String pretty(String raw, String fallback) {
        String s = ChatFormat.pretty(raw);
        return s.isEmpty() ? fallback : s;
    }

    private static TextView caption(Context c, String s) {
        TextView t = Ui.text(c, s.toUpperCase(Locale.ROOT), Ui.CAPTION, Ui.P.text3);
        t.setTypeface(Ui.medium);
        t.setLetterSpacing(0.06f);
        return t;
    }

    private static TextView mono(Context c, String s) {
        TextView t = Ui.text(c, s, Ui.MONO, Ui.P.text2);
        t.setBackground(Ui.rect(Ui.P.dark ? 0x14000000 : 0x0F1B1740, 10));
        t.setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(10), Ui.dp(8));
        return t;
    }
}
