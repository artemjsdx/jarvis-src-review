package app.jarvis.ui.files;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.data.Settings;
import app.jarvis.media.MusicOut;
import app.jarvis.rt.Jarvis;
import app.jarvis.rt.MusicLibrary;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import java.io.File;
import java.io.IOException;

/**
 * Bottom sheet for an audio file: real metadata (title/duration from MusicLibrary), a player card on j.music
 * (play/pause/stop/seek), the real error text on failure, and "Сделать любимой песней". The seek bar follows info()
 * polled only while the sheet is open. When the music module is not attached (j.music == null) the card says so
 * honestly and shows metadata only.
 */
public final class AudioSheet {
    private AudioSheet() { }

    public static void open(final Host h, final Jarvis j, final File f) {
        final Context c = h.context();
        if (!f.isFile()) { h.overlays().toast("Файл исчез: " + f.getName(), OverlayHost.ERR); return; }

        final LinearLayout box = Ui.column(c);
        box.addView(grip(c));
        final TextView title = Ui.text(c, f.getName(), Ui.TITLE);
        title.setPadding(Ui.dp(20), Ui.dp(6), Ui.dp(20), Ui.dp(2));
        FileKit.ellipsize(title);
        box.addView(title);

        final LinearLayout card = Widgets.card(c);
        card.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
        box.addView(card, Ui.lpm(-1, -2, 14, 6, 14, 0));

        final TextView songTitle = Ui.text(c, "Чтение метаданных…", Ui.BODY);
        songTitle.setTypeface(Ui.medium);
        songTitle.setTag("songtitle");
        card.addView(songTitle);
        final TextView songMeta = Ui.text(c, FileKit.size(f.length()), Ui.CAPTION, Ui.P.text3);
        songMeta.setTag("meta");
        card.addView(songMeta, Ui.lpm(-2, -2, 0, 2, 0, 0));

        LinearLayout times = Ui.row(c);
        final TextView pos = Ui.text(c, "0:00", Ui.CAPTION, Ui.P.text3);
        times.addView(pos);
        times.addView(Ui.space(c, 1, 1), Ui.lpw(1f));
        final TextView dur = Ui.text(c, "–:––", Ui.CAPTION, Ui.P.text3);
        dur.setTag("dur");
        times.addView(dur);
        card.addView(times, Ui.lpm(-1, -2, 0, 2, 0, 0));

        final TextView errorLine = Ui.text(c, "", Ui.CAPTION, Ui.P.err);
        errorLine.setVisibility(View.GONE);
        card.addView(errorLine, Ui.lpm(-1, -2, 0, 8, 0, 0));

        final LinearLayout playBtn = Ui.button(c, "Играть", R.drawable.ic_play, Ui.BTN_PRIMARY, null);
        LinearLayout controls = Ui.row(c);
        controls.setGravity(Gravity.CENTER);
        controls.addView(playBtn, Ui.lpw(1f));
        LinearLayout stopBtn = Ui.button(c, null, R.drawable.ic_stop, Ui.BTN_GLASS, new Runnable() { @Override public void run() {
            MusicOut m = j.music;
            if (m != null) m.stop();
        }});
        controls.addView(stopBtn, Ui.lpm(Ui.dp(56), -2, 8, 0, 0, 0));
        card.addView(controls, Ui.lpm(-1, -2, 0, 12, 0, 0));

        LinearLayout favRow = Widgets.row(c, R.drawable.ic_heart, "Сделать любимой песней", "Запомнить этот трек в настройках", null, new Runnable() {
            @Override public void run() {
                j.settings.set(Settings.MUSIC_FAVORITE, j.sandbox.rel(f));
                h.overlays().toast("Любимая песня: " + f.getName(), OverlayHost.OK);
            }
        });
        favRow.setBackground(Ui.glass(14));
        box.addView(favRow, Ui.lpm(-1, -2, 14, 10, 14, 0));
        box.addView(Ui.space(c, 1, 14));

        final boolean[] dragging = {false};
        final long[] lastUser = {0};
        final long[] duration = {0};
        final boolean[] active = {true};
        final Handler handler = new Handler(Looper.getMainLooper());

        final Widgets.Slider seek = Widgets.slider(c, 0, 1, 0, 0, new Widgets.SliderListener() { @Override public void onValue(float v, boolean fromUser) {
            if (!fromUser) return;
            dragging[0] = true;
            lastUser[0] = System.nanoTime();
            pos.setText(fmtTime((long) (v * duration[0])));
        }});
        seek.setEnabled(false);
        // release = a short gap without new drag events; the slider callback cannot distinguish up from move
        final Runnable maybeRelease = new Runnable() { @Override public void run() {
            if (!active[0] || !dragging[0]) return;
            if (System.nanoTime() - lastUser[0] > 350_000_000L) {
                dragging[0] = false;
                MusicOut m = j.music;
                if (m != null && duration[0] > 0) m.seekTo((long) (seek.getValue() * duration[0]));
            }
            handler.postDelayed(this, 120);
        }};
        handler.postDelayed(maybeRelease, 200);

        if (j.music == null) {
            playBtn.setAlpha(0.4f);
            playBtn.setClickable(false);
            errorLine.setVisibility(View.VISIBLE);
            errorLine.setText("Плеер ещё не подключён — модуль музыки в разработке.");
        } else {
            playBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { toggle(j, f, playBtn, errorLine); }});
        }

        // real metadata (MediaMetadataRetriever) off the UI thread
        new Thread(new Runnable() { @Override public void run() {
            final MusicLibrary.Track t = MusicLibrary.describe(j.sandbox, f, true);
            j.main.post(new Runnable() { @Override public void run() {
                if (!active[0]) return;
                String tt = t.title == null || t.title.isEmpty() ? f.getName() : t.title;
                ((TextView) card.findViewWithTag("songtitle")).setText(tt);
                String meta = FileKit.size(t.size);
                if (!t.artist.isEmpty()) meta = t.artist + " · " + meta;
                if (t.durationMs > 0) meta += " · " + t.durationText();
                ((TextView) card.findViewWithTag("meta")).setText(meta);
                title.setText(tt);
                if (t.durationMs > 0) {
                    duration[0] = t.durationMs;
                    seek.setEnabled(true);
                    ((TextView) card.findViewWithTag("dur")).setText(fmtTime(t.durationMs));
                }
            }});
        }}, "jv-audio-meta").start();

        // poll info() every 500 ms while the sheet is open
        final Runnable poll = new Runnable() { @Override public void run() {
            if (!active[0]) return;
            MusicOut m = j.music;
            if (m != null) {
                MusicOut.Info i = m.info();
                boolean ours = i.path != null && i.path.equals(f.getPath());
                if (ours && (i.state == MusicOut.State.PLAYING || i.state == MusicOut.State.PAUSED)) {
                    stylePlay(playBtn, i.state == MusicOut.State.PLAYING);
                    if (i.durationMs > 0) {
                        duration[0] = i.durationMs;
                        seek.setEnabled(true);
                        ((TextView) card.findViewWithTag("dur")).setText(fmtTime(i.durationMs));
                    }
                    if (!dragging[0]) {
                        seek.setValue(i.durationMs > 0 ? (float) i.positionMs / i.durationMs : 0);
                        pos.setText(fmtTime(i.positionMs));
                    }
                } else if (ours && i.state == MusicOut.State.STOPPED) {
                    stylePlay(playBtn, false);
                    if (!dragging[0]) { seek.setValue(0); pos.setText("0:00"); }
                }
                if (i.error != null && !i.error.isEmpty()) { errorLine.setVisibility(View.VISIBLE); errorLine.setText(i.error); }
            }
            handler.postDelayed(this, 500);
        }};
        handler.postDelayed(poll, 300);

        h.overlays().showSheet(box, true, new Runnable() { @Override public void run() {
            active[0] = false;
            handler.removeCallbacksAndMessages(null);
        }});
    }

    private static void toggle(Jarvis j, File f, LinearLayout playBtn, TextView errorLine) {
        MusicOut m = j.music;
        if (m == null) return;
        try {
            MusicOut.Info i = m.info();
            boolean ours = i.path != null && i.path.equals(f.getPath());
            if (ours && i.state == MusicOut.State.PLAYING) { m.pause(); stylePlay(playBtn, false); return; }
            if (ours && i.state == MusicOut.State.PAUSED) { m.resume(); stylePlay(playBtn, true); return; }
            errorLine.setVisibility(View.GONE);
            m.play(f, f.getName());
            stylePlay(playBtn, true);
        } catch (IOException e) {
            stylePlay(playBtn, false);
            errorLine.setVisibility(View.VISIBLE);
            errorLine.setText("Не удалось воспроизвести: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } catch (Throwable t) {
            stylePlay(playBtn, false);
            errorLine.setVisibility(View.VISIBLE);
            errorLine.setText("Ошибка плеера: " + t.getClass().getSimpleName());
        }
    }

    private static void stylePlay(LinearLayout btn, boolean playing) {
        View label = btn.getChildAt(btn.getChildCount() - 1);
        View icon = btn.getChildAt(0);
        if (label instanceof TextView) ((TextView) label).setText(playing ? "Пауза" : "Играть");
        if (icon instanceof ImageView) ((ImageView) icon).setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private static String fmtTime(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }

    private static View grip(Context c) {
        FrameLayout fr = new FrameLayout(c);
        View g = new View(c);
        g.setBackground(Ui.rect(Ui.P.strokeHi, 3));
        fr.addView(g, new FrameLayout.LayoutParams(Ui.dp(36), Ui.dp(4), Gravity.CENTER));
        fr.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(20)));
        return fr;
    }
}
