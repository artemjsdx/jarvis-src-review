package app.jarvis.ui.chat;

import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.data.ChatItem;
import app.jarvis.data.ChatStore;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;
import app.jarvis.ui.chat.actions.ActionGroupView;
import app.jarvis.ui.chat.actions.ActionGrouping;
import app.jarvis.ui.chat.list.ChatListView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Renders the conversation into a ChatListView: day separators, bubbles, action groups, error and system lines.
 * Only the newest WINDOW rows are built; older rows come in on scroll-up. A new row fades and rises in, a re-render
 * of existing rows does not animate.
 */
public final class ChatAdapter implements ActionGroupView.State {
    public static final int WINDOW = 60;
    private static final long RISE_MS = 220;

    /** Callbacks the screen provides. */
    public interface Actions { void onSpeak(String text); }

    private final Host host;
    private final Actions actions;
    private final ChatListView target;
    private final List<ChatItem> items = new ArrayList<>();
    private final Set<Long> groupOpen = new HashSet<>(), subOpen = new HashSet<>(), seen = new HashSet<>();
    private int window = WINDOW;
    private boolean animating, hasOlder;

    public ChatAdapter(Host host, Actions actions, ChatListView target) {
        this.host = host;
        this.actions = actions;
        this.target = target;
        target.setNearTopListener(new Runnable() { @Override public void run() { loadOlder(); } });
    }

    public List<ChatItem> items() { return items; }

    /** Full reload (conversation switch, clear): nothing animates. */
    public void setAll(List<ChatItem> loaded) {
        items.clear();
        items.addAll(loaded);
        window = WINDOW;
        rebuild(false);
    }

