package app.jarvis.ui.screens;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ListView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.log.Journal;
import app.jarvis.core.log.JournalAction;
import app.jarvis.core.log.JournalFilter;
import app.jarvis.data.SqlJournal;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Screen;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import app.jarvis.ui.files.FileKit;
import app.jarvis.ui.glass.DropFrame;
import app.jarvis.ui.glass.Glass;
import app.jarvis.ui.glass.LiquidIndicator;
import app.jarvis.ui.glass.SegmentedGlass;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Journal viewer over j.journal (SqlJournal): live-updating list (newest first; the user's scroll position survives),
 * 14 category chips + level segments + text search, row detail sheet (where the error happened, technical details,
 * action button, run id, duration, copy), "older rows" paging, pause live, clear with confirm, export to j.home/exports/
 * with a share sheet, copy visible rows, and the total row count. Queries run off the UI thread.
 */
public class LogScreen extends Screen {
    private static final int PAGE = 100;

    private static final String[][] CATS = {
        {"system", "Система"}, {"agent", "Агент"}, {"llm", "LLM"}, {"tools", "Инструменты"}, {"terminal", "Терминал"},
        {"files", "Файлы"}, {"downloads", "Загрузки"}, {"wake", "Wake"}, {"stt", "STT"}, {"tts", "TTS"},
        {"player", "Плеер"}, {"adb", "ADB"}, {"permissions", "Разрешения"}, {"performance", "Перфоманс"},
    };

    private ListView list;
    private RowAdapter adapter;
    private final List<SqlJournal.Row> rows = new ArrayList<>();
    private String catFilter;                       // null = all
    private int segment = JournalFilter.ALL;
    private String search = "";
    private boolean paused, loadingPage, hasMore = true;
    private long total = -1;
    private TextView totalView, pauseChip;
    private SegmentedGlass levels;
    private DropFrame catTrack;
    private LinearLayout catChips;
    private LiquidIndicator catDrop;
    private TextView catSelected;
    private HorizontalScrollView catScroll;
    private TextView moreFooter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SqlJournal.LiveListener live = new SqlJournal.LiveListener() { @Override public void onRows(List<SqlJournal.Row> newRows) { onLive(newRows); } };

    public LogScreen(Host host) { super(host); }

    @Override protected View build() {
        LinearLayout root = Ui.column(ctx);
        root.setPadding(Ui.dp(10), Ui.dp(4), Ui.dp(10), Ui.dp(4));

        // headline: total + live pause + copy/export/clear
        LinearLayout head = Ui.row(ctx);
        head.setPadding(Ui.dp(4), Ui.dp(2), Ui.dp(4), Ui.dp(2));
        totalView = Ui.text(ctx, "", Ui.CAPTION, Ui.P.text3);
        head.addView(totalView, Ui.lpw(1f));
        pauseChip = Ui.chip(ctx, "вживую", Ui.P.ok);
        pauseChip.setTypeface(Ui.medium);
        pauseChip.setClickable(true);
        pauseChip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { togglePause(); }});
        head.addView(pauseChip, Ui.lpm(-2, -2, 0, 0, 6, 0));
        head.addView(Ui.iconButton(ctx, R.drawable.ic_copy, 34, Ui.P.text2, new Runnable() { @Override public void run() { copyVisible(); }}));
        head.addView(Ui.iconButton(ctx, R.drawable.ic_download, 34, Ui.P.text2, new Runnable() { @Override public void run() { export(); }}));
        head.addView(Ui.iconButton(ctx, R.drawable.ic_trash, 34, Ui.P.text2, new Runnable() { @Override public void run() {
            Dialogs.confirm(host, "Очистить журнал?", "Все записи будут удалены безвозвратно. Это не влияет на работу агента.", "Очистить", true,
                new Runnable() { @Override public void run() {
                    new Thread(new Runnable() { @Override public void run() {
                        j.journal.clear();
                        j.main.post(new Runnable() { @Override public void run() { requery(); }});
                    }}, "jv-logclear").start();
                }});
        }}));
        root.addView(head);

        root.addView(buildLevelFilter(), Ui.lpm(-1, -2, 0, 4, 0, 0));

        catChips = Ui.row(ctx);
        catChips.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
        catTrack = new DropFrame(ctx, catChips);
        catDrop = catTrack.drop();
        catTrack.setBackground(Glass.background(Ui.S.RADIUS_CHIP));
        Glass.mark(catTrack, Ui.S.RADIUS_CHIP);
        catTrack.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) { retargetCatDrop(); }
        });
        catScroll = new HorizontalScrollView(ctx);
        catScroll.setHorizontalScrollBarEnabled(false);
        catScroll.addView(catTrack, new ViewGroup.LayoutParams(-2, -2));
        root.addView(catScroll, Ui.lpm(-1, -2, 0, 6, 0, 0));
        buildCatChips();

        // search
        final EditText searchField = Widgets.field(ctx, "Поиск по тексту…", "", false, false);
        searchField.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f);
        searchField.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(8));
        FrameLayout searchBox = Widgets.fieldBox(ctx, searchField);
        root.addView(searchBox, Ui.lpm(-1, -2, 0, 6, 0, 4));
        searchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(final android.text.Editable s) {
                handler.removeCallbacksAndMessages(null);
                handler.postDelayed(new Runnable() { @Override public void run() {
                    String ns = s.toString().trim();
                    if (!ns.equals(search)) { search = ns; requery(); }
                }}, 350);
            }
        });

        // rows
        list = new ListView(ctx);
        list.setDivider(Ui.rect(Ui.P.divider, 0));
        list.setDividerHeight(Math.max(1, Ui.dp(0.75f)));
        list.setStackFromBottom(false);
        adapter = new RowAdapter(ctx);
        list.setAdapter(adapter);
        moreFooter = Ui.text(ctx, "Показать ещё", Ui.BODY, Ui.P.accent);
        moreFooter.setTypeface(Ui.medium);
        moreFooter.setGravity(Gravity.CENTER);
        moreFooter.setPadding(0, Ui.dp(14), 0, Ui.dp(14));
        moreFooter.setClickable(true);
        moreFooter.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { loadMore(); }});
        LinearLayout footWrap = Ui.column(ctx);
        footWrap.setGravity(Gravity.CENTER);
        footWrap.addView(moreFooter);
        list.addFooterView(footWrap, null, false);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    /** The level segments (Все / Предупр. / Ошибки / Отладка) as a glass control with the liquid drop. */
    private View buildLevelFilter() {
        levels = new SegmentedGlass(ctx);
        levels.setItems(new String[]{"Все", "Предупр.", "Ошибки", "Отладка"});
        levels.setSelected(segment);
        levels.setListener(new SegmentedGlass.Listener() {
            @Override public void onSelected(int index) {
                segment = index;
                requery();
            }
        });
        return levels;
    }

    private Journal.Level minLevel() { return JournalFilter.minLevel(segment, search); }

    @Override public String title() { return "Журнал"; }

    @Override public void onShow(String arg) {
        j.journal.addLiveListener(live);
        requery();
    }

    @Override public void onHide() {
        j.journal.removeLiveListener(live);
    }

    // --------------------------------------------------------------------------------------------------- data loading
    private void requery() {
        final String cat = catFilter, text = search;
        final Journal.Level lvl = minLevel();
        new Thread(new Runnable() { @Override public void run() {
            final List<SqlJournal.Row> fresh = j.journal.query(cat, lvl, text, 0, PAGE);
            final long totalNow = j.journal.count();
            j.main.post(new Runnable() { @Override public void run() {
                if (!same(cat, text, lvl)) return; // stale
                rows.clear();
                rows.addAll(fresh);
                hasMore = fresh.size() >= PAGE;
                total = totalNow;
                updateHead();
                adapter.notifyDataSetChanged();
            }});
        }}, "jv-logquery").start();
    }

    private boolean same(String cat, String text, Journal.Level lvl) {
        return (cat == null ? catFilter == null : cat.equals(catFilter)) && text.equals(search) && lvl == minLevel();
    }

    private void loadMore() {
        if (loadingPage || rows.isEmpty() || !hasMore) return;
        loadingPage = true;
        final String cat = catFilter, text = search;
        final Journal.Level lvl = minLevel();
        final long before = rows.get(rows.size() - 1).id;
        new Thread(new Runnable() { @Override public void run() {
            final List<SqlJournal.Row> older = j.journal.query(cat, lvl, text, before, PAGE);
            j.main.post(new Runnable() { @Override public void run() {
                loadingPage = false;
                rows.addAll(older);
                hasMore = older.size() >= PAGE;
                moreFooter.setText(hasMore ? "Показать ещё" : "Дальше записей нет");
                adapter.notifyDataSetChanged();
            }});
        }}, "jv-logmore").start();
    }

    /**
     * Live rows: a row whose id is already listed replaces it in place (summary rows are rewritten, not appended); the
     * matching new rows are prepended (newest first). The user's scroll position is kept unless at the top.
     */
    private void onLive(List<SqlJournal.Row> newRows) {
        if (paused) return;
        List<SqlJournal.Row> matching = new ArrayList<>();
        boolean replaced = false;
        for (int i = newRows.size() - 1; i >= 0; i--) {          // newest first
            SqlJournal.Row r = newRows.get(i);
            int at = indexOfId(r.id);
            if (at >= 0) { rows.set(at, r); replaced = true; }
            else if (matches(r)) matching.add(r);
        }
        if (matching.isEmpty() && !replaced) return;
        boolean atTop = list.getFirstVisiblePosition() <= 1;
        int firstPos = list.getFirstVisiblePosition();
        View firstChild = list.getChildAt(0);
        int offset = firstChild == null ? 0 : firstChild.getTop();
        rows.addAll(0, matching);
        if (total >= 0) total += matching.size();
        adapter.notifyDataSetChanged();
        if (atTop) list.setSelection(0);
        else list.setSelectionFromTop(firstPos + matching.size(), offset);
        updateHead();
    }

    private int indexOfId(long id) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).id == id) return i;
        return -1;
    }

    private boolean matches(SqlJournal.Row r) {
        if (catFilter != null && !catFilter.equals(r.category)) return false;
        if (r.level.ordinal() < minLevel().ordinal()) return false;
        if (!search.isEmpty() && !(r.message.contains(search) || r.details.contains(search))) return false;
        return true;
    }

    private void updateHead() {
        String s = total < 0 ? "" : "Всего: " + total;
        if (paused) s += " · пауза";
        totalView.setText(s);
        moreFooter.setText(hasMore ? "Показать ещё" : "Дальше записей нет");
    }

    private void togglePause() {
        paused = !paused;
        pauseChip.setText(paused ? "пауза" : "вживую");
        pauseChip.setTextColor(paused ? Ui.P.warn : Ui.P.ok);
        pauseChip.setBackground(Ui.rect(Ui.alpha(paused ? Ui.P.warn : Ui.P.ok, 0.14f), 20));
        if (!paused) requery();
        updateHead();
    }

    private void buildCatChips() {
        catChips.removeAllViews();
        catSelected = null;
        catChips.addView(chip("Все", null), Ui.lpm(-2, -2, 0, 0, 6, 0));
        for (String[] pair : CATS) catChips.addView(chip(pair[1], pair[0]), Ui.lpm(-2, -2, 0, 0, 6, 0));
        catChips.post(new Runnable() { @Override public void run() { retargetCatDrop(); } });
    }

    private TextView chip(String label, final String cat) {
        final boolean on = cat == null ? catFilter == null : cat.equals(catFilter);
        TextView t = Ui.chip(ctx, label, on ? Ui.P.text : Ui.P.text2);
        t.setTypeface(on ? Ui.medium : Ui.regular);
        t.setBackground(null);
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            selectCategory(cat);
        }});
        if (on) catSelected = t;
        return t;
    }

    /** Moves the category drop behind the selected chip; before the first layout there is nothing to aim at. */
    private void retargetCatDrop() {
        if (catSelected == null || catChips.getWidth() == 0) return;
        float inset = Ui.dpf(2);
        float l = catChips.getLeft() + catSelected.getLeft() + inset, t = catChips.getTop() + catSelected.getTop() + inset;
        catDrop.moveTo(l, t, l + catSelected.getWidth() - 2 * inset, t + catSelected.getHeight() - 2 * inset);
    }

    /** Selects the category filter (null = all); the chip row and the list follow. */
    public void selectCategory(String cat) {
        catFilter = cat;
        buildCatChips();
        requery();
    }

    // ---------------------------------------------------------------------------------------------------- list rows
    private static int levelColor(Journal.Level l) {
        switch (l) {
            case ERROR: return Ui.P.err;
            case WARN: return Ui.P.warn;
            case DEBUG: return Ui.P.text3;
            default: return Ui.P.accent;
        }
    }

    /** The search text marked in the row; the list keeps the plain message for everything else. */
    static CharSequence highlight(String text, String query) {
        if (text == null || query == null || query.isEmpty()) return text;
        String lower = text.toLowerCase(Locale.ROOT), q = query.toLowerCase(Locale.ROOT);
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        int i = lower.indexOf(q);
        while (i >= 0) {
            sb.setSpan(new BackgroundColorSpan(Ui.alpha(Ui.P.accent, 0.28f)), i, i + q.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            i = lower.indexOf(q, i + q.length());
        }
        return sb;
    }

    private static final class Holder {
        View bar;
        TextView cat, time, msg, site, dur;
    }

    private final class RowAdapter extends BaseAdapter {
        private final java.lang.ref.WeakReference<Context> c;
        RowAdapter(Context ctx) { c = new java.lang.ref.WeakReference<>(ctx); }

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return rows.get(position).id; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Context cc = c.get();
            if (cc == null) return new View(parent.getContext());
            Holder h;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof Holder) {
                h = (Holder) convertView.getTag();
            } else {
                h = new Holder();
                LinearLayout row = Ui.row(cc);
                row.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(10), Ui.dp(8));
                h.bar = new View(cc);
                row.addView(h.bar, Ui.lpm(Ui.dp(3), Ui.dp(34), 0, 0, 10, 0));
                LinearLayout col = Ui.column(cc);
                LinearLayout top = Ui.row(cc);
                h.cat = Ui.chip(cc, "", Ui.P.text2);
                h.cat.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f);
                top.addView(h.cat);
                h.time = Ui.text(cc, "", Ui.CAPTION, Ui.P.text3);
                top.addView(h.time, Ui.lpm(-2, -2, 8, 0, 0, 0));
                h.dur = Ui.text(cc, "", Ui.CAPTION, Ui.P.text3);
                top.addView(Ui.space(cc, 1, 1), Ui.lpw(1f));
                top.addView(h.dur);
                col.addView(top);
                h.msg = Ui.text(cc, "", Ui.BODY);
                h.msg.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f);
                h.msg.setSingleLine(true);
                h.msg.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(h.msg, Ui.lpm(-2, -2, 0, 4, 0, 0));
                h.site = Ui.text(cc, "", Ui.MONO, Ui.P.text2);
                h.site.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f);
                h.site.setSingleLine(true);
                h.site.setEllipsize(TextUtils.TruncateAt.START);
                col.addView(h.site, Ui.lpm(-2, -2, 0, 2, 0, 0));
                row.addView(col, Ui.lpw(1f));
                row.setTag(h);
                convertView = row;
            }
            final SqlJournal.Row r = rows.get(position);
            h.bar.setBackgroundColor(levelColor(r.level));
            h.cat.setText(r.category);
            h.cat.setTextColor(Ui.P.text2);
            h.cat.setBackground(Ui.rect(Ui.P.field, 20));
            h.time.setText(r.time());
            h.msg.setText(highlight(r.message, search));
            String site = r.site();
            h.site.setVisibility(site == null ? View.GONE : View.VISIBLE);
            h.site.setText(site == null ? "" : "Где: " + site);
            h.dur.setText(r.durationMs >= 0 ? FileKit.duration(r.durationMs) : "");
            convertView.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { detail(r); }});
            return convertView;
        }
    }

    // ------------------------------------------------------------------------------------------------- detail sheet
    private void detail(final SqlJournal.Row r) {
        LinearLayout b = Ui.column(ctx);
        b.setPadding(Ui.dp(20), Ui.dp(4), Ui.dp(20), Ui.dp(12));
        LinearLayout head = Ui.row(ctx);
        TextView cat = Ui.chip(ctx, r.category, levelColor(r.level));
        head.addView(cat);
        head.addView(Ui.text(ctx, r.time(), Ui.CAPTION, Ui.P.text3), Ui.lpm(-2, -2, 10, 0, 0, 0));
        head.addView(Ui.space(ctx, 1, 1), Ui.lpw(1f));
        if (r.durationMs >= 0) head.addView(Widgets.pill(ctx, FileKit.duration(r.durationMs), Widgets.MUTED));
        b.addView(head);
        TextView msg = Ui.text(ctx, r.message, Ui.BODY);
        msg.setTextIsSelectable(true);
        b.addView(msg, Ui.lpm(-1, -2, 0, 10, 0, 0));
        String site = r.site();
        if (site != null) {
            TextView where = Ui.text(ctx, "Где: " + site, Ui.MONO, Ui.P.err);
            where.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f);
            where.setTextIsSelectable(true);
            b.addView(where, Ui.lpm(-1, -2, 0, 10, 0, 0));
        }
        if (r.runId != null && !r.runId.isEmpty())
            b.addView(Ui.text(ctx, "Запуск: " + r.runId, Ui.CAPTION, Ui.P.text3), Ui.lpm(-1, -2, 0, 8, 0, 0));
        if (site != null) {
            techBlock(b, r);
        } else if (r.details != null && !r.details.isEmpty()) {
            b.addView(Widgets.sectionTitle(ctx, "Детали"));
            TextView d = Ui.text(ctx, pretty(r.details), Ui.MONO, Ui.P.text2);
            d.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            d.setTextIsSelectable(true);
            b.addView(d, Ui.lpm(-1, -2, 0, 2, 0, 0));
        }
        final JournalAction action = JournalAction.from(r.details);
        final OverlayHost.Layer[] sheet = new OverlayHost.Layer[1];
        if (action != null) {
            b.addView(Ui.button(ctx, action.label, R.drawable.ic_ok, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() {
                host.overlays().dismiss(sheet[0]);
                openAction(action);
            }}), Ui.lpm(-1, -2, 0, 14, 0, 0));
        }
        LinearLayout btns = Ui.row(ctx);
        btns.setGravity(Gravity.END);
        btns.addView(Ui.button(ctx, "Копировать", R.drawable.ic_copy, Ui.BTN_GLASS, new Runnable() { @Override public void run() {
            host.copyToClipboard("Журнал", SqlJournal.format(r));
            host.overlays().toast("Скопировано", 1);
        }}));
        b.addView(btns, Ui.lpm(-1, -2, 0, action == null ? 14 : 8, 0, 0));
        sheet[0] = Dialogs.sheet(host, "Событие журнала", b, true);
    }

    /** Collapsed block with the thread, environment and the full stack of an error (expands on tap). */
    private void techBlock(LinearLayout b, SqlJournal.Row r) {
        final TextView header = Ui.text(ctx, "Технические детали: показать", Ui.BODY, Ui.P.accent);
        header.setTypeface(Ui.medium);
        header.setPadding(0, Ui.dp(14), 0, Ui.dp(6));
        header.setClickable(true);
        final TextView body = Ui.text(ctx, technicalText(r.details), Ui.MONO, Ui.P.text2);
        body.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f);
        body.setTextIsSelectable(true);
        body.setVisibility(View.GONE);
        header.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            boolean open = body.getVisibility() != View.VISIBLE;
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            header.setText("Технические детали: " + (open ? "скрыть" : "показать"));
        }});
        b.addView(header);
        b.addView(body, Ui.lpm(-1, -2, 0, 0, 0, 0));
    }

    static String technicalText(String detailsJson) {
        JSONObject d;
        try { d = new JSONObject(detailsJson); } catch (Throwable ignored) { return detailsJson; }
        StringBuilder sb = new StringBuilder();
        line(sb, "Поток", d.optString("thread"));
        line(sb, "Приложение", d.optString("app"));
        line(sb, "Android", d.optString("android"));
        line(sb, "Устройство", d.optString("device"));
        if (d.has("free_mb")) line(sb, "Свободно памяти", d.optLong("free_mb") + " МБ");
        line(sb, "Исключение", d.optString("exception") + (d.optString("cause").isEmpty() ? "" : ": " + d.optString("cause")));
        line(sb, "Отпечаток", d.optString("fingerprint"));
        String stack = d.optString("stack");
        if (!stack.isEmpty()) sb.append('\n').append(stack);
        return sb.toString();
    }

    private static void line(StringBuilder sb, String label, String value) {
        if (value != null && !value.isEmpty()) sb.append(label).append(": ").append(value).append('\n');
    }

    private void openAction(JournalAction a) {
        if (JournalAction.BACKGROUND_SETTINGS.equals(a.id)) host.navigate("settings/background");
    }

    private static String pretty(String details) {
        try { return new JSONObject(details).toString(2); } catch (Throwable ignored) { return details; }
    }

    // ------------------------------------------------------------------------------------------------------- export
    public void export() {
        final String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
        final File dir = new File(j.home, "exports");
        final File out = new File(dir, "jarvis-log-" + stamp + ".txt");
        final Dialogs.ProgressController pc = Dialogs.progress(host, "Экспорт журнала", "Пишу файл…", null);
        final String cat = catFilter;
        final Journal.Level lvl = minLevel();
        new Thread(new Runnable() { @Override public void run() {
            try {
                dir.mkdirs();
                final int n = j.journal.export(out, cat, lvl, 20000);
                j.journal.i(Journal.Cat.SYSTEM, "Журнал выгружен в " + out.getPath() + " (" + n + " строк)");
                j.main.post(new Runnable() { @Override public void run() {
                    pc.close();
                    exportSheet(out, n);
                }});
            } catch (final Exception e) {
                j.main.post(new Runnable() { @Override public void run() {
                    pc.close();
                    host.overlays().toast("Экспорт не удался: " + e.getMessage(), 2);
                }});
            }
        }}, "jv-logexport").start();
    }

    private void exportSheet(final File out, int n) {
        LinearLayout b = Ui.column(ctx);
        b.setPadding(Ui.dp(20), Ui.dp(4), Ui.dp(20), Ui.dp(12));
        LinearLayout ok = Ui.row(ctx);
        ok.addView(Ui.icon(ctx, R.drawable.ic_ok, 20, Ui.P.ok));
        ok.addView(Ui.text(ctx, "Выгружено строк: " + n, Ui.BODY), Ui.lpm(-2, -2, 10, 0, 0, 0));
        b.addView(ok);
        TextView path = Ui.text(ctx, out.getPath(), Ui.MONO, Ui.P.text2);
        path.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        path.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
        path.setBackground(Ui.glass(12));
        path.setTextIsSelectable(true);
        b.addView(path, Ui.lpm(-1, -2, 0, 10, 0, 0));
        LinearLayout btns = Ui.row(ctx);
        btns.addView(Ui.button(ctx, "Скопировать путь", R.drawable.ic_copy, Ui.BTN_GLASS, new Runnable() { @Override public void run() {
            host.copyToClipboard("Путь журнала", out.getPath());
            host.overlays().toast("Путь скопирован", 1);
        }}), Ui.lpw(1f));
        btns.addView(Ui.button(ctx, "Поделиться", R.drawable.ic_share, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() {
            host.shareFile(out, "text/plain");
        }}), Ui.lpm(-2, -2, 8, 0, 0, 0));
        b.addView(btns, Ui.lpm(-1, -2, 0, 12, 0, 0));
        Dialogs.sheet(host, "Журнал выгружен", b, true);
    }

    private void copyVisible() {
        if (rows.isEmpty()) { host.overlays().toast("Нет видимых строк", 0); return; }
        int n = Math.min(rows.size(), 2000);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(SqlJournal.format(rows.get(i))).append('\n');
        host.copyToClipboard("Журнал Jarvis", sb.toString());
        host.overlays().toast("Скопировано строк: " + n + (n < rows.size() ? " (из загруженных " + rows.size() + ")" : ""), 1);
    }

    /** Scene hook: open the detail sheet for a row. */
    public void openDetail(SqlJournal.Row r) { detail(r); }
}
