package app.jarvis.ui.screens;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.net.Downloader;
import app.jarvis.core.tools.DownloadTool;
import app.jarvis.core.tools.Sandbox;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.Mime;
import app.jarvis.rt.Attachments;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.Screen;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import app.jarvis.ui.files.FileKit;
import app.jarvis.ui.files.FileViewer;
import app.jarvis.ui.files.FolderPicker;
import app.jarvis.ui.files.SandboxImporter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Browser of the agent's private sandbox. Root = cards for the main folders (with item counts and free space), other
 * folders = ListView (folders first, icon by type, size + modified). Actions: import picked documents into the current
 * folder (streaming, off the UI thread), new folder/text file, rename, delete, move/copy via folder chooser, copy path,
 * share. Tapping a file opens the right viewer (text editor / audio player / image / details).
 */
public class FilesScreen extends Screen {
    private static final int REQ_IMPORT = 5101;

    private File cwd;
    private int generation;
    private LinearLayout rootView;
    private LinearLayout crumbRow;
    private HorizontalScrollView crumbScroll;
    private FrameLayout content;
    private LinearLayout rootScroll;      // cards at sandbox root
    private LinearLayout rootCardsBox;    // card grid lives here
    private LinearLayout rootFilesBox;    // loose files at root
    private ListView list;
    private View emptyView;               // "Пустая папка": shown by apply(), never at the sandbox root
    private LinearLayout errorView;       // "Не удалось открыть папку" with retry
    private TextView loadingView;
    private FrameLayout refreshBtn;
    private ObjectAnimator refreshSpin;
    private boolean manualRefresh;
    private EntryAdapter adapter;
    private TextView freeSpace;
    private final List<File> entries = new ArrayList<>();

    public FilesScreen(Host host) {
        super(host);
        cwd = j.sandbox.root;
    }

