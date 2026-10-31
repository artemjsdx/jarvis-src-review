package app.jarvis.ui.screens;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.data.ChatItem;
import app.jarvis.core.chat.SessionText;
import app.jarvis.data.ChatStore;
import app.jarvis.rt.ChatEvent;
import app.jarvis.rt.Jarvis;
import app.jarvis.rt.JarvisListener;
import app.jarvis.rt.Sessions;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.Screen;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import app.jarvis.ui.chat.AttachSheet;
import app.jarvis.ui.chat.ChatAdapter;
import app.jarvis.ui.chat.ChatTextView;
import app.jarvis.ui.chat.ModelSheets;
import app.jarvis.ui.chat.ScrollToBottomButton;
import app.jarvis.ui.glass.Glass;
import app.jarvis.ui.chat.TypingIndicator;
import app.jarvis.ui.chat.drawer.SessionsDrawer;
import app.jarvis.ui.chat.list.ChatListView;
import java.util.ArrayList;
import java.util.List;

/**
 * The agent chat: conversation list (the biggest part), slim top strip (model chip, new dialog, history), compact
 * composer (attach, text, push-to-talk mic, send/stop). Streaming text renders in one footer view, never a full list
 * rebuild. Conversation switching is a UI-local state: the picked conversation gets its `updated` stamp refreshed, so
 * the runtime's currentConversation() follows the user's choice.
 */
public class AgentScreen extends Screen {
    private static final int REQ_PICK = 7001, REQ_GALLERY = 7002;

    private long convId;
    private LinearLayout col, strip;
    private TextView titleView, modelChip;
    private SessionsDrawer drawer;
    private ChatListView list;
    private ChatAdapter adapter;
    private LinearLayout empty;
    private ScrollToBottomButton scrollBtn;
    private int unread;
    private LinearLayout streamFooter;
    private ChatTextView streamText;
    private TypingIndicator typing;
    private EditText input;
    private FrameLayout sendBtn;
    private ImageView sendIcon;
    private LinearLayout attachRow;
    private TextView attachState;
    private boolean importing;
    private final List<ChatItem.Attachment> pending = new ArrayList<>();

    private final JarvisListener listener = new JarvisListener() {
        @Override public void onChat(ChatEvent e) { handleChat(e); }
        @Override public void onRunChanged(boolean running) { refreshSend(); }
        @Override public void onProvidersChanged() { refreshModelChip(); }
        @Override public void onPermissionResult(String permission, boolean granted) { }
    };

    public AgentScreen(Host host) { super(host); }

