package app.jarvis.ui.chat.drawer;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.core.chat.SearchHit;
import app.jarvis.core.chat.SearchQuery;
import app.jarvis.ui.Ui;
import java.util.List;

/** One message found by chat search: author, time and the snippet with the matched words in bold. */
final class SearchHitRow {
    private SearchHitRow() { }

    static View build(Context c, SearchHit hit, List<String> terms, Runnable onOpen) {
        LinearLayout row = Ui.column(c);
        row.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8));
        row.setBackground(Ui.rippleOn(new ColorDrawable(0), 14));

        TextView meta = Ui.text(c, ("user".equals(hit.role) ? "Вы" : "Джарвис") + " · " + SessionRow.timeLabel(hit.ts), Ui.CAPTION, Ui.P.text3);
        meta.setSingleLine(true);
        row.addView(meta, Ui.lpm(-1, -2, 0, 0, 0, 2));

        TextView body = Ui.text(c, hit.snippet, Ui.CAPTION, Ui.P.text);
        body.setText(bold(hit.snippet, terms));
        body.setMaxLines(3);
        body.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(body, Ui.lpm(-1, -2, 0, 0, 0, 0));

        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onOpen.run(); } });
        return row;
    }

    /** Folding keeps the length, so the offsets of the folded text are valid in the snippet. */
    static SpannableString bold(String snippet, List<String> terms) {
        SpannableString s = new SpannableString(snippet);
        String folded = SearchQuery.fold(snippet);
        for (String t : terms) {
            int i = folded.indexOf(t);
            while (i >= 0) {
                s.setSpan(new StyleSpan(Typeface.BOLD), i, i + t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = folded.indexOf(t, i + t.length());
            }
        }
        return s;
    }
}