    @Override protected View build() {
        LinearLayout root = Ui.column(ctx);
        rootView = root;

        // breadcrumb + up, in one bordered bar
        LinearLayout head = Ui.row(ctx);
        head.setBackground(Ui.glass(14));
        head.setPadding(Ui.dp(4), Ui.dp(2), Ui.dp(8), Ui.dp(2));
        final FrameLayout upBtn = Ui.iconButton(ctx, R.drawable.ic_chevron_left, 40, Ui.P.text, new Runnable() { @Override public void run() {
            if (!cwd.equals(j.sandbox.root)) enter(cwd.getParentFile());
        }});
        upBtn.setTag("up");
        head.addView(upBtn);
        crumbScroll = new HorizontalScrollView(ctx);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        crumbRow = Ui.row(ctx);
        crumbRow.setPadding(Ui.dp(6), Ui.dp(8), Ui.dp(6), Ui.dp(8));
        crumbScroll.addView(crumbRow);
        head.addView(crumbScroll, Ui.lpw(1f));
        root.addView(head, Ui.lpm(-1, -2, 10, 6, 10, 0));

        // toolbar: import / new folder / new text file / free space / refresh
        LinearLayout tools = Ui.row(ctx);
        tools.setPadding(Ui.dp(10), Ui.dp(4), Ui.dp(10), Ui.dp(4));
        tools.addView(Ui.iconButton(ctx, R.drawable.ic_upload, 42, Ui.P.text, new Runnable() { @Override public void run() {
            host.pickDocument(FilesScreen.this, REQ_IMPORT, new String[]{"*/*"}, true);
        }}), Ui.lpm(-2, -2, 0, 0, 4, 0));
        tools.addView(Ui.iconButton(ctx, R.drawable.ic_folder_plus, 42, Ui.P.text, new Runnable() { @Override public void run() { newFolder(); }}), Ui.lpm(-2, -2, 0, 0, 4, 0));
        tools.addView(Ui.iconButton(ctx, R.drawable.ic_file_plus, 42, Ui.P.text, new Runnable() { @Override public void run() { newTextFile(); }}), Ui.lpm(-2, -2, 0, 0, 4, 0));
        tools.addView(Ui.iconButton(ctx, R.drawable.ic_download, 42, Ui.P.text, new Runnable() { @Override public void run() { downloadByUrl(); }}), Ui.lpm(-2, -2, 0, 0, 4, 0));
        View space = Ui.space(ctx, 1, 1);
        tools.addView(space, Ui.lpw(1f));
        freeSpace = Ui.text(ctx, "", Ui.CAPTION, Ui.P.text3);
        tools.addView(freeSpace, Ui.lpm(-2, -2, 0, 0, 6, 0));
        refreshBtn = Ui.iconButton(ctx, R.drawable.ic_refresh, 42, Ui.P.text2, new Runnable() { @Override public void run() {
            manualRefresh = true;
            refresh();
        }});
        refreshBtn.setTag("refresh");
        tools.addView(refreshBtn);
        root.addView(tools);

        content = new FrameLayout(ctx);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        rootScroll = Ui.column(ctx);
        rootScroll.setPadding(Ui.dp(14), Ui.dp(4), Ui.dp(14), Ui.dp(12));
        content.addView(rootScroll, new FrameLayout.LayoutParams(-1, -1));

        list = new ListView(ctx);
        list.setDivider(Ui.rect(Ui.P.divider, 0));
        list.setDividerHeight(Math.max(1, Ui.dp(0.75f)));
        adapter = new EntryAdapter();
        list.setAdapter(adapter);
        LinearLayout empty = Widgets.empty(ctx, R.drawable.ic_folder_open, "Пустая папка",
            "Импортируйте файл кнопкой «Загрузить» или попросите агента что-то сюда скачать");
        LinearLayout failed = Widgets.empty(ctx, R.drawable.ic_folder_open, "Не удалось открыть папку",
            "Android не дал прочитать каталог. Проверьте, что он ещё существует, и повторите");
        failed.addView(Ui.button(ctx, "Повторить", R.drawable.ic_refresh, Ui.BTN_GLASS, new Runnable() { @Override public void run() { refresh(); }}),
            Ui.lpm(-2, -2, 0, 12, 0, 0));
        loadingView = Ui.text(ctx, "Читаю папку…", Ui.BODY, Ui.P.text3);
        loadingView.setGravity(Gravity.CENTER);
        content.addView(list, new FrameLayout.LayoutParams(-1, -1));
        content.addView(empty, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        content.addView(failed, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        content.addView(loadingView, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        emptyView = empty;                // visibility is managed in apply(): AdapterView.setEmptyView also showed it on top of the root cards
        errorView = failed;
        empty.setVisibility(View.GONE);
        failed.setVisibility(View.GONE);
        loadingView.setVisibility(View.GONE);
        return root;
    }

    @Override public String title() { return "Файлы"; }

    @Override public void onShow(String arg) {
        if (arg != null && !arg.isEmpty()) {
            try {
                File f = j.sandbox.resolve(arg);
                if (f.isDirectory()) cwd = f;
            } catch (IOException ignored) { }
        }
        refresh();
    }

    // ------------------------------------------------------------------------------------------------ data loading
    private void refresh() {
        final File dir = cwd;
        final int gen = ++generation;
        final boolean atRoot = dir.equals(j.sandbox.root);
        if (!atRoot && entries.isEmpty()) {
            loadingView.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);
            errorView.setVisibility(View.GONE);
        }
        setSpinning(manualRefresh);
        new Thread(new Runnable() { @Override public void run() {
            final List<File> found = new ArrayList<>();
            final long free = dir.getUsableSpace();
            boolean failed = false;
            if (dir.isDirectory()) {
                File[] kids = dir.listFiles();
                if (kids == null) failed = true;
                else {
                    Arrays.sort(kids, new Comparator<File>() { @Override public int compare(File a, File b) {
                        if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                        return a.getName().compareToIgnoreCase(b.getName());
                    }});
                    found.addAll(Arrays.asList(kids));
                }
            }
            final boolean unreadable = failed;
            final boolean vanished = !dir.isDirectory();
            j.main.post(new Runnable() { @Override public void run() { apply(gen, dir, found, free, vanished, unreadable); }});
        }}, "jv-files").start();
    }

    private void setSpinning(boolean on) {
        if (refreshBtn == null) return;
        if (!on || Ui.reduceMotion) {
            if (refreshSpin != null) refreshSpin.cancel();
            refreshSpin = null;
            refreshBtn.setRotation(0f);
            return;
        }
        if (refreshSpin != null && refreshSpin.isRunning()) return;
        refreshSpin = ObjectAnimator.ofFloat(refreshBtn, View.ROTATION, 0f, 360f);
        refreshSpin.setDuration(700);
        refreshSpin.setRepeatCount(ValueAnimator.INFINITE);
        refreshSpin.setInterpolator(new LinearInterpolator());
        refreshSpin.start();
    }

    private void apply(int gen, File dir, List<File> found, long free, boolean vanished, boolean unreadable) {
        if (gen != generation) return;                       // user moved on while we were listing
        if (vanished) {
            setSpinning(false);
            manualRefresh = false;
            host.overlays().toast("Папка исчезла: " + dir.getName(), 2);
            enter(dir.equals(j.sandbox.root) ? null : dir.getParentFile() == null ? j.sandbox.root : dir.getParentFile());
            return;
        }
        cwd = dir;
        entries.clear();
        entries.addAll(found);
        boolean atRoot = dir.equals(j.sandbox.root);
        loadingView.setVisibility(View.GONE);
        rootScroll.setVisibility(atRoot ? View.VISIBLE : View.GONE);
        list.setVisibility(!atRoot && !unreadable && !found.isEmpty() ? View.VISIBLE : View.GONE);
        emptyView.setVisibility(!atRoot && !unreadable && found.isEmpty() ? View.VISIBLE : View.GONE);
        errorView.setVisibility(!atRoot && unreadable ? View.VISIBLE : View.GONE);
        buildCrumbs();
        FrameLayout up = (FrameLayout) rootView.findViewWithTag("up");
        up.setVisibility(atRoot ? View.INVISIBLE : View.VISIBLE);
        freeSpace.setText(FileKit.size(free) + " свободно");
        if (atRoot) fillRoot(); else adapter.notifyDataSetChanged();
        setSpinning(false);
        if (manualRefresh) {
            manualRefresh = false;
            host.overlays().toast(unreadable ? "Не удалось прочитать папку" : "Обновлено: " + found.size(), unreadable ? 2 : 1);
        }
    }

    private void enter(File dir) {
        if (dir == null) dir = j.sandbox.root;
        cwd = dir;
        refresh();
    }

    // -------------------------------------------------------------------------------------------------- breadcrumbs
    private void buildCrumbs() {
        crumbRow.removeAllViews();
        List<File> chain = new ArrayList<>();
        for (File p = cwd; p != null && (p.equals(j.sandbox.root) || p.getPath().startsWith(j.sandbox.root.getPath() + File.separator)); p = p.getParentFile()) chain.add(p);
        java.util.Collections.reverse(chain);
        Context c = ctx;
        for (int i = 0; i < chain.size(); i++) {
            final File f = chain.get(i);
            boolean last = i == chain.size() - 1;
            if (i > 0) crumbRow.addView(Ui.text(c, "/", Ui.CAPTION, Ui.P.text3));
            TextView t = Ui.text(c, i == 0 ? "песочница" : f.getName(), Ui.CAPTION, last ? Ui.P.accent : Ui.P.text2);
            t.setTypeface(Ui.medium);
            FileKit.ellipsize(t);
            if (!last) {
                t.setClickable(true);
                t.setBackground(Ui.rippleOn(new android.graphics.drawable.ColorDrawable(0), 8));
                t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { enter(f); }});
                t.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
            }
            crumbRow.addView(t);
        }
        crumbScroll.post(new Runnable() { @Override public void run() { crumbScroll.fullScroll(View.FOCUS_RIGHT); } });
    }