    @Override protected View build() {
        col = Ui.column(ctx);
        buildStrip();
        View dv = new View(ctx);
        dv.setBackgroundColor(Ui.P.divider);
        col.addView(dv, new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(1))));

        FrameLayout center = new FrameLayout(ctx);
        col.addView(center, new LinearLayout.LayoutParams(-1, 0, 1f));

        list = new ChatListView(ctx);
        list.setScrolledListener(new Runnable() { @Override public void run() { updateScrollBtn(); } });
        adapter = new ChatAdapter(host, new ChatAdapter.Actions() {
            @Override public void onSpeak(String text) { j.speaker.speak(text); }
        }, list);
        streamFooter = Ui.column(ctx);
        streamFooter.setPadding(Ui.dp(12), Ui.dp(3), Ui.dp(64), Ui.dp(3));
        LinearLayout sb = Ui.column(ctx);
        sb.setBackground(Ui.glass(18));
        sb.setPadding(Ui.dp(12), Ui.dp(9), Ui.dp(12), Ui.dp(9));
        typing = new TypingIndicator(ctx);
        sb.addView(typing, Ui.lpm(-2, -2, 0, 0, 0, 0));
        streamText = new ChatTextView(ctx);
        streamText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        streamText.setTextColor(Ui.P.text);
        streamText.setLineSpacing(0, 1.18f);
        sb.addView(streamText, Ui.lpm(-1, -2, 0, 6, 0, 0));
        streamFooter.addView(sb, new LinearLayout.LayoutParams(-2, -2));
        streamFooter.setVisibility(View.GONE);
        list.setFooter(streamFooter);
        center.addView(list, new FrameLayout.LayoutParams(-1, -1));

        empty = Ui.column(ctx);
        empty.setGravity(Gravity.CENTER);
        empty.addView(Widgets.empty(ctx, R.drawable.ic_chat, "Начните диалог", "Спросите о чём угодно — текстом или голосом"));
        empty.addView(examples(ctx));
        center.addView(empty, new FrameLayout.LayoutParams(-1, -1));

        scrollBtn = new ScrollToBottomButton(ctx);
        scrollBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { unread = 0; scrollBtn.setUnread(0); jumpBottom(true); }
        });
        Glass.pinBottomEnd(host, scrollBtn, center, 44, 12);

        col.addView(buildComposer());

        drawer = new SessionsDrawer(host, new SessionsDrawer.Actions() {
            @Override public void newChat() { newConversation(); }
            @Override public void clearContext() { clearContextHere(); }
            @Override public void voiceIn(long id) { startVoiceIn(id); }
        });
        FrameLayout root = new FrameLayout(ctx) {
            @Override public boolean onInterceptTouchEvent(MotionEvent e) { return drawer.onIntercept(e); }
            @Override public boolean onTouchEvent(MotionEvent e) { return drawer.onTouch(e); }
        };
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    private LinearLayout examples(Context c) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.CENTER);
        String[] ex = {"Что ты делаешь?", "Покажи файлы", "Открой журнал"};
        for (final String s : ex) {
            TextView chip = Ui.text(c, s, Ui.CAPTION, Ui.P.text);
            chip.setTypeface(Ui.medium);
            chip.setPadding(Ui.dp(13), Ui.dp(8), Ui.dp(13), Ui.dp(8));
            chip.setBackground(Ui.glassPressable(18));
            chip.setClickable(true);
            chip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { send(s); } });
            Ui.pressScale(chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (row.getChildCount() > 0) lp.leftMargin = Ui.dp(8);
            row.addView(chip, lp);
        }
        row.setPadding(0, Ui.dp(14), 0, 0);
        return row;
    }

    private void buildStrip() {
        strip = Ui.row(ctx);
        strip.setPadding(Ui.dp(6), Ui.dp(4), Ui.dp(6), Ui.dp(4));
        FrameLayout sessionsBtn = Ui.iconButton(ctx, R.drawable.ic_list, 40, Ui.P.text, new Runnable() { @Override public void run() { drawer.open(); } });
        sessionsBtn.setContentDescription("Сессии");
        strip.addView(sessionsBtn);

        LinearLayout titles = Ui.column(ctx);
        titles.setPadding(Ui.dp(6), Ui.dp(2), Ui.dp(6), Ui.dp(2));
        titles.setClickable(true);
        titles.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { drawer.open(); } });
        titleView = Ui.text(ctx, "", Ui.BODY, Ui.P.text);
        titleView.setTypeface(Ui.semibold);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(titleView);
        modelChip = Ui.text(ctx, "", Ui.CAPTION, Ui.P.text3);
        modelChip.setSingleLine(true);
        modelChip.setEllipsize(TextUtils.TruncateAt.END);
        modelChip.setClickable(true);
        modelChip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { ModelSheets.modelSheet(host); } });
        titles.addView(modelChip, Ui.lpm(-1, -2, 0, 1, 0, 0));
        strip.addView(titles, Ui.lpw(1f));

        strip.addView(Ui.iconButton(ctx, R.drawable.ic_plus, 40, Ui.P.text, new Runnable() { @Override public void run() { newConversation(); } }));
        strip.addView(Ui.iconButton(ctx, R.drawable.ic_eraser, 40, Ui.P.text, new Runnable() { @Override public void run() { clearContextHere(); } }));
        col.addView(strip);
        refreshModelChip();
    }

    private View buildComposer() {
        LinearLayout box = Ui.column(ctx);
        box.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(6));

        HorizontalScrollView attScroll = new HorizontalScrollView(ctx);
        attScroll.setHorizontalScrollBarEnabled(false);
        attScroll.setVerticalScrollBarEnabled(false);
        attScroll.setVisibility(View.GONE);
        attachRow = Ui.row(ctx);
        attScroll.addView(attachRow, new HorizontalScrollView.LayoutParams(-2, -2));
        box.addView(attScroll, new LinearLayout.LayoutParams(-1, -2));

        attachState = Ui.text(ctx, "", Ui.CAPTION, Ui.P.text3);
        attachState.setVisibility(View.GONE);
        box.addView(attachState, Ui.lpm(-2, -2, Ui.dp(6), Ui.dp(4), 0, 0));

        LinearLayout bar = Ui.row(ctx);
        bar.setBackground(Ui.glass(24));
        bar.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
        FrameLayout clip = Ui.iconButton(ctx, R.drawable.ic_paperclip, 42, Ui.P.text2, new Runnable() {
            @Override public void run() {
                if (!importing) AttachSheet.open(host, AgentScreen.this, REQ_PICK, REQ_GALLERY);
            }
        });
        clip.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (!importing) AttachSheet.openLast(host, AgentScreen.this, REQ_PICK, REQ_GALLERY);
                return true;
            }
        });
        clip.setContentDescription("Прикрепить");
        bar.addView(clip);
        input = Widgets.field(ctx, "Сообщение", "", true, false);
        input.setMaxLines(5);
        input.setBackground(null);
        input.setPadding(Ui.dp(8), Ui.dp(10), Ui.dp(8), Ui.dp(10));
        input.setGravity(Gravity.BOTTOM | Gravity.START);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, -2, 1f);
        ilp.topMargin = Ui.dp(2);
        bar.addView(input, ilp);
        bar.addView(Ui.iconButton(ctx, R.drawable.ic_mic, 42, Ui.P.text, new Runnable() {
            @Override public void run() { startMic(); }
        }));
        sendBtn = new FrameLayout(ctx);
        sendIcon = Ui.icon(ctx, R.drawable.ic_send, 20, Ui.P.onAccent);
        sendBtn.addView(sendIcon, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        sendBtn.setBackground(Ui.accentFill(20));
        sendBtn.setClickable(true);
        sendBtn.setFocusable(true);
        sendBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onSendTap(); } });
        Ui.pressScale(sendBtn);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.dp(42), Ui.dp(42));
        slp.leftMargin = Ui.dp(4);
        bar.addView(sendBtn, slp);
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        refreshSend();
        return box;
    }

    // ---- actions ---------------------------------------------------------------------------------------------------
    private void onSendTap() {
        if (j.isBusy()) { j.cancelAll(); return; }
        send(input.getText().toString());
    }

    private void send(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty() && pending.isEmpty()) return;
        List<ChatItem.Attachment> atts = new ArrayList<>(pending);
        pending.clear();
        input.setText("");
        rebuildAttachChips();
        j.submit(t, atts, "text");
        host.hideKeyboard();
        unread = 0;
        scrollBtn.setUnread(0);
        jumpBottom(true);
    }

    private void startMic() {
        if (!host.hasPermission(Manifest.permission.RECORD_AUDIO)) { host.requestPermission(Manifest.permission.RECORD_AUDIO); return; }
        String p = j.voice.micProblem();
        if (!p.isEmpty()) { j.toast(p, 2); return; }
        j.voice.startListening();
    }

    /** The "+" button, the drawer's new-chat action and the direct command all go through Sessions.newChat(). */
    private void newConversation() {
        convId = j.sessions.newChat();
        reload();
        focusInput();
    }

    /** Never wipes the context silently: the icon alone does not say what it does, so the dialog names the effect first. */
    private void clearContextHere() {
        Dialogs.confirm(host, "Очистить контекст чата?",
            "Ассистент перестанет видеть всё, о чём вы говорили в этом чате до этого момента. Сообщения останутся в ленте.",
            "Очистить", true, new Runnable() { @Override public void run() {
                j.sessions.clearContext(convId);
                reload();
                j.toast("Контекст очищен", 1);
            } });
    }

    private void startVoiceIn(long id) {
        if (!host.hasPermission(Manifest.permission.RECORD_AUDIO)) { host.requestPermission(Manifest.permission.RECORD_AUDIO); return; }
        String p = j.voice.micProblem();
        if (!p.isEmpty()) { j.toast(p, 2); return; }
        if (!j.startVoiceChat(id)) j.toast("Не удалось включить микрофон", 2);
    }

    private void focusInput() {
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, 0);
    }

    private final Sessions.Listener sessionsListener = new Sessions.Listener() {
        @Override public void onSessionsChanged(long activeId) {
            if (activeId != convId || j.sessions.hasFocusItem()) {
                convId = activeId;
                reload();
            }
            refreshTitle();
        }
    };

    private void refreshTitle() {
        final long conv = convId;
        new Thread(new Runnable() {
            @Override public void run() {
                ChatStore.Conv cv = j.sessions.info(conv);
                final String t = cv == null || cv.title.isEmpty() ? SessionText.DEFAULT_TITLE : cv.title;
                j.main.post(new Runnable() {
                    @Override public void run() { if (conv == convId) titleView.setText(t); }
                });
            }
        }, "jv-chat-title").start();
    }

    // ---- data ------------------------------------------------------------------------------------------------------
    private volatile long loadStamp;

    private void reload() {
        final long conv = convId;
        final long stamp = ++loadStamp;
        new Thread(new Runnable() {
            @Override public void run() {
                final List<ChatItem> items = j.chat.items(conv, 0, 400);
                j.main.post(new Runnable() {
                    @Override public void run() {
                        if (stamp != loadStamp) return;
                        adapter.setAll(items);
                        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                        unread = 0;
                        scrollBtn.setUnread(0);
                        long focus = j.sessions.takeFocusItem();
                        if (focus <= 0 || !list.scrollToKey(focus)) jumpBottom(false);
                    }
                });
            }
        }, "jv-chat-load").start();
    }

    private void handleChat(ChatEvent e) {
        switch (e.type) {
            case ADDED:
                if (e.item != null && e.item.convId == convId) {
                    boolean stick = list.isAtBottom();
                    if (stick) list.followIfAtBottom();
                    adapter.upsert(e.item);
                    empty.setVisibility(View.GONE);
                    if (!stick) unread++;
                    scrollBtn.setUnread(unread);
                    updateScrollBtn();
                }
                break;
            case UPDATED:
                if (e.item != null && e.item.convId == convId) {
                    boolean stick = list.isAtBottom();
                    if (stick) list.followIfAtBottom();
                    adapter.upsert(e.item);
                }
                break;
            case STREAM:
                if (e.streamText == null) break;
                boolean stick = list.isAtBottom();
                if (stick) list.followIfAtBottom();
                boolean hasText = !e.streamText.isEmpty();
                streamText.setVisibility(hasText ? View.VISIBLE : View.GONE);
                if (hasText) streamText.setText(e.streamText);
                streamFooter.setVisibility(View.VISIBLE);
                typing.setActive(!hasText);
                break;
            case STREAM_END:
                streamFooter.setVisibility(View.GONE);
                typing.setActive(false);
                break;
            case CLEARED:
            case SWITCHED:
                reload();
                break;
            default:
                break;
        }
    }

    private void jumpBottom(boolean smooth) {
        list.scrollToBottom(smooth);
    }

    private void updateScrollBtn() {
        boolean away = !list.isAtBottom() && !adapter.items().isEmpty();
        scrollBtn.show(away);
        if (!away) { unread = 0; scrollBtn.setUnread(0); }
    }

    // ---- composer details ------------------------------------------------------------------------------------------
    private void refreshSend() {
        boolean busy = j.isBusy();
        sendIcon.setImageResource(busy ? R.drawable.ic_stop : R.drawable.ic_send);
        sendBtn.setContentDescription(busy ? "Остановить" : "Отправить");
    }

    private void rebuildAttachChips() {
        attachRow.removeAllViews();
        for (final ChatItem.Attachment a : pending) {
            LinearLayout chip = Ui.row(ctx);
            chip.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(6), Ui.dp(6));
            chip.setBackground(Ui.glass(14));
            chip.addView(Ui.icon(ctx, R.drawable.ic_file, 14, Ui.P.accent));
            TextView name = Ui.text(ctx, a.name.isEmpty() ? "файл" : a.name, Ui.CAPTION, Ui.P.text2);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            name.setMaxWidth(Ui.dp(160));
            chip.addView(name, Ui.lpm(-2, -2, 6, 0, 2, 0));
            ImageView x = Ui.icon(ctx, R.drawable.ic_close, 13, Ui.P.text3);
            FrameLayout xb = new FrameLayout(ctx);
            xb.setBackground(Ui.rippleOn(new android.graphics.drawable.ColorDrawable(0), 12));
            xb.setClickable(true);
            xb.addView(x, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            xb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pending.remove(a); rebuildAttachChips(); }
            });
            LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(Ui.dp(26), Ui.dp(26));
            chip.addView(xb, xlp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (attachRow.getChildCount() > 0) lp.leftMargin = Ui.dp(6);
            attachRow.addView(chip, lp);
        }
        ((ViewGroup) attachRow.getParent()).setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void startAttach(final Uri uri) {
        if (importing) return;
        importing = true;
        attachState.setVisibility(View.VISIBLE);
        attachState.setText("Загружаю файл…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final ChatItem.Attachment a = j.attach(uri, new app.jarvis.rt.Attachments.Progress() {
                        @Override public void onBytes(final long copied, final long total) {
                            j.main.post(new Runnable() {
                                @Override public void run() {
                                    attachState.setText(total > 0 ? "Загружаю файл… " + (copied * 100 / total) + "%" : "Загружаю файл…");
                                }
                            });
                        }
                    });
                    j.main.post(new Runnable() {
                        @Override public void run() {
                            importing = false;
                            attachState.setVisibility(View.GONE);
                            if (a != null) { pending.add(a); rebuildAttachChips(); }
                        }
                    });
                } catch (final Throwable t) {
                    j.main.post(new Runnable() {
                        @Override public void run() {
                            importing = false;
                            attachState.setVisibility(View.GONE);
                            j.toast("Не удалось вложить файл: " + t.getClass().getSimpleName(), 2);
                        }
                    });
                }
            }
        }, "jv-attach").start();
    }

    // ---- top strip -------------------------------------------------------------------------------------------------
    private void refreshModelChip() {
        List<Jarvis.LlmChoice> choices = j.llmChoices();
        Jarvis.LlmChoice c = choices.isEmpty() ? null : choices.get(0);
        if (c != null && c.hasKey && !c.model.isEmpty()) {
            modelChip.setText(c.preset.name + " · " + c.model);
            modelChip.setTextColor(Ui.P.text3);
        } else {
            String name = c == null ? "" : c.preset.name;
            modelChip.setText((name.isEmpty() ? "" : name + " · ") + "нет ключа или модели");
            modelChip.setTextColor(Ui.P.warn);
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------------
    @Override public String title() { return null; }

    @Override public void onShow(String arg) {
        convId = j.chat.currentConversation();
        j.addListener(listener);
        j.sessions.addListener(sessionsListener);
        refreshModelChip();
        refreshSend();
        refreshTitle();
        reload();
    }

    @Override public void onHide() {
        j.removeListener(listener);
        j.sessions.removeListener(sessionsListener);
        drawer.hideNow();
        streamFooter.setVisibility(View.GONE);
        typing.setActive(false);
    }

    @Override public boolean onBack() { return drawer.onBack(); }

    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {
        boolean ours = requestCode == REQ_PICK || requestCode == REQ_GALLERY;
        if (!ours || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        startAttach(data.getData());
    }

    @Override public void onInsets(int top, int bottom) {
        col.setPadding(0, top, 0, 0);
        drawer.setInsets(top, bottom);
        if (list.isAtBottom()) jumpBottom(false);
    }
}
