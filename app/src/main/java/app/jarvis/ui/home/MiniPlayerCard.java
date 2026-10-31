package app.jarvis.ui.home;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.media.MusicOut;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;

/**
 * Compact "now playing" glass card for the home screen: title, thin progress, position/duration, play/pause, next,
 * stop. Polls {@code music.info()} every 500 ms (only while visible) because position is not pushed through events.
 */
public final class MiniPlayerCard extends LinearLayout {
    private final Jarvis j;
    private final TextView title, time;
    private final Widgets.Progress bar;
    private final ImageView playIcon;
    private final Runnable poll = new Runnable() {
        @Override public void run() { refresh(); schedule(); }
    };
    private boolean polling;

    public MiniPlayerCard(Context c, Jarvis j) {
        super(c);
        this.j = j;
        setOrientation(VERTICAL);
        setBackground(Ui.glass(18));
        setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(6), Ui.dp(10));

        LinearLayout top = Ui.row(c);
        ImageView ic = Ui.icon(c, R.drawable.ic_music, 16, Ui.P.accent);
        top.addView(ic, Ui.lpm(-2, -2, 0, 0, 10, 0));
        title = Ui.text(c, "", Ui.BODY);
        title.setTypeface(Ui.medium);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, Ui.lpw(1f));
        playIcon = Ui.icon(c, R.drawable.ic_play, 18, Ui.P.text);
        LinearLayout playBtn = tapWrap(c, playIcon, new Runnable() { @Override public void run() { togglePlay(); } });
        top.addView(playBtn);
        top.addView(tapWrap(c, Ui.icon(c, R.drawable.ic_skip_next, 18, Ui.P.text), new Runnable() {
            @Override public void run() { MusicOut m = j.music; if (m != null) m.next(); }
        }));
        top.addView(tapWrap(c, Ui.icon(c, R.drawable.ic_stop, 18, Ui.P.text), new Runnable() {
            @Override public void run() { MusicOut m = j.music; if (m != null) m.stop(); }
        }));
        addView(top, new LinearLayout.LayoutParams(-1, -2));

        bar = new Widgets.Progress(c);
        addView(bar, Ui.lpm(-1, Ui.dp(4), 0, 8, 0, 0));

        time = Ui.text(c, "", Ui.CAPTION, Ui.P.text3);
        addView(time, Ui.lpm(-2, -2, 0, 4, 0, 0));
        setVisibility(GONE);
    }

    private LinearLayout tapWrap(Context c, ImageView ic, final Runnable onClick) {
        LinearLayout b = Ui.row(c);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.dp(9), Ui.dp(9), Ui.dp(9), Ui.dp(9));
        b.addView(ic);
        b.setBackground(Ui.rippleOn(new android.graphics.drawable.ColorDrawable(0), 16));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onClick.run(); } });
        Ui.pressScale(b);
        return b;
    }

    private void togglePlay() {
        MusicOut m = j.music;
        if (m == null) return;
        if (m.info().state == MusicOut.State.PLAYING) m.pause(); else m.resume();
    }

    /** @return true when the card is showing something. */
    public boolean refresh() {
        MusicOut m = j.music;
        MusicOut.Info i = m == null ? null : m.info();
        boolean active = i != null && (i.state == MusicOut.State.PLAYING || i.state == MusicOut.State.PAUSED);
        if (!active) { setVisibility(GONE); return false; }
        setVisibility(VISIBLE);
        title.setText(i.title.isEmpty() ? "Аудио" : i.title);
        playIcon.setImageResource(i.state == MusicOut.State.PLAYING ? R.drawable.ic_pause : R.drawable.ic_play);
        long total = Math.max(0, i.durationMs);
        bar.set(total > 0 ? Math.max(0f, Math.min(1f, i.positionMs / (float) total)) : 0f);
        time.setText(fmt(i.positionMs) + (total > 0 ? " / " + fmt(total) : ""));
        return true;
    }

    public void startPolling() {
        polling = true;
        refresh();
        schedule();
    }

    public void stopPolling() {
        polling = false;
        j.main.removeCallbacks(poll);
    }

    private void schedule() {
        if (!polling) return;
        j.main.postDelayed(poll, 500);
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
