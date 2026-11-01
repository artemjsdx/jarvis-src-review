package app.jarvis.ui.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Layout;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.llm.ToolSpec;
import app.jarvis.core.tools.MemoryOrder;
import app.jarvis.core.tools.MemoryStore;
import app.jarvis.core.tools.TermuxCommand;
import app.jarvis.core.tools.TermuxTool;
import app.jarvis.core.util.CancelToken;
import app.jarvis.data.Settings;
import app.jarvis.rt.TermuxBridge;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.TimeZone;

/**
 * Sandbox & tools: sandbox location/size/free space, opt-in access to shared storage, the real agent tool list
 * with expandable JSON schemas, and the assistant memory viewer (view/edit/delete/clear).
 */
public class SandboxPage extends SettingsPage {
    private LinearLayout content;
    private LinearLayout memoryHost;
    private TextView sizeValue;
    private TextView memoryCount;

    public SandboxPage(Host host) { super(host); }

    @Override public String title() { return "Песочница и инструменты"; }

    @Override protected View build() {
        ScrollView sv = Ui.scroll(ctx);
        sv.setFillViewport(true);
        content = Ui.column(ctx);
        content.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(24));

        content.addView(Widgets.sectionTitle(ctx, "Песочница"));
        content.addView(sandboxCard());
        computeSize();

        content.addView(Widgets.sectionTitle(ctx, "Что умеет агент"));
        content.addView(capabilitiesCard());

        content.addView(Widgets.sectionTitle(ctx, "Мост в Termux"));
        content.addView(bridgeCard());

        content.addView(Widgets.sectionTitle(ctx, "Инструменты агента"));
        content.addView(toolsCard());

        content.addView(Widgets.sectionTitle(ctx, "Память ассистента"));
        content.addView(memoryCard());

