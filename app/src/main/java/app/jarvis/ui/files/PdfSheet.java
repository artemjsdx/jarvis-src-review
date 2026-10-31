package app.jarvis.ui.files;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;
import java.io.File;
import java.io.IOException;

/**
 * PDF viewer on android.graphics.pdf.PdfRenderer. One page is rendered at a time, off the UI thread; the renderer and
 * its file descriptor are opened per render and closed right after, so nothing stays open when the sheet is dismissed.
 */
public final class PdfSheet {
    private PdfSheet() { }

    private static final class Pages {
        int count;
        int shown = -1;
        int generation;
    }

    public static void open(final Host h, final File f) {
        final Context c = h.context();
        final Handler main = new Handler(Looper.getMainLooper());
        final LinearLayout box = Ui.column(c);
        final TextView caption = Ui.text(c, "Открываю…", Ui.CAPTION, Ui.P.text2);
        caption.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(8));
        final ImageView page = new ImageView(c);
        page.setAdjustViewBounds(true);
        page.setScaleType(ImageView.ScaleType.FIT_CENTER);
        page.setBackgroundColor(Color.WHITE);
        final TextView status = Ui.text(c, "", Ui.CAPTION, Ui.P.text3);
        status.setPadding(Ui.dp(4), Ui.dp(8), Ui.dp(4), 0);

        LinearLayout nav = Ui.row(c);
        final Pages st = new Pages();
        final View prev = Ui.button(c, "Назад", R.drawable.ic_chevron_left, Ui.BTN_GHOST, null);
        final View next = Ui.button(c, "Дальше", R.drawable.ic_chevron_right, Ui.BTN_GHOST, null);
        nav.addView(prev);
        nav.addView(Ui.space(c, 1, 1), Ui.lpw(1f));
        nav.addView(next);

        final ScrollView scroll = new ScrollView(c);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        box.addView(caption);
        box.addView(scroll, Ui.lp(-1, Ui.dp(520)));
        box.addView(status);
        box.addView(nav, Ui.lpm(-1, -2, 0, 6, 0, 0));

        app.jarvis.ui.Dialogs.sheet(h, f.getName(), box, false);
        final int widthPx = c.getResources().getDisplayMetrics().widthPixels - Ui.dp(48);

        final Runnable show = new Runnable() { @Override public void run() {
            final int want = st.shown;
            final int gen = ++st.generation;
            status.setText("Страница " + (want + 1) + " из " + st.count);
            prev.setEnabled(want > 0);
            next.setEnabled(want < st.count - 1);
            prev.setAlpha(want > 0 ? 1f : 0.4f);
            next.setAlpha(want < st.count - 1 ? 1f : 0.4f);
            new Thread(new Runnable() { @Override public void run() {
                Bitmap bm = null;
                String err = null;
                try {
                    bm = render(f, want, widthPx);
                } catch (IOException | RuntimeException e) {
                    err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                final Bitmap out = bm;
                final String msg = err;
                main.post(new Runnable() { @Override public void run() {
                    if (gen != st.generation) return;
                    if (out == null) { status.setText("Не удалось открыть страницу: " + msg); return; }
                    page.setImageBitmap(out);
                    caption.setText(f.getName());
                }});
            } }, "jv-pdf-page").start();
        } };

        prev.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (st.shown > 0) { st.shown--; show.run(); } } });
        next.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (st.shown < st.count - 1) { st.shown++; show.run(); } } });

        new Thread(new Runnable() { @Override public void run() {
            int count = 0;
            String err = null;
            try {
                count = pageCount(f);
            } catch (IOException | RuntimeException e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final int n = count;
            final String msg = err;
            main.post(new Runnable() { @Override public void run() {
                if (msg != null || n <= 0) {
                    caption.setText("Не удалось открыть PDF" + (msg == null ? "" : ": " + msg));
                    return;
                }
                st.count = n;
                st.shown = 0;
                show.run();
            }});
        } }, "jv-pdf-open").start();
    }

    public static int pageCount(File f) throws IOException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer r = new PdfRenderer(pfd)) {
            return r.getPageCount();
        }
    }

    public static Bitmap render(File f, int index, int widthPx) throws IOException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer r = new PdfRenderer(pfd)) {
            PdfRenderer.Page p = r.openPage(index);
            try {
                int w = Math.max(200, widthPx);
                int hPx = Math.max(1, Math.round(w * (float) p.getHeight() / p.getWidth()));
                Bitmap bm = Bitmap.createBitmap(w, hPx, Bitmap.Config.ARGB_8888);
                bm.eraseColor(Color.WHITE);
                p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bm;
            } finally {
                p.close();
            }
        }
    }
}
