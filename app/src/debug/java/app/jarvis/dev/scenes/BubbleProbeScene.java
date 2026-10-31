package app.jarvis.dev.scenes;

import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import app.jarvis.dev.PreviewHost;
import app.jarvis.ui.Ui;
import app.jarvis.ui.chat.ChatTextView;

/** One-shot diagnosis: how selectable text measures inside wrap-content bubbles (ListView vs plain, selectable vs not). */
public final class BubbleProbeScene {
    private static final String TEXT = "Джарвис, создай заметку: купить хлеб и молоко к ужину";

    public static void render() throws Exception {
        android.content.Context c = PreviewHost.ctx;
        LinearLayout root = Ui.column(c);

        root.addView(caption(c, "1 plain LinearLayout + plain TextView"));
        root.addView(bubblePlain(c, false));
        root.addView(caption(c, "2 plain LinearLayout + selectable"));
        root.addView(bubblePlain(c, true));
        root.addView(caption(c, "3 ListView + plain"));
        root.addView(listOf(c, false, false));
        root.addView(caption(c, "4 ListView + selectable"));
        root.addView(listOf(c, true, false));
        root.addView(caption(c, "5 ListView + selectable, selectable set AFTER text"));
        root.addView(listOf(c, true, true));
        PreviewHost.render(root, "bubble_probe", 0, 0);
    }

    private static TextView caption(android.content.Context c, String s) {
        TextView t = Ui.text(c, s, Ui.CAPTION, Ui.P.accent);
        t.setPadding(0, Ui.dp(8), 0, Ui.dp(4));
        return t;
    }

    private static View bubblePlain(android.content.Context c, boolean selectable) {
        LinearLayout row = Ui.column(c);
        row.setPadding(Ui.dp(12), 0, Ui.dp(64), 0);
        LinearLayout bubble = Ui.column(c);
        bubble.setBackground(Ui.rect(0x40B9A6FF, 18));
        bubble.setPadding(Ui.dp(12), Ui.dp(9), Ui.dp(12), Ui.dp(7));
        TextView t = selectable ? new ChatTextView(c) : Ui.text(c, "", Ui.BODY);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(Ui.P.text);
        t.setText(TEXT);
        bubble.addView(t, new LinearLayout.LayoutParams(-2, -2));
        row.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private static View listOf(android.content.Context c, final boolean selectable, final boolean selectAfterText) {
        ListView lv = new ListView(c);
        lv.setDivider(null);
        BaseAdapter a = new BaseAdapter() {
            @Override public int getCount() { return 2; }
            @Override public Object getItem(int position) { return null; }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row = Ui.column(c);
                row.setPadding(Ui.dp(12), Ui.dp(2), Ui.dp(64), Ui.dp(2));
                LinearLayout bubble = Ui.column(c);
                bubble.setBackground(Ui.rect(0x40B9A6FF, 18));
                bubble.setPadding(Ui.dp(12), Ui.dp(9), Ui.dp(12), Ui.dp(7));
                TextView t = selectable ? new ChatTextView(c) : Ui.text(c, "", Ui.BODY);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                t.setTextColor(Ui.P.text);
                t.setText(TEXT);
                if (selectAfterText && t instanceof ChatTextView) ((ChatTextView) t).setTextIsSelectable(true);
                bubble.addView(t, new LinearLayout.LayoutParams(-2, -2));
                row.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
                return row;
            }
        };
        lv.setAdapter(a);
        lv.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(120)));
        return lv;
    }
}
