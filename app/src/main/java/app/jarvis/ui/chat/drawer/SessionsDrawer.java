package app.jarvis.ui.chat.drawer;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.chat.SearchHit;
import app.jarvis.core.chat.SearchQuery;
import app.jarvis.core.chat.SessionGrouping;
import app.jarvis.core.chat.SessionSearch;
import app.jarvis.core.chat.UndoWindow;
import app.jarvis.core.ui.DrawerGesture;
import app.jarvis.data.ChatStore;
import app.jarvis.rt.Jarvis;
import app.jarvis.rt.Sessions;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.GlassDrawable;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;
import app.jarvis.ui.glass.Glass;
import app.jarvis.ui.Widgets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Left slide-in panel with the chat sessions, drawn over the Agent screen. The Agent screen forwards its touches
 * (edge swipe and drag-to-close) through onIntercept/onTouch and its back press through onBack.
 */
public final class SessionsDrawer extends FrameLayout {
    public interface Actions {
        void newChat();
        void clearContext();
        void voiceIn(long convId);
    }

    private static final float MAX_WIDTH_DP = 360f, WIDTH_FRACTION = 0.82f, EDGE_DP = 24f, SLOP_DP = 16f;
    private static final long OPEN_MS = 280, CLOSE_MS = 200, SEARCH_DEBOUNCE_MS = 150, UNDO_MS = 5000, STAGGER_MS = 20;
    private static final int STAGGER_ROWS = 8, HIT_LIMIT = 40;
    private static final Interpolator OPEN_CURVE = new DecelerateInterpolator(2f), CLOSE_CURVE = new AccelerateInterpolator(1.5f);

    private final Host host;
    private final Jarvis j;
    private final Actions actions;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SessionSearch search = new SessionSearch();
    private final UndoWindow undo = new UndoWindow(UNDO_MS);
    private final DrawerGesture gesture;
    private final Sessions.Listener sessionsListener = new Sessions.Listener() {
        @Override public void onSessionsChanged(long activeId) { if (open) loadList(); }
    };

    private View scrim;
    private FrameLayout panel;
    private ScrollView scroll;
    private LinearLayout list;
    private EditText searchField;
    private ImageView clearBtn;
    private LinearLayout undoBar;
    private TextView undoLabel;
    private boolean includeHidden, includeArchived;
    private boolean open, dragging, rowsAnimate;
    private float progress, dragStart, downX;
    private float panelPx = 1f;
    private int insetTop, insetBottom;
    private ValueAnimator anim;
    private VelocityTracker velocity;

    public SessionsDrawer(Host host, Actions actions) {
        super(host.context());
        this.host = host;
        this.j = host.jarvis();
        this.actions = actions;
        this.gesture = new DrawerGesture(Ui.dp(EDGE_DP), Ui.dp(SLOP_DP));
        setClipChildren(false);
        buildScrim();
        buildPanel();
        buildUndoBar();
        setProgress(0f);
    }

    // ---- public API ------------------------------------------------------------------------------------------------

    public boolean isOpen() { return open; }

    public void open() {
        if (open) return;
        setOpenState(true);
        rowsAnimate = true;
        loadList();
        animateTo(1f, OPEN_MS, OPEN_CURVE);
    }

    public void close() {
        if (!open) return;
        setOpenState(false);
        animateTo(0f, CLOSE_MS, CLOSE_CURVE);
    }

    /** Immediate close without animation, for when the Agent screen is hidden. */
    public void hideNow() {
        if (anim != null) anim.cancel();
        setOpenState(false);
        setProgress(0f);
    }

    /** Places the panel at a fixed open fraction (0..1). Used by previews to show a mid-animation frame. */
    public void snapTo(float fraction) {
        if (anim != null) anim.cancel();
        setOpenState(fraction > 0.5f);
        setProgress(fraction);
    }

    public boolean onBack() {
        if (!open) return false;
        close();
        return true;
    }

    public void setInsets(int top, int bottom) {
        insetTop = top;
        insetBottom = bottom;
        panel.setPadding(0, top, 0, bottom);
        LayoutParams lp = (LayoutParams) undoBar.getLayoutParams();
        lp.bottomMargin = bottom + Ui.dp(16);
        undoBar.setLayoutParams(lp);
    }

