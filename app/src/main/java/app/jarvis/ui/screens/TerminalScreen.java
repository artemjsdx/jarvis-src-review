package app.jarvis.ui.screens;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.tools.ShellExec;
import app.jarvis.core.tools.TermuxCommand;
import app.jarvis.core.tools.TermuxTool;
import app.jarvis.core.util.CancelToken;
import app.jarvis.rt.TermuxBridge;
import app.jarvis.ui.Host;
import app.jarvis.ui.Screen;
import app.jarvis.ui.Ui;
import app.jarvis.ui.files.FileKit;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * A real terminal over j.shell (ShellSession): append-only color-coded output (stdout white, stderr rose, echo lavender),
 * a ~200 KB hard cap on the visible buffer, Run→Cancel input line, history navigation (chips + up/down), quick-command
 * chips above the input with a help sheet, and an honest "Подробнее" sheet about the limits. A "Termux" mode sends the
 * same input to Termux through {@link TermuxBridge}. Commands run on a background thread; output arrives through the
 * Sink callback and is flushed to the UI throttled (~15 updates/s), so the UI thread never blocks.
 */
public class TerminalScreen extends Screen {
    private static final long TIMEOUT_MS = 600_000;          // 10 minutes per command, cancellable
    private static final int CAP = 200_000;                  // hard cap of the visible output buffer, chars
    private static final long FLUSH_MS = 66;                 // ~15 flushes/s
    private static final String[] QUICK = {"ls", "pwd", "df -h", "uname -a", "ps", "date"};

    private TextView output;
    private TextView emptyOutput;                                  // monospace, spannable
    private ScrollView outputScroll;
    private boolean autoFollow = true;
    private final SpannableStringBuilder buf = new SpannableStringBuilder();
    private final ArrayDeque<Chunk> pending = new ArrayDeque<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable flusher = new Runnable() { @Override public void run() { flush(); } };
    private boolean flushScheduled;

    private EditText input;
    private LinearLayout runBtn;
    private TextView runLabel;
    private android.widget.ImageView runIcon;
    private TextView footer;
    private TextView promptView;
    private TextView modeChip;
    private LinearLayout historyRow;
    private HorizontalScrollView historyScroll;
    private LinearLayout historyChips;
    private volatile boolean running;
    private volatile CancelToken activeToken;
    private boolean termuxMode;

    private final LinkedHashSet<String> recent = new LinkedHashSet<>();   // distinct commands, newest last
    private int historyIdx = -1;                                          // -1 = input line, else index into history list

    private static final class Chunk {
        final String text;
        final boolean stderr;
        Chunk(String t, boolean e) { text = t; stderr = e; }
    }

    public TerminalScreen(Host host) { super(host); }

