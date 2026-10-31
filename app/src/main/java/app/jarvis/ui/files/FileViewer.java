package app.jarvis.ui.files;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.util.Mime;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * Opens a file the way it deserves: audio → AudioSheet, text up to 1 MB → TextEditorSheet, image → a scaled-down
 * preview (inSampleSize, never a full-size decode), everything else → details (type, size, dates, sha-256 on demand)
 * with share/delete. Used by the Files screen; callers get onChanged() after delete/rename-able operations.
 */
public final class FileViewer {
    private FileViewer() { }

    public static void open(Host h, Jarvis j, File f, Runnable onChanged) {
        if (f == null || !f.isFile()) { h.overlays().toast("Файл не найден", OverlayHost.ERR); return; }
        String mime = Mime.guess(f.getName());
        if (mime.startsWith("audio/")) { AudioSheet.open(h, j, f); return; }
        if (mime.startsWith("image/")) { image(h, j, f, onChanged); return; }
        if (mime.equals("application/pdf")) { PdfSheet.open(h, f); return; }
        if (Mime.isText(f.getName()) && f.length() <= TextEditorSheet.MAX_BYTES) {
            TextEditorSheet.open(h, j, f, onChanged);
            return;
        }
        details(h, j, f, onChanged);
    }

    // ------------------------------------------------------------------------------------------------ image preview
    private static void image(final Host h, final Jarvis j, final File f, final Runnable onChanged) {
        Context c = h.context();
        LinearLayout box = Ui.column(c);
        box.addView(grip(c));
        TextView title = Ui.text(c, f.getName(), Ui.TITLE);
        title.setPadding(Ui.dp(20), Ui.dp(6), Ui.dp(20), Ui.dp(6));
        FileKit.ellipsize(title);
        box.addView(title);

        final FrameLayout holder = new FrameLayout(c);
        holder.setMinimumHeight(Ui.dp(180));
        Widgets.Spinner sp = Widgets.spinner(c, 26);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(Ui.dp(26), Ui.dp(26), Gravity.CENTER);
        holder.addView(sp, slp);
        box.addView(holder, Ui.lpm(-1, -2, 14, 0, 14, 0));
        final TextView cap = Ui.text(c, "Декодирование…", Ui.CAPTION, Ui.P.text3);
        cap.setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(20), Ui.dp(4));
        box.addView(cap);

