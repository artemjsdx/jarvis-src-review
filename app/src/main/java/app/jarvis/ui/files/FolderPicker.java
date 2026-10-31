package app.jarvis.ui.files;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ListView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.tools.Sandbox;
import app.jarvis.ui.Host;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Bottom sheet: pick a sandbox folder (breadcrumbs + subfolder list + "Сюда" button); used by move/copy.
 * The list rebuilds in place while navigating; one overlay layer for the whole session.
 */
public final class FolderPicker {
    private FolderPicker() { }

    public interface Listener { void onPicked(File dir); }

    public static void show(final Host h, String title, final Sandbox sb, File start, final Listener onPick) {
        final Context c = h.context();
        final LinearLayout box = Ui.column(c);
        box.addView(grip(c));
        box.addView(Ui.text(c, title, Ui.TITLE), Ui.lpm(-2, -2, 20, 6, 20, 6));
        final OverlayHost.Layer[] ly = new OverlayHost.Layer[1];
        final File[] cwd = {start == null ? sb.root : start};
        final FolderAdapter adapter = new FolderAdapter(c, sb, cwd, new Runnable() {
            @Override public void run() { crumbRow(box, sb, cwd[0]); pathLine(box, sb, cwd[0]); }
        });

        final LinearLayout crumbHolder = Ui.column(c);
        crumbHolder.setTag("crumbs");
        box.addView(crumbHolder, Ui.lpm(-1, -2, 12, 0, 12, 0));

        final ListView list = new ListView(c);
        list.setDivider(Ui.rect(Ui.P.divider, 0));
        list.setDividerHeight(Math.max(1, Ui.dp(0.75f)));
        list.setAdapter(adapter);
        int listH = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.40f);
        box.addView(list, new LinearLayout.LayoutParams(-1, listH));

        LinearLayout bottom = Ui.row(c);
        bottom.setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(16), Ui.dp(12));
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        TextView path = Ui.text(c, "", Ui.CAPTION, Ui.P.text3);
        path.setTag("path");
        FileKit.ellipsize(path);
        bottom.addView(path, Ui.lpw(1f));
        bottom.addView(Ui.button(c, "Сюда", R.drawable.ic_check, Ui.BTN_PRIMARY, new Runnable() {
            @Override public void run() { h.overlays().dismiss(ly[0]); onPick.onPicked(cwd[0]); }
        }));
        box.addView(bottom);

        ly[0] = h.overlays().showSheet(box, true, null);
        crumbRow(box, sb, cwd[0]);
        pathLine(box, sb, cwd[0]);
    }

    private static void crumbRow(LinearLayout box, final Sandbox sb, final File dir) {
        LinearLayout holder = (LinearLayout) box.findViewWithTag("crumbs");
        holder.removeAllViews();
        List<File> chain = new ArrayList<>();
        for (File p = dir; p != null && (p.equals(sb.root) || p.getPath().startsWith(sb.root.getPath() + File.separator)); p = p.getParentFile()) {
            chain.add(p);
        }
        java.util.Collections.reverse(chain);
        LinearLayout row = Ui.row(box.getContext());
        Context c = box.getContext();
        for (int i = 0; i < chain.size(); i++) {
            final File f = chain.get(i);
            boolean last = i == chain.size() - 1;
            if (i > 0) row.addView(Ui.text(c, " / ", Ui.CAPTION, Ui.P.text3));
            TextView t = Ui.text(c, i == 0 ? "песочница" : f.getName(), Ui.CAPTION, last ? Ui.P.accent : Ui.P.text2);
            t.setTypeface(Ui.medium);
            FileKit.ellipsize(t);
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { jump(box, sb, f); } });
            row.addView(t);
        }
        HorizontalScrollView hv = new HorizontalScrollView(c);
        hv.setHorizontalScrollBarEnabled(false);
        hv.addView(row, new ViewGroup.LayoutParams(-2, -2));
        holder.addView(hv, new LinearLayout.LayoutParams(-1, -2));
        hv.post(new Runnable() { @Override public void run() { hv.fullScroll(View.FOCUS_RIGHT); } });
    }

    /** Called from breadcrumb taps: the adapter reloads and both headers rebuild. */
    private static void jump(LinearLayout box, Sandbox sb, File dir) {
        // the adapter is reachable through the ListView child
        ListView list = (ListView) ((ViewGroup) box.getChildAt(3));
        ((FolderAdapter) list.getAdapter()).enter(dir);
    }

    private static void pathLine(LinearLayout box, Sandbox sb, File dir) {
        TextView path = (TextView) box.findViewWithTag("path");
        String rel = sb.rel(dir);
        path.setText(rel.equals("/") ? "Папка: песочница" : "Папка: " + rel);
    }

    private static View grip(Context c) {
        FrameLayout f = new FrameLayout(c);
        View g = new View(c);
        g.setBackground(Ui.rect(Ui.P.strokeHi, 3));
        f.addView(g, new FrameLayout.LayoutParams(Ui.dp(36), Ui.dp(4), Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(20)));
        return f;
    }

    /** Rows: ".." (when not at root), then the subfolders of the current folder. Folder lists are short: no recycling. */
    private static final class FolderAdapter extends BaseAdapter {
        private static final int UP = 0, DIR = 1;
        private final Context c;
        private final Sandbox sb;
        private final File[] cwd;
        private final Runnable onChange;
        private final List<File> dirs = new ArrayList<>();

        FolderAdapter(Context c, Sandbox sb, File[] cwd, Runnable onChange) {
            this.c = c; this.sb = sb; this.cwd = cwd; this.onChange = onChange;
            load();
        }

        void load() {
            dirs.clear();
            File[] kids = cwd[0].listFiles();
            if (kids != null) {
                for (File k : kids) if (k.isDirectory() && !k.getName().startsWith(".")) dirs.add(k);
                Collections.sort(dirs, new Comparator<File>() { @Override public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); } });
            }
            notifyDataSetChanged();
        }

        void enter(File dir) { cwd[0] = dir; load(); onChange.run(); }

        private boolean hasUp() { return !cwd[0].equals(sb.root); }

        @Override public int getCount() { return dirs.size() + (hasUp() ? 1 : 0); }
        @Override public int getViewTypeCount() { return 1; }
        @Override public Object getItem(int position) { return position; }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            if (position == 0 && hasUp()) {
                return Widgets.row(c, R.drawable.ic_chevron_left, "На уровень выше", null, null, new Runnable() {
                    @Override public void run() { enter(cwd[0].getParentFile()); }
                });
            }
            final File d = dirs.get(hasUp() ? position - 1 : position);
            File[] n = d.listFiles();
            String sub = FileKit.entryCount(n == null ? 0 : n.length);
            return Widgets.row(c, R.drawable.ic_folder, d.getName(), sub, null, new Runnable() {
                @Override public void run() { enter(d); }
            });
        }
    }
}