        sv.addView(content);
        return sv;
    }

    private View sandboxCard() {
        LinearLayout card = Widgets.card(ctx);
        card.addView(Widgets.row(ctx, R.drawable.ic_folder, "Расположение", j.sandbox.root.getPath(), null, null));
        Widgets.divide(card);
        LinearLayout sizeRow = Widgets.row(ctx, R.drawable.ic_archive, "Занято", "считаю…", null, null);
        sizeValue = findSubtitle(sizeRow);
        card.addView(sizeRow);
        Widgets.divide(card);
        long free = 0;
        try { free = j.sandbox.root.getUsableSpace(); } catch (Throwable ignored) { }
        card.addView(Widgets.row(ctx, R.drawable.ic_drive, "Свободно на диске", free > 0 ? PageUi.humanSize(free) : "неизвестно", null, null));
        Widgets.divide(card);
        card.addView(Widgets.row(ctx, R.drawable.ic_external, "Доступ к общей памяти", "Разрешить инструментам читать /sdcard (нужен допуск Android)",
            Widgets.toggle(ctx, j.settings.getBool(Settings.SANDBOX_EXTERNAL, false), new Widgets.Toggle.Listener() { @Override public void onChanged(boolean on) {
                if (on && !host.hasPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)) host.requestPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
                j.settings.setBool(Settings.SANDBOX_EXTERNAL, on);
                host.overlays().toast(on ? "Общая память разрешена" : "Доступ к общей памяти закрыт", 0);
            } }), null));
        return card;
    }

    private View capabilitiesCard() {
        LinearLayout card = Widgets.card(ctx);
        card.addView(Widgets.row(ctx, R.drawable.ic_wrench, "shell_exec — Android shell",
            "mksh и toybox с правами приложения. Не Linux и не root: часть команд (apt, sudo, /system) недоступна. Работает в каталогах песочницы.", null, null));
        Widgets.divide(card);
        card.addView(Widgets.row(ctx, R.drawable.ic_external, "termux_exec — Termux",
            TermuxBridge.installed(ctx)
                ? "Полноценный Linux-терминал Termux: python3, git, node, curl. Нужен мост ниже."
                : "Появится, когда установлен Termux (F-Droid или GitHub). Без него агент пользуется только shell_exec.", null, null));
        return card;
    }

    private View bridgeCard() {
        LinearLayout card = Widgets.card(ctx);
        boolean installed = TermuxBridge.installed(ctx);
        boolean granted = TermuxBridge.permissionGranted(ctx);
        card.addView(Widgets.row(ctx, R.drawable.ic_wrench, "Termux", installed ? "установлен" : "не установлен", null, null));
        Widgets.divide(card);
        LinearLayout permRow = Widgets.row(ctx, R.drawable.ic_external, "Разрешение моста",
            granted ? "выдано" : "не выдано: без него termux_exec и терминальный режим Termux не работают", granted ? null
                : Ui.button(ctx, "Выдать", 0, Ui.BTN_GHOST, new Runnable() { @Override public void run() {
                    host.requestPermission(TermuxBridge.PERMISSION);
                } }), null);
        card.addView(permRow);
        Widgets.divide(card);
        LinearLayout checkRow = Widgets.row(ctx, R.drawable.ic_refresh, "Проверка связи",
            "отправляет в Termux `echo` и показывает ответ (нужен allow-external-apps = true в ~/.termux/termux.properties)", null, null);
        final TextView status = (TextView) checkRow.findViewWithTag("subtitle");
        if (status != null) { status.setMaxLines(Integer.MAX_VALUE); status.setEllipsize(null); }
        card.addView(checkRow);
        card.addView(Ui.button(ctx, "Проверить", 0, Ui.BTN_GLASS, new Runnable() { @Override public void run() {
            runBridgeCheck(status);
        } }), Ui.lpm(-1, -2, 16, 4, 16, 14));
        return card;
    }

    private void runBridgeCheck(final TextView status) {
        if (status == null) return;
        status.setText("проверяю…");
        final String id = "check" + Long.toString(System.nanoTime() & 0xffffffffL, 36);
        final String wrapper = TermuxCommand.wrapper(id, "echo jarvis-ok; uname -sm; python3 -V 2>&1 | head -1", null, null, 20);
        new Thread(new Runnable() { @Override public void run() {
            String msg;
            try {
                TermuxTool.Reply r = j.termux.exec(id, wrapper, 30_000, new CancelToken());
                if (r == null) {
                    j.termux.kill(id);
                    msg = "нет ответа за 30 с: проверьте разрешение и allow-external-apps";
                } else if (r.err != 0) {
                    msg = "Termux отказал: " + (r.errmsg.isEmpty() ? "код " + r.err : r.errmsg);
                } else {
                    msg = r.stdout.trim().replace("\n", " · ");
                }
            } catch (IOException e) {
                msg = e.getMessage();
            }
            final String text = msg;
            j.main.post(new Runnable() { @Override public void run() { status.setText(text); } });
        } }, "jv-termux-check").start();
    }

    private static TextView findSubtitle(LinearLayout row) {
        View tx = row.getChildAt(row.getChildCount() > 2 ? 1 : 0);
        if (tx instanceof LinearLayout) {
            View s = ((LinearLayout) tx).findViewWithTag("subtitle");
            if (s instanceof TextView) return (TextView) s;
        }
        return null;
    }

    /** Text column inside a Widgets.row: index 0 without an icon, index 1 when the icon badge (FrameLayout) is first. */
    private static LinearLayout textColumn(LinearLayout row) {
        return (LinearLayout) row.getChildAt(row.getChildAt(0) instanceof LinearLayout ? 0 : 1);
    }

    private void computeSize() {
        if (sizeValue != null) sizeValue.setText("считаю…");
        Thread th = new Thread(new Runnable() { @Override public void run() {
            final long[] total = {0};
            walk(j.sandbox.root, total, 0);
            j.main.post(new Runnable() { @Override public void run() { if (sizeValue != null) sizeValue.setText(PageUi.humanSize(total[0])); } });
        } }, "jv-sandbox-size");
        th.setDaemon(true);
        th.start();
    }

    private static void walk(File f, long[] total, int depth) {
        if (depth > 12 || total[0] > 50L * 1024 * 1024 * 1024) return;
        File[] fs = f.listFiles();
        if (fs == null) {
            total[0] += f.length();
            return;
        }
        for (File x : fs) {
            if (x.isDirectory()) walk(x, total, depth + 1);
            else total[0] += x.length();
        }
    }

    @Override public void onPermissionResult(String permission, boolean granted) {
        if (android.Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permission) && !granted && j.settings.getBool(Settings.SANDBOX_EXTERNAL, false)) {
            host.overlays().toast("Android не дал доступ к общей памяти — файлы за пределами песочницы останутся недоступны", 2);
        }
    }

    // --------------------------------------------------------------------------- tools
    private View toolsCard() {
        LinearLayout card = Widgets.card(ctx);
        List<ToolSpec> specs = j.tools.specs();
        if (specs.isEmpty()) {
            card.addView(PageUi.note(ctx, "Инструменты не зарегистрированы."), Ui.lpm(-2, -2, 16, Ui.dp(12), Ui.dp(16), Ui.dp(12)));
            return card;
        }
        for (final ToolSpec s : specs) {
            String pretty;
            try { pretty = s.parameters.toString(2); } catch (Exception e) { pretty = s.parameters.toString(); }
            LinearLayout row = Widgets.row(ctx, R.drawable.ic_wrench, s.name, s.description, null, null);
            LinearLayout tx = textColumn(row);
            final TextView schema = Ui.text(ctx, pretty, Ui.MONO, Ui.P.text3);
            schema.setVisibility(View.GONE);
            tx.addView(schema, Ui.lpm(-2, -2, 0, 6, 0, 0));
            row.setBackground(Ui.rippleOn(new android.graphics.drawable.ColorDrawable(0), 14));
            row.setClickable(true);
            row.setFocusable(true);
            row.setContentDescription("Инструмент " + s.name + ". Показать параметры");
            row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                boolean expanding = schema.getVisibility() != View.VISIBLE;
                schema.setVisibility(expanding ? View.VISIBLE : View.GONE);
                v.setContentDescription("Инструмент " + s.name
                    + (expanding ? ". Скрыть параметры" : ". Показать параметры"));
            } });
            card.addView(row);
            Widgets.divide(card);
        }
        card.removeView(card.getChildAt(card.getChildCount() - 1));
        return card;
    }

    // --------------------------------------------------------------------------- memory
    private View memoryCard() {
        LinearLayout card = Widgets.card(ctx);
        LinearLayout head = Ui.row(ctx);
        head.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(14), Ui.dp(6));
        LinearLayout tx = Ui.column(ctx);
        TextView t = Ui.text(ctx, "Что ассистент запомнил", Ui.BODY);
        t.setTypeface(Ui.medium);
        tx.addView(t);
        memoryCount = PageUi.note(ctx, "");
        tx.addView(memoryCount, Ui.lpm(-2, -2, 0, 2, 0, 0));
        head.addView(tx, Ui.lpw(1f));
        card.addView(head);
        LinearLayout actions = PageUi.buttonRow(ctx,
            Ui.button(ctx, "Добавить", R.drawable.ic_plus, Ui.BTN_GLASS, new Runnable() { @Override public void run() { editMemory(null); } }),
            Ui.button(ctx, "Очистить", R.drawable.ic_trash, Ui.BTN_DANGER, new Runnable() { @Override public void run() {
            Dialogs.confirm(host, "Очистить память?", "Ассистент забудет все записи: предпочтения, привычки, факты о вас.", "Очистить", true, new Runnable() { @Override public void run() {
                try {
                    j.memory.clear();
                    renderMemory();
                    host.overlays().toast("Память очищена", 0);
                } catch (IOException e) {
                    host.overlays().toast("Не удалось: " + e.getMessage(), 2);
                }
            } });
        } }));
        card.addView(actions, Ui.lpm(-1, -2, 16, 4, 16, 14));
        Widgets.divide(card);
        memoryHost = Ui.column(ctx);
        card.addView(memoryHost);
        renderMemory();
        return card;
    }

    private void renderMemory() {
        if (memoryHost == null) return;
        memoryHost.removeAllViews();
        List<MemoryStore.Entry> all = j.memory.all();
        memoryCount.setText(all.isEmpty() ? "пока ничего" : "записей: " + all.size());
        if (all.isEmpty()) {
            LinearLayout e = Widgets.empty(ctx, R.drawable.ic_file_text, "Память пуста",
                "Ассистент запоминает предпочтения сам — и всё, что он запомнил, можно удалить здесь");
            memoryHost.addView(e);
            return;
        }
        int i = 0;
        for (final MemoryStore.Entry en : MemoryOrder.newestFirst(all)) {
            if (i > 0) Widgets.divide(memoryHost);
            FrameLayout trail = new FrameLayout(ctx);
            trail.addView(Ui.iconButton(ctx, R.drawable.ic_trash, 36, Ui.P.err, new Runnable() { @Override public void run() {
                confirmForget(en);
            } }), new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            LinearLayout row = Widgets.row(ctx, R.drawable.ic_file_text, en.key, en.value, trail, new Runnable() { @Override public void run() { showEntry(en); } });
            row.setTag("mem." + en.key);
            TextView sub = (TextView) row.findViewWithTag("subtitle");
            if (sub != null) {
                sub.setMaxLines(3);
                sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
                sub.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
                sub.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NORMAL);
                sub.setText(en.value + "\n" + MemoryOrder.stamp(en.updated, TimeZone.getDefault()));
            }
            memoryHost.addView(row);
            i++;
        }
    }

    /** Full text of one memory entry with edit, copy and delete; long notes scroll inside the sheet. */
    private void showEntry(final MemoryStore.Entry en) {
        LinearLayout body = Ui.column(ctx);
        TextView text = Ui.text(ctx, en.value, Ui.BODY, Ui.P.text);
        text.setTextIsSelectable(true);
        text.setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY);
        text.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
        body.addView(text);
        body.addView(PageUi.note(ctx, "Сохранено: " + MemoryOrder.stamp(en.updated, TimeZone.getDefault())), Ui.lpm(-1, -2, 0, 12, 0, 0));
        final OverlayHost.Layer[] ly = new OverlayHost.Layer[1];
        View edit = Ui.button(ctx, "Изменить", R.drawable.ic_edit, Ui.BTN_GHOST, new Runnable() { @Override public void run() {
            host.overlays().dismiss(ly[0]);
            editMemory(en);
        } });
        View copy = Ui.button(ctx, "Копировать", R.drawable.ic_copy, Ui.BTN_GHOST, new Runnable() { @Override public void run() {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(en.key, en.value));
            host.overlays().toast("Скопировано", 0);
        } });
        View forget = Ui.button(ctx, "Удалить", R.drawable.ic_trash, Ui.BTN_DANGER, new Runnable() { @Override public void run() {
            host.overlays().dismiss(ly[0]);
            confirmForget(en);
        } });
        body.addView(PageUi.buttonRow(ctx, edit, copy), Ui.lpm(-1, -2, 0, 16, 0, 0));
        body.addView(forget, Ui.lpm(-1, -2, 0, 8, 0, 0));
        ly[0] = Dialogs.sheet(host, en.key, body, true);
    }

    private void confirmForget(final MemoryStore.Entry en) {
        Dialogs.confirm(host, "Забыть «" + en.key + "»?", en.value, "Забыть", true, new Runnable() { @Override public void run() {
            try {
                j.memory.delete(en.key);
                renderMemory();
            } catch (IOException e) {
                host.overlays().toast("Не удалось: " + e.getMessage(), 2);
            }
        } });
    }

    private void editMemory(final MemoryStore.Entry existing) {
        Dialogs.prompt(host, existing == null ? "Новая запись" : "Изменить запись",
            existing == null ? "Ключ, например «любимый напиток»" : "Ключ", existing == null ? "" : existing.key, false, false, "Дальше",
            new Dialogs.StringListener() { @Override public void on(String key) {
                final String k = key.trim();
                if (k.isEmpty()) { host.overlays().toast("Ключ не может быть пустым", 2); return; }
                Dialogs.prompt(host, "Значение для «" + k + "»", "Что запомнить", existing == null ? "" : existing.value, true, false, "Сохранить",
                    new Dialogs.StringListener() { @Override public void on(String value) {
                        try {
                            j.memory.put(k, value.trim());
                            renderMemory();
                        } catch (IOException e) {
                            host.overlays().toast("Не удалось сохранить: " + e.getMessage(), 2);
                        }
                    } });
            } });
    }
}