    @Override protected View build() {
        LinearLayout root = Ui.column(ctx);
        root.setPadding(Ui.dp(10), Ui.dp(4), Ui.dp(10), Ui.dp(4));

        output = Ui.text(ctx, "", Ui.MONO, Ui.P.text);
        output.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        output.setLineSpacing(0, 1.12f);
        output.setTextIsSelectable(false);
        output.setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(10), Ui.dp(8));
        outputScroll = new ScrollView(ctx);
        outputScroll.addView(output, new ViewGroup.LayoutParams(-1, -2));
        outputScroll.setOnScrollChangeListener(new View.OnScrollChangeListener() { @Override public void onScrollChange(View v, int x, int y, int ox, int oy) {
            TextView t = (TextView) outputScroll.getChildAt(0);
            if (t == null) return;
            autoFollow = y >= t.getHeight() - outputScroll.getHeight() - Ui.dp(48);
        }});
        FrameLayout outputCard = new FrameLayout(ctx);
        outputCard.setBackground(Ui.glass(16));
        outputCard.addView(outputScroll, new FrameLayout.LayoutParams(-1, -1));
        emptyOutput = Ui.text(ctx,
            "Терминал готов\n\nВведите команду в строке ниже или выберите команду из быстрых кнопок.\n" +
            "Команды выполняются в ограниченном Android shell без root.", Ui.CAPTION, Ui.P.text2);
        emptyOutput.setPadding(Ui.dp(16), Ui.dp(16), Ui.dp(16), Ui.dp(16));
        emptyOutput.setClickable(false);
        FrameLayout.LayoutParams emptyLp = new FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP);
        outputCard.addView(emptyOutput, emptyLp);
        LinearLayout.LayoutParams outLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        outLp.setMargins(0, 0, 0, Ui.dp(6));
        root.addView(outputCard, outLp);

        // last finished-command footer pill
        footer = Ui.chip(ctx, "", Ui.P.text3);
        footer.setVisibility(View.GONE);
        LinearLayout footRow = Ui.row(ctx);
        footRow.setPadding(Ui.dp(4), Ui.dp(2), Ui.dp(4), Ui.dp(2));
        footRow.addView(footer);
        root.addView(footRow);

        // history chips + up/down
        historyRow = Ui.row(ctx);
        historyRow.setPadding(Ui.dp(4), Ui.dp(2), Ui.dp(4), Ui.dp(2));
        historyRow.addView(Ui.iconButton(ctx, R.drawable.ic_arrow_up, 34, Ui.P.text2, new Runnable() { @Override public void run() { historyStep(-1); }}));
        historyRow.addView(Ui.iconButton(ctx, R.drawable.ic_arrow_down, 34, Ui.P.text2, new Runnable() { @Override public void run() { historyStep(1); }}));
        historyChips = Ui.row(ctx);
        historyScroll = new HorizontalScrollView(ctx);
        historyScroll.setHorizontalScrollBarEnabled(false);
        historyScroll.addView(historyChips, new ViewGroup.LayoutParams(-2, -2));
        historyRow.addView(historyScroll, Ui.lpw(1f));
        historyRow.setVisibility(View.GONE);
        root.addView(historyRow);

        // quick commands sit above the input line: mode switch, scrollable chips, help
        LinearLayout quickRow = Ui.row(ctx);
        quickRow.setPadding(Ui.dp(4), Ui.dp(2), Ui.dp(4), Ui.dp(6));
        modeChip = Ui.chip(ctx, "Shell", Ui.P.text2);
        modeChip.setClickable(true);
        modeChip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { toggleMode(); }});
        quickRow.addView(modeChip, Ui.lpm(-2, -2, 0, 0, 6, 0));
        HorizontalScrollView quickScroll = new HorizontalScrollView(ctx);
        quickScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout quickChips = Ui.row(ctx);
        for (final String q : QUICK) {
            TextView chip = Ui.chip(ctx, q, Ui.P.text2);
            chip.setTypeface(Ui.mono);
            chip.setBackground(Ui.glassPressable(20));
            chip.setClickable(true);
            chip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { runCommand(q); }});
            quickChips.addView(chip, Ui.lpm(-2, -2, 0, 0, 6, 0));
        }
        quickScroll.addView(quickChips);
        quickRow.addView(quickScroll, Ui.lpw(1f));
        quickRow.addView(Ui.iconButton(ctx, R.drawable.ic_info, 30, Ui.P.text2, new Runnable() { @Override public void run() { showQuickHelp(); }}),
            Ui.lpm(-2, -2, 4, 0, 0, 0));
        root.addView(quickRow);

        // input line: one bordered field; Run/Cancel is exactly as tall as the field
        LinearLayout inRow = Ui.row(ctx);
        inRow.setBackground(Ui.glass(14));
        inRow.setPadding(Ui.dp(12), Ui.dp(4), Ui.dp(4), Ui.dp(4));
        promptView = Ui.text(ctx, "~/ $", Ui.MONO, Ui.P.accent);
        inRow.addView(promptView);
        input = new EditText(ctx);
        input.setBackground(null);
        input.setHint("команда, например ls -la");
        input.setHintTextColor(Ui.P.text3);
        input.setTextColor(Ui.P.text);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        input.setTypeface(Ui.mono);
        input.setSingleLine(true);
        input.setMinHeight(Ui.dp(40));
        input.setPadding(Ui.dp(8), 0, Ui.dp(8), 0);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        input.setHighlightColor(Ui.P.accentSoft);
        try { if (android.os.Build.VERSION.SDK_INT >= 29) input.setTextCursorDrawable(Ui.cursor()); } catch (Throwable ignored) { }
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() { @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
            submit();
            return true;
        }});
        inRow.addView(input, Ui.lpw(1f));
        runBtn = Ui.button(ctx, "Пуск", R.drawable.ic_send, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() { submitOrCancel(); }});
        runBtn.setPadding(Ui.dp(12), 0, Ui.dp(14), 0);
        runLabel = (TextView) runBtn.getChildAt(runBtn.getChildCount() - 1);
        runIcon = runBtn.getChildAt(0) instanceof android.widget.ImageView ? (android.widget.ImageView) runBtn.getChildAt(0) : null;
        inRow.addView(runBtn, Ui.lpm(-2, Ui.dp(40), 6, 0, 0, 0));
        root.addView(inRow);

        // note line + actions
        LinearLayout noteRow = Ui.row(ctx);
        noteRow.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(2));
        noteRow.addView(Ui.iconButton(ctx, R.drawable.ic_copy, 34, Ui.P.text2, new Runnable() { @Override public void run() {
            if (buf.length() == 0) { host.overlays().toast("Вывод пуст", 0); return; }
            host.copyToClipboard("Вывод терминала", buf.toString());
            host.overlays().toast("Вывод скопирован (" + FileKit.size(buf.length()) + ")", 1);
        }}));
        noteRow.addView(Ui.iconButton(ctx, R.drawable.ic_trash, 34, Ui.P.text2, new Runnable() { @Override public void run() {
            buf.clear();
            output.setText("");
            updateEmptyOutput();
            footer.setVisibility(View.GONE);
        }}));
        TextView note = Ui.text(ctx, "Android shell (mksh/toybox), без root", Ui.CAPTION, Ui.P.text3);
        note.setPadding(Ui.dp(6), 0, 0, 0);
        noteRow.addView(note, Ui.lpw(1f));
        TextView more = Ui.text(ctx, "Подробнее", Ui.CAPTION, Ui.P.accent);
        more.setTypeface(Ui.medium);
        more.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(4), Ui.dp(8));
        more.setClickable(true);
        more.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showLimits(); }});
        noteRow.addView(more);
        root.addView(noteRow);
        return root;
    }

    @Override public String title() { return "Терминал"; }

    @Override public void onShow(String arg) {
        updatePrompt();
        refreshHistoryChips();
        if (arg != null && !arg.isEmpty() && input != null) { input.setText(arg); input.requestFocus(); }
    }

    private void toggleMode() {
        if (running) { host.overlays().toast("Дождитесь завершения команды", 0); return; }
        if (!termuxMode) {
            if (!TermuxBridge.installed(ctx)) { host.overlays().toast("Termux не установлен", 2); return; }
            if (!TermuxBridge.permissionGranted(ctx)) {
                host.overlays().toast("Выдайте разрешение моста: Настройки → Песочница и инструменты", 2);
                host.requestPermission(TermuxBridge.PERMISSION);
                return;
            }
        }
        termuxMode = !termuxMode;
        modeChip.setText(termuxMode ? "Termux" : "Shell");
        modeChip.setTextColor(termuxMode ? Ui.P.onAccent : Ui.P.text2);
        modeChip.setBackground(termuxMode ? Ui.accentFill(20) : Ui.glassPressable(20));
        updatePrompt();
    }

    private void updatePrompt() {
        if (promptView == null) return;
        promptView.setText(promptText());
    }

    private String promptText() {
        if (termuxMode) return "termux ~ $";
        String rel = j.sandbox.rel(j.shell.cwd());
        return rel.equals("/") ? "~/ $" : "~/" + rel + " $";
    }

    private void submitOrCancel() {
        if (running) {
            CancelToken t = activeToken;
            if (t != null) t.cancel("остановлено из терминала");
            return;
        }
        submit();
    }

    private void submit() {
        String cmd = input.getText().toString().trim();
        if (cmd.isEmpty()) { host.overlays().toast("Введите команду", 0); return; }
        runCommand(cmd);
    }

    /** Runs a command (also used by preview scenes to exercise the real path). */
    public void runCommand(String cmd) {
        if (running) { host.overlays().toast("Дождитесь завершения или нажмите «Стоп»", 0); return; }
        input.setText("");
        historyIdx = -1;
        recent.remove(cmd);
        recent.add(cmd);
        while (recent.size() > 12) { java.util.Iterator<String> it = recent.iterator(); it.next(); it.remove(); }
        refreshHistoryChips();

        appendEcho(cmd);
        setRunning(true);
        final CancelToken token = new CancelToken();
        activeToken = token;
        final long t0 = System.nanoTime();
        if (termuxMode) { runInTermux(cmd, token, t0); return; }
        final String command = cmd;
        final ShellExec.Sink sink = new ShellExec.Sink() { @Override public void chunk(String text, boolean stderr) { enqueue(text, stderr); } };
        new Thread(new Runnable() { @Override public void run() {
            try {
                ShellExec.Result r = j.shell.run(command, null, TIMEOUT_MS, token, sink);
                final ShellExec.Result res = r;
                j.main.post(new Runnable() { @Override public void run() { finish(res, System.nanoTime() - t0); }});
            } catch (final IOException e) {
                j.main.post(new Runnable() { @Override public void run() {
                    enqueue("\n[shell] " + e.getMessage() + "\n", true);
                    completeRun("ошибка запуска", false);
                }});
            }
        }}, "jv-term-cmd").start();
    }

    private void runInTermux(final String command, final CancelToken token, final long t0) {
        final String id = "term" + Long.toString(System.nanoTime() & 0xffffffffL, 36);
        final String wrapper = TermuxCommand.wrapper(id, command, null, null, (int) (TIMEOUT_MS / 1000));
        new Thread(new Runnable() { @Override public void run() {
            TermuxTool.Reply rep = null;
            String error = null;
            try {
                rep = j.termux.exec(id, wrapper, TIMEOUT_MS + TermuxTool.GRACE_MS, token);
                if (rep == null) j.termux.kill(id);
            } catch (IOException e) {
                error = e.getMessage();
            }
            final TermuxTool.Reply reply = rep;
            final String err = error;
            final boolean cancelled = token.isCancelled();
            j.main.post(new Runnable() { @Override public void run() { finishTermux(reply, err, cancelled, System.nanoTime() - t0); }});
        }}, "jv-term-termux").start();
    }

    private void finish(ShellExec.Result r, long nano) {
        String dur = FileKit.duration(nano / 1_000_000);
        if (r.timedOut || r.cancelled) enqueue(r.cancelled ? "[остановлено]\n" : "[превышен таймаут 10 мин]\n", true);
        String label = r.cancelled ? "отменено" : r.timedOut ? "таймаут" : "exit " + r.exitCode;
        completeRun(label + " · " + dur, r.exitCode == 0 && !r.cancelled && !r.timedOut);
        j.journal.i(app.jarvis.core.log.Journal.Cat.TERMINAL, r.cancelled ? "Команда отменена: " + r.cwd
            : "Команда выполнена: exit " + r.exitCode + " за " + dur, null, null, r.durationMs);
    }

    private void finishTermux(TermuxTool.Reply r, String err, boolean cancelled, long nano) {
        String dur = FileKit.duration(nano / 1_000_000);
        if (err != null) {
            enqueue("[termux] " + err + "\n", true);
            completeRun("мост недоступен · " + dur, false);
            return;
        }
        if (r == null) {
            enqueue(cancelled ? "[остановлено]\n" : "[Termux не ответил]\n", true);
            completeRun((cancelled ? "отменено" : "нет ответа") + " · " + dur, false);
            return;
        }
        if (!r.stdout.isEmpty()) enqueue(r.stdout.endsWith("\n") ? r.stdout : r.stdout + "\n", false);
        if (!r.stderr.isEmpty()) enqueue(r.stderr.endsWith("\n") ? r.stderr : r.stderr + "\n", true);
        if (r.err != 0) {
            enqueue("[termux] " + (r.errmsg.isEmpty() ? "код " + r.err : r.errmsg) + "\n", true);
            completeRun("Termux отказал · " + dur, false);
            return;
        }
        completeRun("Termux exit " + r.exitCode + " · " + dur, r.exitCode == 0);
        j.journal.i(app.jarvis.core.log.Journal.Cat.TERMINAL, "Команда Termux выполнена: exit " + r.exitCode + " за " + dur, null, null, nano / 1_000_000L);
    }

    /** Ends a run: stops the spinner, shows the footer pill and a labelled divider after everything already queued. */
    private void completeRun(String label, boolean ok) {
        setRunning(false);
        flush();
        showPill(label, ok);
        appendDivider(label, ok);
        updatePrompt();
    }

    private void setRunning(boolean on) {
        running = on;
        runLabel.setText(on ? "Стоп" : "Пуск");
        runBtn.setBackground(on ? Ui.glassPressable(14) : Ui.accentFill(14));
        runLabel.setTextColor(on ? Ui.P.err : Ui.P.onAccent);
        if (runIcon != null) {
            runIcon.setImageResource(on ? R.drawable.ic_stop : R.drawable.ic_send);
            runIcon.setColorFilter(on ? Ui.P.err : Ui.P.onAccent);
        }
        input.setEnabled(!on);
        input.setAlpha(on ? 0.5f : 1f);
    }

    private void updateEmptyOutput() {
        if (emptyOutput != null) emptyOutput.setVisibility(buf.length() == 0 ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------------------------------------- output buffer
    private void enqueue(String text, boolean stderr) {
        synchronized (pending) { pending.add(new Chunk(text, stderr)); }
        if (!flushScheduled) {
            flushScheduled = true;
            handler.postDelayed(flusher, FLUSH_MS);
        }
    }

    private void flush() {
        flushScheduled = false;
        ArrayDeque<Chunk> batch;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            batch = new ArrayDeque<>(pending);
            pending.clear();
        }
        for (Chunk ch : batch) {
            int start = buf.length();
            buf.append(ch.text);
            int color = ch.stderr ? Ui.P.err : Ui.P.text;
            buf.setSpan(new ForegroundColorSpan(color), start, buf.length(), 0);
        }
        enforceCap();
        output.setText(buf);
        updateEmptyOutput();
        if (autoFollow) outputScroll.post(new Runnable() { @Override public void run() { outputScroll.fullScroll(View.FOCUS_DOWN); }});
    }

    /** Keeps only the newest ~CAP chars: drops whole old lines from the front (spans shift automatically). */
    private void enforceCap() {
        if (buf.length() <= CAP + 8_000) return;
        int cut = buf.toString().indexOf('\n', buf.length() - CAP);
        if (cut <= 0) cut = buf.length() - CAP;
        buf.delete(0, cut + 1);
    }

    private void appendEcho(String cmd) {
        flush();
        int start = buf.length();
        String prompt = promptText() + " ";
        buf.append(prompt).append(cmd).append('\n');
        buf.setSpan(new ForegroundColorSpan(Ui.P.accent), start, buf.length(), 0);
        buf.setSpan(new StyleSpan(Typeface.BOLD), start, start + prompt.length(), 0);
        output.setText(buf);
        updateEmptyOutput();
        outputScroll.post(new Runnable() { @Override public void run() { outputScroll.fullScroll(View.FOCUS_DOWN); }});
    }

    private void appendDivider(String label, boolean ok) {
        int start = buf.length();
        StringBuilder sb = new StringBuilder("── ").append(label).append(' ');
        for (int i = sb.length(); i < 36; i++) sb.append('─');
        buf.append(sb.toString()).append("\n\n");
        buf.setSpan(new ForegroundColorSpan(ok ? Ui.P.text3 : Ui.P.err), start, buf.length(), 0);
        output.setText(buf);
        updateEmptyOutput();
        if (autoFollow) outputScroll.fullScroll(View.FOCUS_DOWN);
    }

    private void showPill(String text, boolean ok) {
        footer.setText(text);
        footer.setTextColor(ok ? Ui.P.ok : Ui.P.err);
        footer.setBackground(Ui.glassTinted(20, Ui.alpha(ok ? Ui.P.ok : Ui.P.err, 0.14f)));
        footer.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------------------------------------------ history
    private void refreshHistoryChips() {
        List<app.jarvis.core.tools.ShellSession.Entry> hist = j.shell.history();
        historyRow.setVisibility(hist.isEmpty() && recent.isEmpty() ? View.GONE : View.VISIBLE);
        historyChips.removeAllViews();
        java.util.List<String> shown = new java.util.ArrayList<>(recent);
        java.util.Collections.reverse(shown);
        if (shown.isEmpty()) return;
        Context c = ctx;
        for (final String cmd : shown) {
            TextView chip = Ui.chip(c, cmd, Ui.P.text2);
            chip.setTypeface(Ui.mono);
            chip.setBackground(Ui.glassPressable(20));
            chip.setClickable(true);
            chip.setMaxWidth(Ui.dp(160));
            chip.setSingleLine(true);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            chip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { input.setText(cmd); input.setSelection(cmd.length()); input.requestFocus(); }});
            historyChips.addView(chip, Ui.lpm(-2, -2, 0, 0, 6, 0));
        }
    }

    /** dir -1 = older (up), 1 = newer (down). Walks j.shell.history() and fills the input. */
    private void historyStep(int dir) {
        List<app.jarvis.core.tools.ShellSession.Entry> hist = j.shell.history();
        if (hist.isEmpty()) return;
        int idx = historyIdx + (dir < 0 ? -1 : 1);
        if (historyIdx < 0 && dir < 0) idx = hist.size() - 1;         // first Up jumps to the newest
        if (idx >= hist.size()) { historyIdx = -1; input.setText(""); return; }
        if (idx < 0) idx = 0;
        historyIdx = idx;
        String cmd = hist.get(idx).command;
        input.setText(cmd);
        input.setSelection(Math.min(cmd.length(), 120));
        input.requestFocus();
    }

    // ------------------------------------------------------------------------------------------------- help sheets
    private void showQuickHelp() {
        LinearLayout b = Ui.column(ctx);
        b.setPadding(Ui.dp(20), Ui.dp(4), Ui.dp(20), Ui.dp(12));
        String[] lines = {
            "ls — список файлов в текущем каталоге.",
            "pwd — путь к текущему каталогу.",
            "df -h — свободное место на разделах.",
            "uname -a — версия ядра и архитектура устройства.",
            "ps — запущенные процессы, которые видны приложению.",
            "date — текущие дата и время.",
            "Нажатие на подсказку сразу запускает команду. Режим Termux отправляет любую команду в Termux (python3, git, node).",
        };
        for (String line : lines) {
            TextView t = Ui.text(ctx, line, Ui.BODY, Ui.P.text2);
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f);
            b.addView(t, Ui.lpm(-1, -2, 0, 8, 0, 0));
        }
        final app.jarvis.ui.OverlayHost.Layer[] ly = new app.jarvis.ui.OverlayHost.Layer[1];
        ly[0] = app.jarvis.ui.Dialogs.sheet(host, "Быстрые команды", b, true);
        b.addView(Ui.button(ctx, "Закрыть", 0, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() { host.overlays().dismiss(ly[0]); }}), Ui.lpm(-1, -2, 0, 14, 0, 0));
    }

    // -------------------------------------------------------------------------------------------------- limits sheet
    public void showLimits() {
        LinearLayout b = Ui.column(ctx);
        b.setPadding(Ui.dp(20), Ui.dp(4), Ui.dp(20), Ui.dp(12));
        String[] lines = {
            "Это обычная оболочка Android: mksh + toybox, без root. Доступны только каталоги, разрешённые приложению.",
            "Запуск идёт без TTY: интерактивные программы (vim, top, less) полноценно не работают.",
            "У одной команды таймаут 10 минут; её можно остановить кнопкой «Стоп» (завершается всё дерево процессов).",
            "Вывод ограничен: в памяти экрана держится ~200 КБ, внутри команды — ~100 КБ на поток (потерянный середина помечается).",
            "Стартовый каталог — песочница (~). PATH включает sandbox/bin, поэтому скачанные в bin инструменты доступны по имени.",
            "Ввод у команды отключён (/dev/null): команды должны завершаться сами.",
            "Режим Termux: команды идут в настоящий Termux (python3, git, node). Вывод приходит целиком по окончании; нужен мост, см. «Песочница и инструменты».",
        };
        for (String line : lines) {
            TextView t = Ui.text(ctx, line, Ui.BODY, Ui.P.text2);
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f);
            b.addView(t, Ui.lpm(-1, -2, 0, 8, 0, 0));
        }
        final app.jarvis.ui.OverlayHost.Layer[] ly = new app.jarvis.ui.OverlayHost.Layer[1];
        ly[0] = app.jarvis.ui.Dialogs.sheet(host, "Как это работает", b, true);
        b.addView(Ui.button(ctx, "Закрыть", 0, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() { host.overlays().dismiss(ly[0]); }}), Ui.lpm(-1, -2, 0, 14, 0, 0));
    }
}
