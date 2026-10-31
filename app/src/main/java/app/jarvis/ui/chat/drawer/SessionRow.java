package app.jarvis.ui.chat.drawer;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.data.ChatStore;
import app.jarvis.ui.Ui;
import app.jarvis.ui.chat.ChatFormat;
import java.util.Calendar;
import java.util.Locale;

/** One session in the drawer: accent bar when active, title, preview, time, item count, overflow button. */
public final class SessionRow {
    private SessionRow() { }

    public static LinearLayout build(Context c, final ChatStore.Conv conv, boolean active, Runnable onOpen, Runnable onMore) {
        LinearLayout row = Ui.row(c);
        row.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(4), Ui.dp(10));
        row.setBackground(active ? Ui.glassTinted(14, Ui.P.accentSoft) : Ui.rippleOn(new android.graphics.drawable.ColorDrawable(0), 14));

        View bar = new View(c);
        bar.setBackground(Ui.rect(active ? Ui.P.accent : 0x00000000, 2));
        row.addView(bar, Ui.lp(Ui.dp(3), Ui.dp(30)));

        LinearLayout text = Ui.column(c);
        LinearLayout.LayoutParams tlp = Ui.lpw(1f);
        tlp.setMargins(Ui.dp(12), 0, Ui.dp(8), 0);
        row.addView(text, tlp);

        LinearLayout head = Ui.row(c);
        if (conv.pinned) head.addView(Ui.icon(c, R.drawable.ic_star, 12, Ui.P.accent), Ui.lpm(-2, -2, 0, 0, 5, 0));
        TextView title = Ui.text(c, conv.title.isEmpty() ? "Без названия" : conv.title, Ui.BODY, Ui.P.text);
        title.setTypeface(active ? Ui.semibold : Ui.medium);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title, Ui.lpw(1f));
        text.addView(head, Ui.lpm(-1, -2, 0, 0, 0, 0));

        String preview = conv.preview == null || conv.preview.isEmpty() ? " " : conv.preview;
        TextView sub = Ui.text(c, preview, Ui.CAPTION, Ui.P.text3);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(sub, Ui.lpm(-1, -2, 0, 2, 0, 0));

        LinearLayout meta = Ui.column(c);
        meta.setGravity(Gravity.END);
        meta.setMinimumWidth(Ui.dp(46));
        TextView time = Ui.text(c, timeLabel(conv.updated), Ui.CAPTION, Ui.P.text3);
        time.setSingleLine(true);
        meta.addView(time);
        TextView count = Ui.chip(c, String.valueOf(conv.items), Ui.P.text2);
        meta.addView(count, Ui.lpm(-2, -2, 0, 4, 0, 0));
        row.addView(meta, Ui.lpm(-2, -2, 0, 0, 2, 0));

        FrameLayout more = Ui.iconButton(c, R.drawable.ic_more, 32, Ui.P.text3, onMore);
        more.setContentDescription("Действия с чатом");
        row.addView(more, Ui.lp(Ui.dp(32), Ui.dp(32)));

        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onOpen.run(); } });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { onMore.run(); return true; }
        });
        return row;
    }

    /** "14:05" for today, "Вчера" for yesterday, otherwise "28.10". */
    static String timeLabel(long ts) {
        String day = ChatFormat.dayLabel(ts);
        if (day.equals("Сегодня")) return ChatFormat.time(ts);
        if (day.equals("Вчера")) return day;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        return String.format(Locale.US, "%02d.%02d", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1);
    }
}
