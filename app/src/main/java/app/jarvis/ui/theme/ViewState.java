package app.jarvis.ui.theme;

import android.view.View;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.List;

/**
 * Carries user-visible state (field text, scroll offsets) from one view tree into a freshly built one. Both trees come from
 * the same builder, so the stateful views line up in the same depth-first order; if they do not, nothing is copied.
 */
public final class ViewState {
    private ViewState() { }

    public static void copy(View from, View to) {
        List<View> a = new ArrayList<>(), b = new ArrayList<>();
        collect(from, a);
        collect(to, b);
        if (a.size() != b.size()) return;
        for (int i = 0; i < a.size(); i++) {
            View x = a.get(i), y = b.get(i);
            if (x.getClass() != y.getClass()) return;
            if (x instanceof EditText) copyText((EditText) x, (EditText) y);
            else if (x instanceof ScrollView) restoreScroll((ScrollView) x, (ScrollView) y);
            else if (x instanceof AbsListView) restoreList((AbsListView) x, (AbsListView) y);
        }
    }

    private static void collect(View v, List<View> out) {
        if (v instanceof EditText || v instanceof ScrollView || v instanceof AbsListView) out.add(v);
        if (!(v instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup g = (android.view.ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
    }

    private static void copyText(EditText from, EditText to) {
        CharSequence t = from.getText();
        if (t == null || t.toString().contentEquals(to.getText())) return;
        to.setText(t);
        to.setSelection(Math.min(from.getSelectionEnd() < 0 ? t.length() : from.getSelectionEnd(), t.length()));
    }

    private static void restoreScroll(final ScrollView from, final ScrollView to) {
        final int y = from.getScrollY();
        to.post(new Runnable() { @Override public void run() { to.scrollTo(0, y); } });
    }

    private static void restoreList(final AbsListView from, final AbsListView to) {
        final int first = from.getFirstVisiblePosition();
        final View top = from.getChildAt(0);
        final int offset = top == null ? 0 : top.getTop();
        to.post(new Runnable() { @Override public void run() { to.setSelectionFromTop(first, offset); } });
    }
}
