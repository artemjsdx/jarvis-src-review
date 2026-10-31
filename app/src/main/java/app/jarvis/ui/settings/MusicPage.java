package app.jarvis.ui.settings;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ToolCall;
import app.jarvis.core.tools.Sandbox;
import app.jarvis.core.util.CancelToken;
import app.jarvis.data.Settings;
import app.jarvis.rt.MusicLibrary;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Music settings: favourite song for the morning scenario (picked from real sandbox audio or imported from the
 * phone), preview playback, morning greeting toggle, and a real "play the morning scenario" check through the
 * agent tool music_play.
 */
public class MusicPage extends SettingsPage {
    static final int REQ_IMPORT_AUDIO = 4201;

    private LinearLayout content;
    private LinearLayout tracksHost;
    private TextView checkResult;
    private List<MusicLibrary.Track> tracks = new ArrayList<>();
    private final CancelToken cancel = new CancelToken();

    public MusicPage(Host host) { super(host); }

    @Override public String title() { return "Музыка"; }

    @Override protected View build() {
        ScrollView sv = Ui.scroll(ctx);
        sv.setFillViewport(true);
        content = Ui.column(ctx);
        content.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(24));

        content.addView(Widgets.sectionTitle(ctx, "Любимая песня"));
        LinearLayout favCard = Widgets.card(ctx);
        LinearLayout addRow = Widgets.row(ctx, R.drawable.ic_folder_plus, "Добавить из памяти телефона",
            "Копия сохранится в sandbox/music", null, new Runnable() { @Override public void run() {
                host.pickDocument(screen(), REQ_IMPORT_AUDIO, new String[]{"audio/*"}, true);
            } });
        favCard.addView(addRow);
        Widgets.divide(favCard);
        tracksHost = Ui.column(ctx);
        favCard.addView(tracksHost);
        content.addView(favCard);
        if (j.music == null)
            content.addView(PageUi.note(ctx, "Плеер ещё не подключён: прослушивание и утренний сценарий заработают, когда медиамодуль будет собран."),
                Ui.lpm(-2, -2, Ui.dp(6), Ui.dp(8), Ui.dp(6), 0));