        LinearLayout actions = Ui.row(c);
        actions.setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(12));
        actions.addView(Ui.button(c, "Поделиться", R.drawable.ic_share, Ui.BTN_GLASS, new Runnable() { @Override public void run() {
            h.shareFile(f, Mime.guess(f.getName()));
        }}), Ui.lpw(1f));
        actions.addView(Ui.button(c, "Подробнее", 0, Ui.BTN_GHOST, new Runnable() { @Override public void run() { details(h, j, f, onChanged); }}));
        box.addView(actions);
        h.overlays().showSheet(box, true, null);

        final int target = 1024;
        new Thread(new Runnable() { @Override public void run() {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            final int w = Math.max(1, o.outWidth), hh = Math.max(1, o.outHeight);
            int sample = 1;
            while (Math.max(w, hh) / (sample * 2) >= target) sample *= 2;
            final int sampleF = sample;
            final BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            final Bitmap b = BitmapFactory.decodeFile(f.getPath(), o2);
            j.main.post(new Runnable() { @Override public void run() {
                holder.removeAllViews();
                if (b == null) {
                    cap.setText("Не удалось открыть изображение: формат не поддерживается.");
                    return;
                }
                ImageView iv = new ImageView(h.context());
                iv.setImageBitmap(b);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                holder.addView(iv, new FrameLayout.LayoutParams(-1, Ui.dp(320)));
                cap.setText(w + "×" + hh + (sampleF > 1 ? " → показано уменьшенно (1/" + sampleF + ")" : ""));
            }});
        }}, "jv-img").start();
    }

    // ------------------------------------------------------------------------------------------------------ details
    private static void details(final Host h, final Jarvis j, final File f, final Runnable onChanged) {
        Context c = h.context();
        LinearLayout box = Ui.column(c);
        box.addView(grip(c));
        TextView title = Ui.text(c, f.getName(), Ui.TITLE);
        title.setPadding(Ui.dp(20), Ui.dp(6), Ui.dp(20), Ui.dp(6));
        FileKit.ellipsize(title);
        box.addView(title);

        LinearLayout card = Widgets.card(c);
        card.setPadding(Ui.dp(4), Ui.dp(0), Ui.dp(4), Ui.dp(0));
        card.addView(Widgets.row(c, R.drawable.ic_info, "Тип", FileKit.typeLabel(f), null, null));
        Widgets.divide(card);
        card.addView(Widgets.row(c, 0, "Размер", FileKit.size(f.length()), null, null));
        Widgets.divide(card);
        card.addView(Widgets.row(c, 0, "Изменён", FileKit.date(f.lastModified()), null, null));
        Widgets.divide(card);
        TextView pathVal = Ui.text(c, j.sandbox.rel(f), Ui.MONO, Ui.P.text2);
        pathVal.setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(14), Ui.dp(10));
        card.addView(Ui.text(c, "Путь", Ui.CAPTION, Ui.P.text3), Ui.lpm(-2, -2, 16, 10, 0, 0));
        pathVal.setTextIsSelectable(true);
        card.addView(pathVal, Ui.lpm(-2, -2, 16, 0, 0, 10));
        box.addView(card, Ui.lpm(-1, -2, 14, 0, 14, 0));

        final LinearLayout shaCard = Widgets.card(c);
        shaCard.setPadding(Ui.dp(4), Ui.dp(0), Ui.dp(4), Ui.dp(0));
        shaCard.addView(Widgets.row(c, R.drawable.ic_key, "SHA-256", "Посчитать контрольную сумму файла", null, new Runnable() {
            @Override public void run() { computeSha(h, j, f, shaCard); }
        }));
        box.addView(shaCard, Ui.lpm(-1, -2, 14, 10, 14, 0));

        LinearLayout actions = Ui.row(c);
        actions.setPadding(Ui.dp(14), Ui.dp(4), Ui.dp(14), Ui.dp(14));
        actions.addView(Ui.button(c, "Поделиться", R.drawable.ic_share, Ui.BTN_GLASS, new Runnable() { @Override public void run() {
            h.shareFile(f, Mime.guess(f.getName()));
        }}), Ui.lpw(1f));
        actions.addView(Ui.button(c, "Удалить", R.drawable.ic_trash, Ui.BTN_DANGER, new Runnable() { @Override public void run() {
            confirmDelete(h, j, f, onChanged);
        }}), Ui.lpm(-2, -2, 8, 0, 0, 0));
        box.addView(actions);
        h.overlays().showSheet(box, true, null);
    }

    private static void computeSha(final Host h, Jarvis j, final File f, final LinearLayout shaCard) {
        final Widgets.Spinner sp = Widgets.spinner(h.context(), 16);
        LinearLayout row = Ui.row(h.context());
        row.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(14), Ui.dp(12));
        row.addView(sp);
        row.addView(Ui.text(h.context(), "Считаю…", Ui.CAPTION, Ui.P.text3), Ui.lpm(-2, -2, 10, 0, 0, 0));
        shaCard.addView(row);
        new Thread(new Runnable() { @Override public void run() {
            String sha;
            try { sha = sha256(f); } catch (final IOException e) {
                j.main.post(new Runnable() { @Override public void run() { row.setVisibility(View.GONE); } });
                h.overlays().toast("Не удалось посчитать SHA-256: " + e.getMessage(), OverlayHost.ERR);
                return;
            }
            final String s = sha;
            j.main.post(new Runnable() { @Override public void run() {
                row.setVisibility(View.GONE);
                TextView t = Ui.text(h.context(), s, Ui.MONO, Ui.P.text2);
                t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f);
                t.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(14), Ui.dp(6));
                t.setTextIsSelectable(true);
                shaCard.addView(t);
                LinearLayout cp = Ui.row(h.context());
                cp.setGravity(Gravity.END);
                cp.setPadding(0, 0, Ui.dp(14), Ui.dp(10));
                cp.addView(Ui.button(h.context(), "Скопировать", R.drawable.ic_copy, Ui.BTN_GHOST, new Runnable() { @Override public void run() {
                    h.copyToClipboard("SHA-256", s);
                    h.overlays().toast("Скопировано", OverlayHost.OK);
                }}));
                shaCard.addView(cp);
            }});
        }}, "jv-sha").start();
    }

    static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    public static void confirmDelete(final Host h, final Jarvis j, final File f, final Runnable onChanged) {
        boolean dir = f.isDirectory();
        File[] kids = dir ? f.listFiles() : null;
        int n = kids == null ? 0 : kids.length;
        String msg = dir
            ? "Папка «" + f.getName() + "» и всё её содержимое (" + FileKit.entryCount(n) + ") будут удалены безвозвратно."
            : "Файл «" + f.getName() + "» будет удалён безвозвратно.";
        Dialogs.confirm(h, "Удалить?", msg, "Удалить", true, new Runnable() { @Override public void run() {
            new Thread(new Runnable() { @Override public void run() {
                SandboxImporter.deleteTree(f);
                j.main.post(new Runnable() { @Override public void run() {
                    h.overlays().toast("Удалено: " + f.getName(), OverlayHost.OK);
                    if (onChanged != null) onChanged.run();
                }});
            }}, "jv-del").start();
        }});
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
