package app.jarvis.ui.chat;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Screen;
import app.jarvis.ui.Ui;

/**
 * Paperclip choice: "Файлы" (document picker) or "Галерея" (photo picker on Android 13+, the media store before).
 * A long press on the paperclip repeats the last choice without the sheet.
 */
public final class AttachSheet {
    public static final int FILES = 0, GALLERY = 1;
    private static final String PREFS = "chat_attach", KEY_LAST = "last";

    private AttachSheet() { }

    public static void open(final Host host, final Screen requester, final int reqFiles, final int reqGallery) {
        Context c = host.context();
        LinearLayout box = Ui.column(c);
        final OverlayHost.Layer[] layer = new OverlayHost.Layer[1];
        box.addView(tile(c, R.drawable.ic_folder, "Файлы", "Документы и любые файлы", new Runnable() {
            @Override public void run() { host.overlays().dismiss(layer[0]); pick(host, requester, FILES, reqFiles, reqGallery); }
        }), Ui.lpm(-1, -2, Ui.dp(16), 0, Ui.dp(16), Ui.dp(10)));
        box.addView(tile(c, R.drawable.ic_image, "Галерея", "Фото и видео", new Runnable() {
            @Override public void run() { host.overlays().dismiss(layer[0]); pick(host, requester, GALLERY, reqFiles, reqGallery); }
        }), Ui.lpm(-1, -2, Ui.dp(16), 0, Ui.dp(16), Ui.dp(12)));
        layer[0] = Dialogs.sheet(host, "Прикрепить", box, false);
    }

    /** Long press: the last choice without the sheet; with no history yet, the sheet. */
    public static void openLast(Host host, Screen requester, int reqFiles, int reqGallery) {
        int last = lastChoice(host.context());
        if (last < 0) open(host, requester, reqFiles, reqGallery);
        else pick(host, requester, last, reqFiles, reqGallery);
    }

    private static void pick(Host host, Screen requester, int kind, int reqFiles, int reqGallery) {
        remember(host.context(), kind);
        if (kind == FILES) {
            host.pickDocument(requester, reqFiles, new String[]{"*/*"}, false);
            return;
        }
        Intent i;
        if (Build.VERSION.SDK_INT >= 33) {
            i = new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
        } else {
            i = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).setType("image/*");
        }
        host.startActivityForResult(requester, i, reqGallery);
    }

    private static View tile(Context c, int icon, String title, String hint, final Runnable onTap) {
        LinearLayout row = Ui.row(c);
        row.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
        row.setBackground(Ui.glassPressable(18));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onTap.run(); } });
        ImageView ic = Ui.icon(c, icon, 26, Ui.P.accent);
        row.addView(ic, Ui.lpm(Ui.dp(26), Ui.dp(26), 0, 0, Ui.dp(14), 0));
        LinearLayout tx = Ui.column(c);
        TextView t = Ui.text(c, title, Ui.BODY, Ui.P.text);
        t.setTypeface(Ui.medium);
        tx.addView(t);
        TextView h = Ui.text(c, hint, Ui.CAPTION, Ui.P.text2);
        tx.addView(h, Ui.lpm(-2, -2, 0, Ui.dp(2), 0, 0));
        row.addView(tx, Ui.lpw(1f));
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private static int lastChoice(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.getInt(KEY_LAST, -1);
    }

    private static void remember(Context c, int kind) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_LAST, kind).apply();
    }
}