    /** @return true if the list changed. */
    public boolean upsert(ChatItem it) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).seq == it.seq) {
                items.set(i, it);
                rebuild(true);
                return true;
            }
        }
        items.add(it);
        rebuild(true);
        return true;
    }

    public void loadOlder() {
        if (!hasOlder) return;
        window += WINDOW;
        rebuild(false);
    }

    private void rebuild(boolean animateNew) {
        List<ActionGrouping.Row> layout = ActionGrouping.layout(items);
        int from = Math.max(0, layout.size() - window);
        List<View> rows = new ArrayList<>();
        List<Object> keys = new ArrayList<>();
        animating = animateNew;
        try {
            for (int i = from; i < layout.size(); i++) {
                ActionGrouping.Row r = layout.get(i);
                switch (r.kind) {
                    case DAY:
                        rows.add(sepView(r.ts));
                        keys.add("day" + (r.ts / 86_400_000L));
                        break;
                    case RUN:
                        rows.add(new ActionGroupView(host.context(), host, this, r.run));
                        keys.add("run" + r.run.get(0).seq);
                        break;
                    default: {
                        View v = itemView(r.item);
                        if (seen.add(r.item.seq) && animateNew) rise(v);
                        rows.add(v);
                        keys.add(r.item.seq);
                    }
                }
            }
        } finally {
            animating = false;
        }
        hasOlder = from > 0;
        target.setHasOlder(hasOlder);
        target.setRows(rows, keys);
    }

    private View itemView(ChatItem it) {
        switch (it.kind) {
            case USER: return bubbleRow(it, true);
            case ASSISTANT: return bubbleRow(it, false);
            case ERROR: return errorView(it);
            default: return sysView(it);
        }
    }

    private static void rise(View v) {
        v.setAlpha(0f);
        v.setTranslationY(Ui.dpf(8));
        v.animate().alpha(1f).translationY(0f).setDuration(Ui.ms(RISE_MS)).start();
    }

    // ---- separators / system ---------------------------------------------------------------------------------------
    private View sepView(long ts) {
        LinearLayout row = Ui.column(host.context());
        row.setGravity(Gravity.CENTER);
        TextView t = Ui.text(host.context(), ChatFormat.dayLabel(ts), Ui.CAPTION, Ui.P.text3);
        t.setTypeface(Ui.medium);
        t.setPadding(Ui.dp(12), Ui.dp(3), Ui.dp(12), Ui.dp(4));
        t.setBackground(Ui.rect(Ui.P.dark ? 0x16000000 : 0x101B1740, 12));
        row.addView(t, Ui.lpm(-2, -2, 0, Ui.dp(7), 0, Ui.dp(5)));
        return row;
    }

    private View sysView(ChatItem it) {
        if (ChatStore.CONTEXT_CLEARED.equals(it.text)) return contextDivider();
        LinearLayout row = Ui.column(host.context());
        TextView t = Ui.text(host.context(), it.text, Ui.CAPTION, Ui.P.text3);
        t.setGravity(Gravity.CENTER);
        row.addView(t, Ui.lpm(-1, -2, Ui.dp(32), Ui.dp(6), Ui.dp(32), Ui.dp(6)));
        return row;
    }

    /** Context cut: a rule on each side of the label; everything above it stays visible but is not sent to the model. */
    private View contextDivider() {
        android.content.Context c = host.context();
        LinearLayout row = Ui.row(c);
        row.setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(16), Ui.dp(10));
        row.addView(Ui.divider(c), new LinearLayout.LayoutParams(0, Math.max(1, Ui.dp(1)), 1f));
        TextView t = Ui.text(c, "Контекст очищен", Ui.CAPTION, Ui.P.text3);
        row.addView(t, Ui.lpm(-2, -2, 10, 0, 10, 0));
        row.addView(Ui.divider(c), new LinearLayout.LayoutParams(0, Math.max(1, Ui.dp(1)), 1f));
        return row;
    }

    // ---- bubbles ---------------------------------------------------------------------------------------------------
    private View bubbleRow(final ChatItem it, boolean user) {
        LinearLayout row = Ui.column(host.context());
        row.setPadding(Ui.dp(12), Ui.dp(3), Ui.dp(12), Ui.dp(3));
        BubbleView b = new BubbleView(host.context());
        // Message bubbles are frosted surfaces, not independent translucent
        // gradients. GlassHost samples the wallpaper behind these rounded views.
        b.setBackground(user ? Ui.glassTinted(18, Ui.P.bubbleUser) : Ui.glass(18));
        b.setPadding(Ui.dp(12), Ui.dp(9), Ui.dp(12), Ui.dp(7));
        b.text.setText(it.text);
        boolean voice = "voice".equals(it.source);
        b.src.setVisibility(user && voice ? View.VISIBLE : View.GONE);
        boolean direct = "direct".equals(it.model);
        b.model.setVisibility(user || it.model.isEmpty() || direct ? View.GONE : View.VISIBLE);
        b.model.setText(it.model);
        b.time.setText(ChatFormat.time(it.ts));
        if (user) bindAtts(b.atts, it);
        if (!user) {
            b.text.setMenuListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { messageMenu(it); return true; }
            });
        }
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.gravity = user ? Gravity.END : Gravity.START;
        row.addView(b, blp);
        return row;
    }

    private void bindAtts(LinearLayout box, ChatItem it) {
        box.removeAllViews();
        for (final ChatItem.Attachment a : it.attachments) {
            LinearLayout chip = Ui.row(box.getContext());
            chip.setPadding(Ui.dp(10), Ui.dp(5), Ui.dp(10), Ui.dp(5));
            chip.setBackground(Ui.rect(Ui.P.dark ? 0x1FFFFFFF : 0x141B1740, 12));
            chip.addView(Ui.icon(box.getContext(), R.drawable.ic_file, 13, Ui.P.text2));
            TextView name = Ui.text(box.getContext(), a.name.isEmpty() ? a.path : a.name, Ui.CAPTION, Ui.P.text2);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            chip.addView(name, Ui.lpm(-2, -2, 6, 0, 6, 0));
            if (a.size > 0) chip.addView(Ui.text(box.getContext(), ChatStore.humanSize(a.size), Ui.CAPTION, Ui.P.text3));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = Ui.dp(5);
            box.addView(chip, lp);
        }
        box.setVisibility(it.attachments.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void messageMenu(final ChatItem it) {
        Dialogs.choose(host, "Сообщение", new String[]{
            "Копировать текст\nсохранить в буфер обмена",
            "Озвучить\nпроизнести через синтез речи"}, -1,
            new Dialogs.IndexListener() {
                @Override public void on(int index) {
                    if (index == 0) host.copyToClipboard("Сообщение", it.text);
                    else if (index == 1) actions.onSpeak(it.text);
                }
            });
    }

    // ---- error card ------------------------------------------------------------------------------------------------
    private View errorView(final ChatItem it) {
        LinearLayout row = Ui.column(host.context());
        row.setPadding(Ui.dp(10), Ui.dp(3), Ui.dp(10), Ui.dp(3));
        LinearLayout card = Ui.column(host.context());
        GradientDrawable bg = Ui.rect(0x12F08A9A, 16);
        bg.setStroke(Math.max(1, Ui.dp(1)), 0x3DF08A9A);
        card.setBackground(bg);
        card.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
        LinearLayout head = Ui.row(host.context());
        head.addView(Ui.icon(host.context(), R.drawable.ic_alert, 15, Ui.P.err));
        TextView title = Ui.text(host.context(), "Ошибка", Ui.CAPTION, Ui.P.err);
        title.setTypeface(Ui.medium);
        head.addView(title, Ui.lpm(-2, -2, Ui.dp(8), 0, 0, 0));
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));
        TextView msg = Ui.text(host.context(), it.text, Ui.BODY, Ui.P.text);
        card.addView(msg, Ui.lpm(-1, -2, 0, Ui.dp(6), 0, 0));
        final String tech = ChatFormat.pretty(it.toolArgs);
        if (!tech.isEmpty()) {
            View btn = Ui.button(host.context(), "Подробности", R.drawable.ic_info, Ui.BTN_GHOST, null);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { Dialogs.info(host, "Подробности ошибки", it.text + "\n\n" + tech); }
            });
            card.addView(btn, Ui.lpm(-2, -2, 0, Ui.dp(6), 0, 0));
        }
        row.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    // ---- ActionGroupView.State -------------------------------------------------------------------------------------
    @Override public boolean isGroupOpen(long key) { return groupOpen.contains(key); }

    @Override public void setGroupOpen(long key, boolean open) {
        if (open) groupOpen.add(key); else groupOpen.remove(key);
    }

    @Override public boolean isSubOpen(long seq) { return subOpen.contains(seq); }

    @Override public void setSubOpen(long seq, boolean open) {
        if (open) subOpen.add(seq); else subOpen.remove(seq);
    }

    @Override public boolean firstSeen(long seq) { return seen.add(seq) && animating; }
}