    // --------------------------------------------------------------------------------------------------- root cards
    private void fillRoot() {
        rootScroll.removeAllViews();
        freeSpace.setText(FileKit.size(j.sandbox.root.getUsableSpace()) + " свободно");
        TextView loading = Ui.text(ctx, "Считаю папки…", Ui.CAPTION, Ui.P.text3);
        loading.setTag("loading");
        rootScroll.addView(loading, Ui.lpm(-2, -2, 6, Ui.dp(8), 0, 0));
        final int gen = generation;
        final Sandbox sb = j.sandbox;
        final File[] coreDirs = {sb.work, sb.inbox, sb.downloads, sb.music, sb.docs, sb.tmp, sb.bin};
        new Thread(new Runnable() { @Override public void run() {
            final long[] counts = new long[coreDirs.length];
            for (int i = 0; i < coreDirs.length; i++) {
                File[] k = coreDirs[i].listFiles();
                counts[i] = k == null ? 0 : k.length;
            }
            final List<File> loose = new ArrayList<>();
            File[] kids = sb.root.listFiles();
            if (kids != null) for (File k : kids) if (k.isFile()) loose.add(k);
            j.main.post(new Runnable() { @Override public void run() {
                if (gen != generation || !cwd.equals(j.sandbox.root)) return;
                buildRootCards(coreDirs, counts, loose);
            }});
        }}, "jv-rootcount").start();
    }

