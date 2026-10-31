package app.jarvis.ui.files;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * Viewer + editor for small text files (text/markdown/json/log, up to 1 MB) inside a bottom sheet.
 * Viewing is selectable; editing swaps in an EditText; saving is atomic (temp file + rename). Closing with unsaved
 * changes asks what to do and can restore the editor with the typed text intact.
 */
public final class TextEditorSheet {
    private TextEditorSheet() { }

    public static final long MAX_BYTES = 1024 * 1024;

    public static void open(final Host h, final Jarvis j, final File f, final Runnable onChanged) {
        openInternal(h, j, f, onChanged, null, false);
    }

    /** @param restoreText when not null the sheet opens straight into edit mode with this text (after "вернуться к правке"). */
    private static void openInternal(final Host h, final Jarvis j, final File f, final Runnable onChanged, final String restoreText, final boolean startInEdit) {
        final Context c = h.context();
        if (!f.isFile()) { h.overlays().toast("Файл исчез: " + f.getName(), OverlayHost.ERR); return; }
        if (restoreText == null && f.length() > MAX_BYTES) {
            Dialogs.info(h, "Файл слишком большой", "«" + f.getName() + "» занимает " + FileKit.size(f.length())
                + ". В просмотрщике можно открывать текст до 1 МБ.");
            return;
        }

        final LinearLayout box = Ui.column(c);
        box.addView(grip(c));

        LinearLayout head = Ui.row(c);
        head.setPadding(Ui.dp(20), Ui.dp(6), Ui.dp(12), Ui.dp(8));
        LinearLayout names = Ui.column(c);
        TextView name = Ui.text(c, f.getName(), Ui.BODY);
        name.setTypeface(Ui.medium);
        FileKit.ellipsize(name);
        names.addView(name);
        TextView meta = Ui.text(c, FileKit.size(f.length()) + " · " + FileKit.typeLabel(f), Ui.CAPTION, Ui.P.text3);
        names.addView(meta, Ui.lpm(-2, -2, 0, 2, 0, 0));
        head.addView(names, Ui.lpw(1f));
        final LinearLayout editBtn = Ui.button(c, "Изменить", R.drawable.ic_edit, Ui.BTN_GHOST, null);
        head.addView(editBtn);
        final LinearLayout saveBtn = Ui.button(c, "Сохранить", R.drawable.ic_save, Ui.BTN_PRIMARY, null);
        saveBtn.setVisibility(View.GONE);
        head.addView(saveBtn, Ui.lpm(-2, -2, 8, 0, 0, 0));
        box.addView(head);

        final LinearLayout bodyHolder = Ui.column(c);
        box.addView(bodyHolder, Ui.lpm(-1, -2, 12, 0, 12, 0));

        final boolean[] dirty = {false};
        final String[] original = {restoreText};       // last saved / loaded content
        final String[] edited = {restoreText};         // what the editor holds right now
        final boolean[] closed = {false};
        final OverlayHost.Layer[] ly = new OverlayHost.Layer[1];

        final Runnable syncButtons = new Runnable() {
            @Override public void run() {
                editBtn.setVisibility(View.VISIBLE);
                saveBtn.setVisibility(dirty[0] ? View.VISIBLE : View.GONE);
            }
        };

        final Runnable closeGuard = new Runnable() { @Override public void run() { h.overlays().dismiss(ly[0]); } };

        editBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            showBody(h, f, bodyHolder, edited[0], true, new Runnable() { @Override public void run() {
                dirty[0] = true;
                syncButtons.run();
            }}, closeGuard, edited);
            syncButtons.run();
        }});
        saveBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { save(h, j, f, bodyHolder, original, new Runnable() {
            @Override public void run() { dirty[0] = false; syncButtons.run(); if (onChanged != null) onChanged.run(); }
        }); }});

        if (restoreText != null || startInEdit) {
            showBody(h, f, bodyHolder, restoreText == null ? "" : restoreText, true, new Runnable() { @Override public void run() {
                dirty[0] = true; syncButtons.run();
            }}, closeGuard, edited);
            syncButtons.run();
        } else {
            final TextView loading = Ui.text(c, "Загрузка…", Ui.CAPTION, Ui.P.text3);
            loading.setPadding(Ui.dp(20), Ui.dp(12), Ui.dp(20), Ui.dp(20));
            bodyHolder.addView(loading, new LinearLayout.LayoutParams(-1, -2));
        }

        ly[0] = h.overlays().showSheet(box, true, new Runnable() { @Override public void run() {
            closed[0] = true;
            if (dirty[0] && edited[0] != null && !edited[0].equals(original[0])) {
                Dialogs.confirm(h, "Изменения не сохранены", "Правки «" + f.getName() + "» будут потеряны.", "Вернуться к правке", false,
                    new Runnable() { @Override public void run() { openInternal(h, j, f, onChanged, edited[0], true); } });
            }
        }});

        if (restoreText == null && !startInEdit) {
            new Thread(new Runnable() { @Override public void run() {
                String text;
                try { text = read(f); } catch (final IOException e) {
                    j.main.post(new Runnable() { @Override public void run() { h.overlays().dismiss(ly[0]); } });
                    h.overlays().toast("Не удалось прочитать файл: " + e.getMessage(), OverlayHost.ERR);
                    return;
                }
                j.main.post(new Runnable() { @Override public void run() {
                    if (closed[0]) return;
                    original[0] = text;
                    edited[0] = text;
                    showBody(h, f, bodyHolder, text, false, null, closeGuard, edited);
                    syncButtons.run();
                }});
            }}, "jv-textread").start();
        }
    }

    /** Builds the view/edit body inside bodyHolder. onEdited fires on each user keystroke (edit mode). */
    private static void showBody(Host h, File f, LinearLayout bodyHolder, String text, boolean edit, final Runnable onEdited, Runnable onClose, final String[] editedOut) {
        Context c = h.context();
        bodyHolder.removeAllViews();
        if (edit) {
            final EditText e = new EditText(c);
            e.setBackground(null);
            e.setText(text);
            e.setTextColor(Ui.P.text);
            e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f);
            e.setTypeface(Ui.mono);
            e.setLineSpacing(0, 1.15f);
            e.setIncludeFontPadding(false);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            e.setMinLines(6);
            e.setMaxLines(20);
            e.setGravity(Gravity.TOP | Gravity.START);
            e.setHighlightColor(Ui.P.accentSoft);
            e.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(8));
            try { if (android.os.Build.VERSION.SDK_INT >= 29) e.setTextCursorDrawable(Ui.cursor()); } catch (Throwable ignored) { }
            e.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
                @Override public void afterTextChanged(android.text.Editable s) { editedOut[0] = s.toString(); if (onEdited != null) onEdited.run(); }
            });
            ScrollView sv = new ScrollView(c);
            sv.addView(e, new ViewGroup.LayoutParams(-1, -2));
            bodyHolder.addView(sv, new LinearLayout.LayoutParams(-1, (int) (c.getResources().getDisplayMetrics().heightPixels * 0.42f)));
            e.requestFocus();
        } else {
            TextView t = Ui.text(c, prettyIfJson(f.getName(), text), Ui.MONO, Ui.P.text);
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f);
            t.setLineSpacing(0, 1.15f);
            t.setTextIsSelectable(true);
            ScrollView sv = new ScrollView(c);
            sv.addView(t, new ViewGroup.LayoutParams(-1, -2));
            bodyHolder.addView(sv, new LinearLayout.LayoutParams(-1, (int) (c.getResources().getDisplayMetrics().heightPixels * 0.42f)));
        }
        LinearLayout foot = Ui.row(c);
        foot.setGravity(Gravity.END);
        foot.setPadding(0, Ui.dp(8), 0, Ui.dp(12));
        foot.addView(Ui.button(c, "Закрыть", R.drawable.ic_close, Ui.BTN_GHOST, new Runnable() { @Override public void run() { onClose.run(); } }));
        bodyHolder.addView(foot);
    }

    private static void save(final Host h, Jarvis j, final File f, final LinearLayout bodyHolder, final String[] original, final Runnable onSaved) {
        ScrollView sv = (ScrollView) bodyHolder.getChildAt(0);
        View cv = sv == null ? null : sv.getChildAt(0);
        if (!(cv instanceof EditText)) return;
        final String text = ((EditText) cv).getText().toString();
        final Dialogs.ProgressController pc = Dialogs.progress(h, "Сохранение", f.getName(), null);
        new Thread(new Runnable() { @Override public void run() {
            try {
                File tmp = new File(f.getParentFile(), "." + f.getName() + ".tmp");
                try (FileOutputStream os = new FileOutputStream(tmp)) { os.write(text.getBytes(StandardCharsets.UTF_8)); os.getFD().sync(); }
                if (!tmp.renameTo(f)) throw new IOException("rename не сработал");
                original[0] = text;
                j.main.post(new Runnable() { @Override public void run() { pc.close(); h.overlays().toast("Сохранено: " + f.getName(), OverlayHost.OK); onSaved.run(); } });
            } catch (final IOException e) {
                j.main.post(new Runnable() { @Override public void run() { pc.close(); h.overlays().toast("Не удалось сохранить: " + e.getMessage(), OverlayHost.ERR); } });
            }
        }}, "jv-textsave").start();
    }

    private static String read(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream((int) Math.min(f.length() + 16, MAX_BYTES));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (bo.size() > MAX_BYTES) throw new IOException("больше 1 МБ");
                bo.write(buf, 0, n);
            }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String prettyIfJson(String name, String text) {
        String n = name.toLowerCase(java.util.Locale.US);
        if (!n.endsWith(".json") || text.isEmpty()) return text;
        try { return new JSONObject(text).toString(2); } catch (Throwable ignored) { return text; }
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