        content.addView(Widgets.sectionTitle(ctx, "Утро"));
        LinearLayout morning = Widgets.card(ctx);
        morning.addView(Widgets.row(ctx, R.drawable.ic_sun, "Утреннее приветствие",
            "По команде вроде «Джарвис, я проснулся» — поприветствовать и включить любимую песню", Widgets.toggle(ctx, j.settings.getBool(Settings.MUSIC_MORNING_GREETING, false),
            new Widgets.Toggle.Listener() { @Override public void onChanged(boolean on) { j.settings.setBool(Settings.MUSIC_MORNING_GREETING, on); } }), null));
        Widgets.divide(morning);
        LinearLayout checkBlock = Ui.column(ctx);
        checkBlock.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(16));
        checkBlock.addView(Ui.button(ctx, "Проверить утренний сценарий", R.drawable.ic_play, Ui.BTN_GLASS, new Runnable() { @Override public void run() { runMorningCheck(); } }));
        checkResult = PageUi.caption(ctx, "Запустит настоящий инструмент music_play: любимая песня → первый трек из music/ → последний присланный.");
        checkResult.setPadding(Ui.dp(0), Ui.dp(8), Ui.dp(0), 0);
        checkBlock.addView(checkResult);
        morning.addView(checkBlock);
        content.addView(morning);

        sv.addView(content);
        rescan();
        return sv;
    }

    @Override public void onShow() { rescan(); }

    private String favorite() { return j.settings.get(Settings.MUSIC_FAVORITE); }

    private void rescan() {
        tracksHost.removeAllViews();
        LinearLayout loading = Ui.row(ctx);
        loading.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        loading.addView(Widgets.spinner(ctx, 18));
        loading.addView(PageUi.note(ctx, "  Ищу аудиофайлы…"), Ui.lpm(-2, -2, 0, 0, 0, 0));
        tracksHost.addView(loading);
        Thread th = new Thread(new Runnable() { @Override public void run() {
            final List<MusicLibrary.Track> found = MusicLibrary.scan(j.sandbox, true, 200);
            j.main.post(new Runnable() { @Override public void run() { renderTracks(found); } });
        } }, "jv-music-scan");
        th.setDaemon(true);
        th.start();
    }

    private void renderTracks(final List<MusicLibrary.Track> found) {
        if (tracksHost == null) return;
        tracksHost.removeAllViews();
        tracks = found;
        String fav = favorite();
        if (found.isEmpty()) {
            LinearLayout e = Widgets.empty(ctx, R.drawable.ic_music, "Аудиофайлов пока нет",
                "Нажмите «Добавить из памяти телефона» или пришлите файл в чат");
            tracksHost.addView(e);
            return;
        }
        int i = 0;
        for (final MusicLibrary.Track t : found) {
            if (i > 0) Widgets.divide(tracksHost);
            StringBuilder sub = new StringBuilder();
            if (t.durationText() != null && !t.durationText().isEmpty()) sub.append(t.durationText()).append(" · ");
            sub.append(PageUi.humanSize(t.size)).append(" · ").append(t.path);
            FrameLayout trail = new FrameLayout(ctx);
            LinearLayout btns = Ui.row(ctx);
            if (j.music != null) {
                btns.addView(Ui.iconButton(ctx, R.drawable.ic_play, 36, Ui.P.accent, new Runnable() { @Override public void run() { preview(t); } }));
            }
            if (t.path.equals(fav)) btns.addView(Ui.icon(ctx, R.drawable.ic_check, 18, Ui.P.accent), Ui.lpm(-2, -2, Ui.dp(4), 0, 0, 0));
            trail.addView(btns, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            LinearLayout row = Widgets.row(ctx, R.drawable.ic_file_audio, t.title, sub.toString(), trail, new Runnable() { @Override public void run() {
                j.settings.set(Settings.MUSIC_FAVORITE, t.path);
                renderTracks(tracks);
                host.overlays().toast("Любимая песня: " + t.title, 0);
            } });
            tracksHost.addView(row);
            i++;
        }
    }

    private void preview(final MusicLibrary.Track t) {
        if (j.music == null) return;
        Thread th = new Thread(new Runnable() { @Override public void run() {
            try {
                j.music.play(t.file, t.title);
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                j.main.post(new Runnable() { @Override public void run() { host.overlays().toast("Не удалось воспроизвести: " + msg, 2); } });
            }
        } }, "jv-music-preview");
        th.setDaemon(true);
        th.start();
    }

    /** Executes the real agent tool music_play with no arguments: favourite → first track → latest received. */
    public void runMorningCheck() {
        checkResult.setText("Проверяю…");
        final long t0 = System.nanoTime();
        Thread th = new Thread(new Runnable() { @Override public void run() {
            final ToolResult r = j.tools.execute(new ToolCall("settings-check", "music_play", "{}"), "settings-music", cancel, j.journal, null);
            final long ms = (System.nanoTime() - t0) / 1_000_000L;
            j.main.post(new Runnable() { @Override public void run() { showCheckResult(r, ms); } });
        } }, "jv-morning-check");
        th.setDaemon(true);
        th.start();
    }

    private void showCheckResult(ToolResult r, long ms) {
        if (r.ok) {
            checkResult.setText("Запустилось за " + ms + " мс: " + r.summary);
            host.overlays().toast("Сценарий работает: " + r.summary, 1);
        } else {
            checkResult.setText("Ошибка: " + (r.error == null || r.error.isEmpty() ? r.summary : r.error));
            host.overlays().toast("Сценарий не сработал — подробности на экране", 2);
        }
    }

    // --------------------------------------------------------------------------- import from the phone
    @Override public void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        if (requestCode != REQ_IMPORT_AUDIO || resultCode != Activity.RESULT_OK || data == null) return;
        final List<android.net.Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            android.content.ClipData clip = data.getClipData();
            for (int i = 0; i < clip.getItemCount(); i++) if (clip.getItemAt(i).getUri() != null) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        final Dialogs.ProgressController progress = Dialogs.progress(host, "Копирование", "Файлы из памяти телефона", null);
        Thread th = new Thread(new Runnable() { @Override public void run() {
            int ok = 0;
            String lastError = "";
            for (int i = 0; i < uris.size(); i++) {
                final int idx = i;
                j.main.post(new Runnable() { @Override public void run() { progress.update("Файл " + (idx + 1) + " из " + uris.size(), (float) idx / uris.size()); } });
                try {
                    importOne(uris.get(i));
                    ok++;
                } catch (Exception e) {
                    lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
            }
            final int done = ok;
            final String err = lastError;
            j.main.post(new Runnable() { @Override public void run() {
                progress.close();
                if (done > 0) host.overlays().toast("Добавлено в music/: " + done, 1);
                if (done < uris.size()) host.overlays().toast("Не удалось скопировать: " + err, 2);
                rescan();
            } });
        } }, "jv-audio-import");
        th.setDaemon(true);
        th.start();
    }

    private void importOne(android.net.Uri uri) throws Exception {
        String name = queryName(uri);
        if (name == null || name.isEmpty()) name = "audio_" + System.currentTimeMillis() + ".mp3";
        name = name.replaceAll("[/\\\\]", "_");
        File dst = new File(j.sandbox.music, name);
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new java.io.IOException("файл недоступен");
        OutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
    }

    private String queryName(android.net.Uri uri) {
        try {
            android.database.Cursor c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c == null) return null;
            try {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (c.moveToFirst() && idx >= 0) return c.getString(idx);
            } finally {
                c.close();
            }
        } catch (Throwable ignored) { }
        String p = uri.getPath();
        return p == null ? null : p.substring(p.lastIndexOf('/') + 1);
    }
}
