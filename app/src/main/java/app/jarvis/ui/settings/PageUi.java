package app.jarvis.ui.settings;

import android.content.Context;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.ui.Ui;
import java.util.Locale;

/** Small builders shared by the settings pages (field that saves on Done/focus loss, captions, formatting). */
final class PageUi {
    private PageUi() { }

    interface TextListener { void on(String s); }

    /** Single-line field that applies on IME action Done and on focus loss. */
    static EditText saveField(Context c, String hint, String initial, final TextListener onSave) {
        final EditText e = app.jarvis.ui.Widgets.field(c, hint, initial, false, false);
        e.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) { onSave.on(e.getText().toString()); hideKeyboard(c, e); return true; }
                return false;
            }
        });
        e.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) { if (!has) onSave.on(e.getText().toString()); }
        });
        return e;
    }

    static void hideKeyboard(Context c, View v) {
        try {
            InputMethodManager im = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Throwable ignored) { }
    }

    static TextView caption(Context c, String s) { return Ui.text(c, s, Ui.CAPTION, Ui.P.text2); }

    static TextView note(Context c, String s) { return Ui.text(c, s, Ui.CAPTION, Ui.P.text3); }

    /** Row of buttons that share the width equally. */
    static LinearLayout buttonRow(Context c, View... bs) {
        LinearLayout r = Ui.row(c);
        for (View b : bs) {
            r.addView(b, Ui.lpw(1f));
        }
        return r;
    }

    /** 1234567 -> "1 234 567" */
    static String group(long n) {
        return String.format(Locale.ROOT, "%,d", n).replace(',', ' ');
    }

    static String humanSize(long b) {
        if (b < 1024) return b + " Б";
        double v = b;
        for (String u : new String[]{"КБ", "МБ", "ГБ", "ТБ"}) {
            v /= 1024;
            if (v < 1024 || u.equals("ТБ"))
                return v >= 10 ? String.format(Locale.US, "%.0f %s", v, u) : String.format(Locale.US, "%.1f %s", v, u);
        }
        return String.valueOf(b);
    }
}