    private void buildRootCards(File[] dirs, long[] counts, List<File> loose) {
        View loading = rootScroll.findViewWithTag("loading");
        if (loading != null) rootScroll.removeView(loading);
        for (int i = 0; i < dirs.length; i += 2) {
            LinearLayout row = Ui.row(ctx);
            // top gravity: with center-vertical a child that has only a BOTTOM margin is laid out at (space-h)/2 - bottomMargin, i.e. shifted up
            // by half the margin and clipped by the row (the top ~15 px of every folder card was cut off)
            row.setGravity(Gravity.TOP);
            for (int k = i; k < Math.min(i + 2, dirs.length); k++) {
                final File d = dirs[k];
                LinearLayout card = Ui.column(ctx);
                card.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
                card.setBackground(Ui.glassPressable(18));
                card.setClickable(true);
                card.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { enter(d); }});
                Ui.pressScale(card);
                card.addView(Ui.icon(ctx, R.drawable.ic_folder, 22, Ui.P.accent));
                TextView n = Ui.text(ctx, d.getName(), Ui.BODY);
                n.setTypeface(Ui.medium);
                FileKit.ellipsize(n);
                card.addView(n, Ui.lpm(-2, -2, 0, 8, 0, 0));
                TextView cnt = Ui.text(ctx, FileKit.entryCount(counts[k]), Ui.CAPTION, Ui.P.text3);
                FileKit.ellipsize(cnt);
                card.addView(cnt, Ui.lpm(-2, -2, 0, 1, 0, 0));
                // weight 1 (not width 0): lpm(0, ...) has no weight, so the root folder cards were laid out 0 px wide and the root looked empty
                LinearLayout.LayoutParams clp = Ui.lpw(1f);
                clp.setMargins(0, 0, Ui.dp(10), Ui.dp(10));
                row.addView(card, clp);
            }
            if (i + 1 >= dirs.length) row.addView(Ui.space(ctx, 1, 1), Ui.lpw(1f));
            rootScroll.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }

        if (!loose.isEmpty()) {
            rootScroll.addView(Widgets.sectionTitle(ctx, "Файлы в корне"));
            for (int i = 0; i < Math.min(loose.size(), 40); i++) {
                final File f = loose.get(i);
                LinearLayout r = Widgets.row(ctx, FileKit.iconFor(f), f.getName(), FileKit.subtitle(f), null, new Runnable() {
                    @Override public void run() { FileViewer.open(host, j, f, listRefresher); }
                });
                r.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { actionSheet(f); return true; }});
                rootScroll.addView(r, new LinearLayout.LayoutParams(-1, -2));
            }
            if (loose.size() > 40) rootScroll.addView(Ui.text(ctx, "…и ещё " + (loose.size() - 40) + " — откройте их через агента или терминал", Ui.CAPTION, Ui.P.text3),
                Ui.lpm(-2, -2, 6, 4, 0, 0));
        }
    }

    // ---------------------------------------------------------------------------------------------------- list rows
    private final Runnable listRefresher = new Runnable() { @Override public void run() { refresh(); } };

    private final class EntryAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = convertView instanceof LinearLayout && convertView.getTag() instanceof Object[] ? (LinearLayout) convertView : makeRow();
            Object[] held = (Object[]) row.getTag();
            final TextView name = (TextView) held[0];
            final TextView sub = (TextView) held[1];
            final android.widget.ImageView icon = (android.widget.ImageView) held[2];
            final File f = entries.get(position);
            name.setText(f.getName());
            sub.setText(FileKit.subtitle(f));
            icon.setImageResource(FileKit.iconFor(f));
            icon.setColorFilter(f.isDirectory() ? Ui.P.accent : Ui.P.text2);
            row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                if (f.isDirectory()) enter(f); else FileViewer.open(host, j, f, listRefresher);
            }});
            row.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { actionSheet(f); return true; }});
            return row;
        }

        private LinearLayout makeRow() {
            LinearLayout row = Ui.row(ctx);
            row.setPadding(Ui.dp(14), Ui.dp(9), Ui.dp(8), Ui.dp(9));
            row.setBackground(Ui.rippleOn(new android.graphics.drawable.ColorDrawable(0), 12));
            FrameLayout badge = new FrameLayout(ctx);
            badge.setBackground(Ui.rect(Ui.P.field, 10));
            android.widget.ImageView icon = Ui.icon(ctx, R.drawable.ic_file, 20, Ui.P.text2);
            badge.addView(icon, new FrameLayout.LayoutParams(Ui.dp(20), Ui.dp(20), Gravity.CENTER));
            row.addView(badge, Ui.lpm(Ui.dp(36), Ui.dp(36), 0, 0, 12, 0));
            LinearLayout col = Ui.column(ctx);
            TextView name = Ui.text(ctx, "", Ui.BODY);
            name.setTypeface(Ui.medium);
            FileKit.ellipsize(name);
            col.addView(name);
            TextView sub = Ui.text(ctx, "", Ui.CAPTION, Ui.P.text3);
            FileKit.ellipsize(sub);
            col.addView(sub, Ui.lpm(-2, -2, 0, 2, 0, 0));
            row.addView(col, Ui.lpw(1f));
            row.addView(Ui.icon(ctx, R.drawable.ic_chevron_right, 16, Ui.P.text3), Ui.lpm(-2, -2, 6, 0, 0, 0));
            row.setTag(new Object[]{name, sub, icon});
            return row;
        }
    }

    // ------------------------------------------------------------------------------------------------------ actions
    private void newFolder() {
        Dialogs.prompt(host, "Новая папка", "Имя папки", "", false, false, "Создать", new Dialogs.StringListener() { @Override public void on(String s) {
            String name = s.trim();
            if (name.isEmpty() || name.contains("/")) { host.overlays().toast("Некорректное имя папки", 2); return; }
            File d = new File(cwd, name);
            if (d.exists()) { host.overlays().toast("Уже существует: " + name, 2); return; }
            if (!d.mkdirs()) { host.overlays().toast("Не удалось создать папку", 2); return; }
            j.journal.i(app.jarvis.core.log.Journal.Cat.FILES, "Создана папка " + j.sandbox.rel(d));
            refresh();
        }});
    }

    private void newTextFile() {
        Dialogs.prompt(host, "Новый текстовый файл", "Имя файла (например, заметка.md)", "", false, false, "Создать", new Dialogs.StringListener() { @Override public void on(String s) {
            String name = s.trim();
            if (name.isEmpty() || name.contains("/")) { host.overlays().toast("Некорректное имя файла", 2); return; }
            File f = Attachments.uniqueFile(cwd, name);
            try {
                if (!f.createNewFile()) { host.overlays().toast("Не удалось создать файл", 2); return; }
            } catch (IOException e) {
                host.overlays().toast("Не удалось создать файл: " + e.getMessage(), 2);
                return;
            }
            j.journal.i(app.jarvis.core.log.Journal.Cat.FILES, "Создан файл " + j.sandbox.rel(f));
            refresh();
            FileViewer.open(host, j, f, listRefresher);
        }});
    }

    private void downloadByUrl() {
        Dialogs.prompt(host, "Скачать по ссылке", "https://…", "", false, false, "Скачать", new Dialogs.StringListener() { @Override public void on(String s) {
            final String url = s.trim();
            String low = url.toLowerCase(Locale.ROOT);
            if (!(low.startsWith("http://") || low.startsWith("https://"))) { host.overlays().toast("Нужна ссылка http:// или https://", 2); return; }
            final File dest = Attachments.uniqueFile(j.sandbox.downloads, DownloadTool.fileNameFromUrl(url));
            final CancelToken cancel = new CancelToken();
            final Dialogs.ProgressController pc = Dialogs.progress(host, "Загрузка", dest.getName(), new Runnable() { @Override public void run() { cancel.cancel(); }});
            new Thread(new Runnable() { @Override public void run() {
                Downloader.Request rq = new Downloader.Request();
                rq.url = url;
                rq.dest = dest;
                rq.proxy = j.proxyRef[0] == null ? "" : j.proxyRef[0];
                try {
                    final Downloader.Result res = new Downloader(j.journal).download(rq, new Downloader.Progress() { @Override public void onProgress(final long done, final long total) {
                        j.main.post(new Runnable() { @Override public void run() {
                            pc.update(FileKit.size(done) + (total > 0 ? " из " + FileKit.size(total) : ""), total > 0 ? (float) done / total : -1);
                        }});
                    }}, cancel);
                    j.journal.i(app.jarvis.core.log.Journal.Cat.FILES, "Скачано: " + j.sandbox.rel(res.file) + " (" + res.bytes + " Б)");
                    j.main.post(new Runnable() { @Override public void run() {
                        pc.close();
                        host.overlays().toast("Скачано: " + dest.getName() + " · " + FileKit.size(res.bytes), 1);
                        refresh();
                    }});
                } catch (final Exception e) {
                    final String msg = cancel.isCancelled() ? "Загрузка отменена" : "Не удалось скачать: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    j.main.post(new Runnable() { @Override public void run() {
                        pc.close();
                        host.overlays().toast(msg, cancel.isCancelled() ? 0 : 2);
                    }});
                }
            }}, "jv-download").start();
        }});
    }

    private void actionSheet(final File f) {
        boolean dir = f.isDirectory();
        List<String> items = new ArrayList<>();
        final List<Integer> ops = new ArrayList<>();
        items.add(dir ? "Открыть папку" : "Открыть"); ops.add(0);
        items.add("Переименовать"); ops.add(1);
        items.add("Переместить в другую папку"); ops.add(2);
        items.add("Копировать в другую папку"); ops.add(3);
        items.add("Скопировать путь"); ops.add(4);
        if (!dir) { items.add("Поделиться"); ops.add(5); }
        items.add("Удалить"); ops.add(6);
        Dialogs.choose(host, f.getName(), items.toArray(new String[0]), -1, new Dialogs.IndexListener() { @Override public void on(int index) {
            if (index < 0 || index >= ops.size()) return;
            switch (ops.get(index)) {
                case 0: if (f.isDirectory()) enter(f); else FileViewer.open(host, j, f, listRefresher); break;
                case 1: rename(f); break;
                case 2: moveOrCopy(f, true); break;
                case 3: moveOrCopy(f, false); break;
                case 4: host.copyToClipboard("Путь в песочнице", j.sandbox.rel(f)); host.overlays().toast("Путь скопирован: " + j.sandbox.rel(f), 0); break;
                case 5: host.shareFile(f, Mime.guess(f.getName())); break;
                case 6: FileViewer.confirmDelete(host, j, f, listRefresher); break;
            }
        }});
    }

    private void rename(final File f) {
        Dialogs.prompt(host, "Переименовать", "Новое имя", f.getName(), false, false, "Сохранить", new Dialogs.StringListener() { @Override public void on(String s) {
            String name = s.trim();
            if (name.isEmpty() || name.contains("/") || name.equals(f.getName())) { host.overlays().toast("Некорректное имя", 2); return; }
            File to = new File(f.getParentFile(), name);
            if (to.exists()) { host.overlays().toast("Уже существует: " + name, 2); return; }
            if (!f.renameTo(to)) { host.overlays().toast("Не удалось переименовать", 2); return; }
            j.journal.i(app.jarvis.core.log.Journal.Cat.FILES, "Переименовано: " + j.sandbox.rel(f) + " → " + name);
            refresh();
        }});
    }

    /** Move (rename, with a streaming fallback) or stream-copy a file/folder into the picked folder. */
    private void moveOrCopy(final File f, final boolean move) {
        if (f.isDirectory() && SandboxImporter.isInside(f, cwd)) {
            host.overlays().toast("Нельзя поместить папку в саму себя", 2);
            return;
        }
        FolderPicker.show(host, move ? "Переместить «" + f.getName() + "» в…" : "Копировать «" + f.getName() + "» в…", j.sandbox, cwd, new FolderPicker.Listener() { @Override public void onPicked(final File target) {
            final File dest = Attachments.uniqueFile(target, f.getName());
            if (move && dest.equals(f)) { host.overlays().toast("Уже в этой папке", 0); return; }
            final CancelToken cancel = new CancelToken();
            final Dialogs.ProgressController pc = Dialogs.progress(host, move ? "Перемещение" : "Копирование", f.getName(), new Runnable() { @Override public void run() { cancel.cancel(); }});
            new Thread(new Runnable() { @Override public void run() {
                String op = move ? "Перемещение" : "Копирование";
                try {
                    SandboxImporter.Progress p = new SandboxImporter.Progress() { @Override public void onProgress(final String label, final long copied, final long total) {
                        j.main.post(new Runnable() { @Override public void run() {
                            pc.update(label + " · " + FileKit.size(copied) + (total > 0 ? " из " + FileKit.size(total) : ""), total > 0 ? (float) copied / total : -1);
                        }});
                    }};
                    if (move) {
                        if (!f.renameTo(dest)) {                       // different mount point: stream it
                            SandboxImporter.copyTree(f, dest, p, cancel);
                            SandboxImporter.deleteTree(f);
                        }
                    } else {
                        SandboxImporter.copyTree(f, dest, p, cancel);
                    }
                    j.journal.i(app.jarvis.core.log.Journal.Cat.FILES, op + ": " + j.sandbox.rel(f) + " → " + j.sandbox.rel(dest));
                    j.main.post(new Runnable() { @Override public void run() {
                        pc.close();
                        host.overlays().toast(move ? "Перемещено в " + j.sandbox.rel(dest) : "Скопировано в " + j.sandbox.rel(dest), 1);
                        refresh();
                    }});
                } catch (final Exception e) {
                    final String msg = cancel.isCancelled() ? "Отменено" : "Не удалось: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    j.main.post(new Runnable() { @Override public void run() {
                        pc.close();
                        host.overlays().toast(msg, cancel.isCancelled() ? 0 : 2);
                        refresh();
                    }});
                }
            }}, "jv-movecopy").start();
        }});
    }

    // -------------------------------------------------------------------------------------------------- import flow
    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_IMPORT || resultCode != android.app.Activity.RESULT_OK || data == null) return;
        final List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        importUris(uris);
    }

    private void importUris(final List<Uri> uris) {
        final CancelToken cancel = new CancelToken();
        final Dialogs.ProgressController pc = Dialogs.progress(host, "Импорт файлов", "Открываю…", new Runnable() { @Override public void run() { cancel.cancel(); }});
        new Thread(new Runnable() { @Override public void run() {
            int ok = 0;
            final List<String> failed = new ArrayList<>();
            for (int i = 0; i < uris.size(); i++) {
                if (cancel.isCancelled()) break;
                final int idx = i;
                j.main.post(new Runnable() { @Override public void run() { pc.update("Файл " + (idx + 1) + " из " + uris.size(), (float) idx / uris.size()); }});
                try {
                    SandboxImporter.importUri(ctx, j.sandbox, uris.get(i), cwd, new SandboxImporter.Progress() {
                        @Override public void onProgress(final String label, final long copied, final long total) {
                            j.main.post(new Runnable() { @Override public void run() {
                                pc.update(label + " · " + FileKit.size(copied) + (total > 0 ? " из " + FileKit.size(total) : ""), total > 0 ? (float) copied / total : -1);
                            }});
                        }
                    }, cancel);
                    ok++;
                } catch (Exception e) {
                    failed.add(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
            }
            final int okCount = ok;
            j.main.post(new Runnable() { @Override public void run() {
                pc.close();
                if (cancel.isCancelled()) host.overlays().toast("Импорт отменён", 0);
                else if (failed.isEmpty()) host.overlays().toast("Импортировано: " + okCount, 1);
                else host.overlays().toast("Импортировано " + okCount + ", не удалось " + failed.size() + ": " + failed.get(0), 2);
                j.journal.i(app.jarvis.core.log.Journal.Cat.FILES, "Импорт в " + j.sandbox.rel(cwd) + ": успешно " + okCount + ", ошибок " + failed.size());
                refresh();
            }});
        }}, "jv-import").start();
    }

    /** Scene/preview hook: same path a row tap takes. */
    public void openViewer(File f) { FileViewer.open(host, j, f, listRefresher); }
}