    /** Edge swipe: true from the first touch that becomes a drag; the Agent screen then stops delivering touches to its list. */
    public boolean onIntercept(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = false;
                downX = e.getX();
                gesture.down(e.getX(), e.getY(), open);
                if (velocity != null) velocity.recycle();
                velocity = VelocityTracker.obtain();
                velocity.addMovement(e);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (dragging) return true;
                if (!gesture.claims(e.getX(), e.getY())) return false;
                dragging = true;
                if (anim != null) anim.cancel();
                dragStart = progress;
                if (!open) {
                    rowsAnimate = true;
                    loadList();
                }
                return true;
            default:
                if (!dragging && velocity != null) {
                    velocity.recycle();
                    velocity = null;
                }
                return dragging;
        }
    }

    /** Receives the touches the Agent screen takes over from onIntercept. Returns false when it is not dragging. */
    public boolean onTouch(MotionEvent e) {
        if (!dragging) return false;
        if (velocity != null) velocity.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                setProgress(dragStart + (e.getX() - downX) / panelPx);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                dragging = false;
                float vx = 0f;
                if (velocity != null) {
                    velocity.computeCurrentVelocity(1000);
                    vx = velocity.getXVelocity();
                    velocity.recycle();
                    velocity = null;
                }
                boolean toOpen = DrawerGesture.settleOpen(progress, vx);
                setOpenState(toOpen);
                animateTo(toOpen ? 1f : 0f, toOpen ? OPEN_MS : CLOSE_MS, toOpen ? OPEN_CURVE : CLOSE_CURVE);
                return true;
            }
            default:
                return true;
        }
    }

    // ---- state -----------------------------------------------------------------------------------------------------

    /** Keeps the list subscribed to Sessions while the drawer is visible, and marks it open when settling open. */
    private void setOpenState(boolean nowOpen) {
        if (nowOpen == open) return;
        open = nowOpen;
        if (open) j.sessions.addListener(sessionsListener);
        else j.sessions.removeListener(sessionsListener);
    }

    private void animateTo(final float target, long durationMs, Interpolator curve) {
        if (anim != null) anim.cancel();
        long d = Ui.ms(durationMs);
        if (d == 0) { setProgress(target); return; }
        anim = ValueAnimator.ofFloat(progress, target);
        anim.setDuration(d);
        anim.setInterpolator(curve);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) { setProgress((Float) a.getAnimatedValue()); }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { anim = null; }
        });
        anim.start();
    }

    private void setProgress(float f) {
        progress = Math.max(0f, Math.min(1f, f));
        boolean visible = progress > 0f;
        panel.setVisibility(visible ? VISIBLE : GONE);
        scrim.setVisibility(visible ? VISIBLE : GONE);
        panel.setTranslationX((progress - 1f) * panelPx);
        scrim.setAlpha(progress);
    }

    // ---- layout ----------------------------------------------------------------------------------------------------

    /** The panel width comes from the width the parent gives us, set before children are measured, so one pass suffices. */
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        panelPx = Math.min(w * WIDTH_FRACTION, Ui.dp(MAX_WIDTH_DP));
        ((LayoutParams) panel.getLayoutParams()).width = (int) panelPx;
        super.onMeasure(widthSpec, heightSpec);
        setProgress(progress);
    }

    private void buildScrim() {
        scrim = new View(host.context());
        scrim.setBackgroundColor(0x66000000);
        scrim.setAlpha(0f);
        scrim.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { close(); } });
        addView(scrim, new LayoutParams(-1, -1));
    }

    private void buildPanel() {
        Context c = host.context();
        panel = new FrameLayout(c);
        panel.setClickable(true);
        panel.setBackground(Glass.surface(22, new LayerDrawable(new Drawable[]{
            Ui.rect(Ui.P.dark ? 0xE51B1823 : 0xF0FBFAFF, 22), new GlassDrawable(22, true)}), 0));
        Glass.mark(panel, 22);
        Ui.clipToRounded(panel, 22);
        LinearLayout col = Ui.column(c);
        col.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
        col.addView(buildHeader(c), Ui.lpm(-1, -2, 0, 14, 0, 10));
        col.addView(buildSearch(c), Ui.lpm(-1, -2, 0, 0, 0, 10));
        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        list = Ui.column(c);
        scroll.addView(list, new LayoutParams(-1, -2));
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        panel.addView(col, new LayoutParams(-1, -1));
        addView(panel, new LayoutParams(0, -1, Gravity.START));
    }

    private View buildHeader(Context c) {
        LinearLayout head = Ui.row(c);
        TextView title = Ui.text(c, "Чаты", Ui.TITLE);
        head.addView(title, Ui.lpw(1f));
        head.addView(Ui.iconButton(c, R.drawable.ic_plus, 40, Ui.P.text, new Runnable() {
            @Override public void run() { close(); actions.newChat(); }
        }));
        head.addView(Ui.iconButton(c, R.drawable.ic_more, 40, Ui.P.text2, new Runnable() {
            @Override public void run() { openOverflow(); }
        }));
        return head;
    }

    private View buildSearch(Context c) {
        LinearLayout box = Ui.row(c);
        box.setPadding(Ui.dp(12), 0, Ui.dp(4), 0);
        box.setBackground(Ui.glass(14));
        box.addView(Ui.icon(c, R.drawable.ic_search, 16, Ui.P.text3), Ui.lpm(-2, -2, 0, 0, 8, 0));
        searchField = Widgets.field(c, "Поиск по чатам", "", false, false);
        searchField.setBackground(null);
        searchField.setPadding(0, Ui.dp(10), 0, Ui.dp(10));
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) {
                clearBtn.setVisibility(s.length() > 0 ? VISIBLE : GONE);
                handler.removeCallbacks(applySearch);
                handler.postDelayed(applySearch, Ui.ms(SEARCH_DEBOUNCE_MS));
            }
        });
        box.addView(searchField, Ui.lpw(1f));
        clearBtn = Ui.icon(c, R.drawable.ic_close, 14, Ui.P.text3);
        clearBtn.setVisibility(GONE);
        clearBtn.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(8));
        clearBtn.setClickable(true);
        clearBtn.setContentDescription("Очистить поиск");
        clearBtn.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { searchField.setText(""); } });
        box.addView(clearBtn, Ui.lp(Ui.dp(30), Ui.dp(30)));
        return box;
    }

    private void buildUndoBar() {
        Context c = host.context();
        undoBar = Ui.row(c);
        undoBar.setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(8), Ui.dp(10));
        undoBar.setBackground(Glass.surface(16, new LayerDrawable(new Drawable[]{
            Ui.rect(Ui.P.dark ? 0xF2111019 : 0xF5FBFAFF, 16), new GlassDrawable(16, true)}), 0));
        undoBar.setVisibility(GONE);
        undoLabel = Ui.text(c, "Чат удалён", Ui.BODY, Ui.P.text);
        undoBar.addView(undoLabel, Ui.lpw(1f));
        TextView undoBtn = Ui.text(c, "Отменить", Ui.BODY, Ui.P.accent);
        undoBtn.setTypeface(Ui.semibold);
        undoBtn.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(8));
        undoBtn.setClickable(true);
        undoBtn.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (undo.undo(undo.pendingId())) {
                    handler.removeCallbacks(commitDue);
                    hideUndoBar();
                    loadList();
                }
            }
        });
        undoBar.addView(undoBtn);
        LayoutParams lp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.leftMargin = lp.rightMargin = Ui.dp(20);
        addView(undoBar, lp);
    }

    // ---- list ------------------------------------------------------------------------------------------------------

    private final Runnable applySearch = new Runnable() {
        @Override public void run() { if (search.update(searchField.getText().toString())) loadList(); }
    };

    private void loadList() {
        final int token = search.token();
        final String query = search.query();
        final boolean hidden = includeHidden, archived = includeArchived;
        new Thread(new Runnable() {
            @Override public void run() {
                final List<ChatStore.Conv> convs = j.sessions.list(query, hidden, archived);
                final List<SearchHit> hits = query.isEmpty() ? new ArrayList<SearchHit>() : j.sessions.search(query, HIT_LIMIT);
                j.main.post(new Runnable() {
                    @Override public void run() {
                        if (!search.isLatest(token)) return;
                        if (query.isEmpty()) render(convs);
                        else renderSearch(convs, hits, SearchQuery.terms(query));
                    }
                });
            }
        }, "jv-sessions-list").start();
    }

    /** Matching messages grouped by chat (snippet, tap opens the chat at that message); chats that matched only by title follow. */
    private void renderSearch(List<ChatStore.Conv> titled, List<SearchHit> hits, List<String> terms) {
        list.removeAllViews();
        Map<Long, List<SearchHit>> byChat = new LinkedHashMap<>();
        for (SearchHit h : hits) {
            if (undo.isPending(h.convId)) continue;
            List<SearchHit> group = byChat.get(h.convId);
            if (group == null) byChat.put(h.convId, group = new ArrayList<>());
            group.add(h);
        }
        List<ChatStore.Conv> rest = new ArrayList<>();
        for (ChatStore.Conv cv : titled) if (!byChat.containsKey(cv.id) && !undo.isPending(cv.id)) rest.add(cv);
        if (byChat.isEmpty() && rest.isEmpty()) {
            list.addView(Widgets.empty(host.context(), R.drawable.ic_search, "Ничего не найдено", "Попробуйте другое слово"));
            return;
        }
        for (Map.Entry<Long, List<SearchHit>> e : byChat.entrySet()) {
            String title = e.getValue().get(0).title;
            TextView head = Ui.text(host.context(), title.isEmpty() ? "Без названия" : title, Ui.CAPTION, Ui.P.text3);
            head.setTypeface(Ui.medium);
            head.setSingleLine(true);
            list.addView(head, Ui.lpm(-1, -2, 14, 14, 0, 2));
            for (final SearchHit h : e.getValue()) {
                list.addView(SearchHitRow.build(host.context(), h, terms, new Runnable() { @Override public void run() { openHit(h); } }),
                    Ui.lpm(-1, -2, 0, 0, 0, 2));
            }
        }
        long active = j.sessions.active();
        for (final ChatStore.Conv cv : rest) {
            list.addView(SessionRow.build(host.context(), cv, cv.id == active,
                new Runnable() { @Override public void run() { select(cv.id); } },
                new Runnable() { @Override public void run() { openSessionMenu(cv); } }), Ui.lpm(-1, -2, 0, 0, 0, 2));
        }
    }

    private void openHit(SearchHit h) {
        j.sessions.focusItem(h.seq);
        j.sessions.select(h.convId);
        close();
    }

    private void render(List<ChatStore.Conv> all) {
        List<ChatStore.Conv> visible = new ArrayList<>();
        for (ChatStore.Conv cv : all) if (!undo.isPending(cv.id)) visible.add(cv);
        list.removeAllViews();
        if (visible.isEmpty()) {
            list.addView(search.filtering()
                ? Widgets.empty(host.context(), R.drawable.ic_search, "Ничего не найдено", "Попробуйте другое слово")
                : Widgets.empty(host.context(), R.drawable.ic_chat, "Чатов пока нет", "Начните разговор — он появится здесь"));
            return;
        }
        long active = j.sessions.active();
        int index = 0;
        boolean animate = rowsAnimate;
        rowsAnimate = false;
        for (SessionGrouping.Section<ChatStore.Conv> sec : SessionGrouping.group(visible, FIELDS, System.currentTimeMillis(), TimeZone.getDefault())) {
            TextView head = Ui.text(host.context(), sec.title, Ui.CAPTION, Ui.P.text3);
            head.setTypeface(Ui.medium);
            list.addView(head, Ui.lpm(-1, -2, 14, 14, 0, 4));
            for (final ChatStore.Conv cv : sec.items) {
                View row = SessionRow.build(host.context(), cv, cv.id == active,
                    new Runnable() { @Override public void run() { select(cv.id); } },
                    new Runnable() { @Override public void run() { openSessionMenu(cv); } });
                list.addView(row, Ui.lpm(-1, -2, 0, 0, 0, 2));
                if (animate && index < STAGGER_ROWS) rise(row, index);
                index++;
            }
        }
    }

    private static void rise(View row, int index) {
        row.setAlpha(0f);
        row.setTranslationX(Ui.dpf(-12));
        row.animate().alpha(1f).translationX(0f).setStartDelay(Ui.ms(index * STAGGER_MS)).setDuration(Ui.ms(180)).start();
    }

    private static final SessionGrouping.Fields<ChatStore.Conv> FIELDS = new SessionGrouping.Fields<ChatStore.Conv>() {
        @Override public long updated(ChatStore.Conv cv) { return cv.updated; }
        @Override public boolean pinned(ChatStore.Conv cv) { return cv.pinned; }
        @Override public boolean archived(ChatStore.Conv cv) { return cv.archived; }
    };

    // ---- actions ---------------------------------------------------------------------------------------------------

    private void select(long id) {
        j.sessions.select(id);
        close();
    }

    private void openOverflow() {
        ListSheet.show(host, null,
            new ListSheet.Item(R.drawable.ic_refresh, "Очистить контекст текущего чата", false, false, new Runnable() {
                @Override public void run() { close(); actions.clearContext(); }
            }),
            new ListSheet.Item(R.drawable.ic_flask, "Показывать тестовые (MCP)", false, includeHidden, new Runnable() {
                @Override public void run() { includeHidden = !includeHidden; loadList(); }
            }),
            new ListSheet.Item(R.drawable.ic_archive, "Показать архив", false, includeArchived, new Runnable() {
                @Override public void run() { includeArchived = !includeArchived; loadList(); }
            }));
    }

    private void openSessionMenu(final ChatStore.Conv cv) {
        final long id = cv.id;
        ListSheet.show(host, cv.title.isEmpty() ? "Чат" : cv.title,
            new ListSheet.Item(R.drawable.ic_edit, "Переименовать", false, false, new Runnable() {
                @Override public void run() { rename(id, cv.title); }
            }),
            new ListSheet.Item(R.drawable.ic_star, cv.pinned ? "Открепить" : "Закрепить", false, false, new Runnable() {
                @Override public void run() { j.sessions.pin(id, !cv.pinned); loadList(); }
            }),
            new ListSheet.Item(R.drawable.ic_archive, cv.archived ? "Из архива" : "В архив", false, false, new Runnable() {
                @Override public void run() { j.sessions.archive(id, !cv.archived); loadList(); }
            }),
            new ListSheet.Item(R.drawable.ic_mic, "Голосовой чат здесь", false, false, new Runnable() {
                @Override public void run() { close(); actions.voiceIn(id); }
            }),
            new ListSheet.Item(R.drawable.ic_trash, "Удалить", true, false, new Runnable() {
                @Override public void run() { confirmDelete(id, cv.title); }
            }));
    }

    private void rename(final long id, String current) {
        Dialogs.prompt(host, "Переименовать", "Название чата", current, false, false, "Сохранить", new Dialogs.StringListener() {
            @Override public void on(String s) { j.sessions.rename(id, s); loadList(); }
        });
    }

    private void confirmDelete(final long id, String title) {
        Dialogs.confirm(host, "Удалить чат?", "«" + (title.isEmpty() ? "Чат" : title) + "» и все его сообщения будут удалены.",
            "Удалить", true, new Runnable() {
                @Override public void run() { startUndo(id); }
            });
    }

    /** Hides the row now; the delete is committed when the undo window closes, unless the user taps Отменить. */
    private void startUndo(long id) {
        long previous = undo.start(id, System.currentTimeMillis());
        if (previous != 0) j.sessions.delete(previous);
        undoBar.setVisibility(VISIBLE);
        undoBar.setAlpha(0f);
        undoBar.animate().alpha(1f).setDuration(Ui.ms(180)).start();
        handler.removeCallbacks(commitDue);
        handler.postDelayed(commitDue, Math.max(0, undo.deadline() - System.currentTimeMillis()));
        loadList();
    }

    private final Runnable commitDue = new Runnable() {
        @Override public void run() {
            long expired = undo.takeExpired(System.currentTimeMillis());
            if (expired != 0) j.sessions.delete(expired);
            if (undo.hasPending()) {
                handler.postDelayed(this, Math.max(0, undo.deadline() - System.currentTimeMillis()));
            } else {
                hideUndoBar();
            }
            loadList();
        }
    };

    private void hideUndoBar() {
        undoBar.animate().alpha(0f).setDuration(Ui.ms(160)).setListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { if (!undo.hasPending()) undoBar.setVisibility(GONE); }
        }).start();
    }
}
