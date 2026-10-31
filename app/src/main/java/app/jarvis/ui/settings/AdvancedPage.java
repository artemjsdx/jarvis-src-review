package app.jarvis.ui.settings;

import android.os.Build;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.log.Journal;
import app.jarvis.data.Settings;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Advanced: build/device info, journal size and minimum level, destructive maintenance actions
 * (settings reset keeping API keys, chat history clear) and the previous crash report viewer.
 */
public class AdvancedPage extends SettingsPage {
    private static final String LOG_LEVEL_KEY = "log.level";   // applied to the journal live; startup default stays DEBUG

    private LinearLayout content;
    private TextView rowCount;

    public AdvancedPage(Host host) { super(host); }

    @Override public String title() { return "Дополнительно"; }

    @Override protected View build() {
        ScrollView sv = Ui.scroll(ctx);
        sv.setFillViewport(true);
        content = Ui.column(ctx);
        content.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(24));

        content.addView(Widgets.sectionTitle(ctx, "О приложении"));
        LinearLayout info = Widgets.card(ctx);
        info.addView(Widgets.row(ctx, R.drawable.ic_info, "Версия", app.jarvis.BuildConfig.VERSION_NAME + " · git " + app.jarvis.BuildConfig.GIT_REV, null, null));
        Widgets.divide(info);
        info.addView(Widgets.row(ctx, R.drawable.ic_cpu, "Android", "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")", null, null));
        Widgets.divide(info);
        info.addView(Widgets.row(ctx, R.drawable.ic_drive, "Устройство", Build.MANUFACTURER + " " + Build.MODEL, null, null));
        Widgets.divide(info);
        info.addView(Widgets.row(ctx, R.drawable.ic_layers, "Архитектура", Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?", null, null));
        content.addView(info);

        content.addView(Widgets.sectionTitle(ctx, "Журнал"));
        View jc = journalCard();
        jc.setTag("journalCard");
        content.addView(jc);

        content.addView(Widgets.sectionTitle(ctx, "Обслуживание"));
        content.addView(maintenanceCard());

        content.addView(Widgets.sectionTitle(ctx, "Отчёты о сбоях"));
        content.addView(crashCard());

        sv.addView(content);
        return sv;
    }

    @Override public void onShow() { applyMinLevel(); refreshCount(); }

    private void refreshCount() {
        if (rowCount == null) return;
        rowCount.setText("записей: " + PageUi.group(j.journal.count()));
    }

    /** Re-applies the stored minimum level to the journal (also done on change). */
    private void applyMinLevel() {
        try {
            String v = j.settings.get(LOG_LEVEL_KEY, "");
            if (!v.isEmpty()) j.journal.setMinLevel(Journal.Level.valueOf(v));
        } catch (Throwable ignored) { }
    }

    private View journalCard() {
        LinearLayout card = Widgets.card(ctx);
        LinearLayout countRow = Widgets.row(ctx, R.drawable.ic_log, "Записей в журнале", "", null, null);
        rowCount = findSubtitle(countRow);
        refreshCount();
        card.addView(countRow);
        Widgets.divide(card);
        LinearLayout maxRow = Ui.column(ctx);
        maxRow.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        maxRow.addView(PageUi.caption(ctx, "Хранить не больше строк"));
        android.widget.EditText e = PageUi.saveField(ctx, "20000", String.valueOf(j.settings.getInt(Settings.LOG_MAX_ROWS, 20000)), new PageUi.TextListener() { @Override public void on(String s) {
            try {
                int v = Integer.parseInt(s.trim());
                if (v < 1000 || v > 1000000) throw new NumberFormatException();
                j.settings.set(Settings.LOG_MAX_ROWS, String.valueOf(v));
                host.overlays().toast("Сохранено", 1);
            } catch (NumberFormatException err) {
                host.overlays().toast("Введите число от 1000 до 1000000", 2);
            }
        } });
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        maxRow.addView(Widgets.fieldBox(ctx, e), Ui.lpm(-1, -2, 0, Ui.dp(8), 0, 0));
        card.addView(maxRow);
        Widgets.divide(card);
        String lvl = j.settings.get(LOG_LEVEL_KEY, "");
        Journal.Level cur = Journal.Level.DEBUG;
        try { if (!lvl.isEmpty()) cur = Journal.Level.valueOf(lvl); } catch (IllegalArgumentException ignored) { }
        String[] labels = {"Подробный (отладка)", "Обычный", "Предупреждения", "Только ошибки"};
        int sel = Math.max(0, Math.min(3, cur.ordinal()));
        card.addView(Widgets.row(ctx, R.drawable.ic_sliders, "Минимальный уровень", labels[sel], null, new Runnable() { @Override public void run() {
            String current = j.settings.get(LOG_LEVEL_KEY, "");
            Journal.Level c;
            try { c = current.isEmpty() ? Journal.Level.DEBUG : Journal.Level.valueOf(current); } catch (IllegalArgumentException x) { c = Journal.Level.DEBUG; }
            Dialogs.choose(host, "Минимальный уровень журнала", new String[]{"Подробный (отладка)", "Обычный", "Предупреждения", "Только ошибки"}, c.ordinal(),
                new Dialogs.IndexListener() { @Override public void on(int index) {
                    Journal.Level pick = Journal.Level.values()[index];
                    j.settings.set(LOG_LEVEL_KEY, pick.name());
                    j.journal.setMinLevel(pick);
                    rebuildJournalSection();
                } });
        } }));
        return card;
    }

    private void rebuildJournalSection() {
        // rebuild only the journal card to show the new level (index of the card inside content is stable)
        for (int i = 0; i < content.getChildCount(); i++) {
            View v = content.getChildAt(i);
            Object tag = v.getTag();
            if (tag != null && "journalCard".equals(tag)) {
                View fresh = journalCard();
                fresh.setTag("journalCard");
                content.removeViewAt(i);
                content.addView(fresh, i);
                return;
            }
        }
    }

    private static TextView findSubtitle(LinearLayout row) {
        View tx = row.getChildAt(row.getChildAt(0) instanceof LinearLayout ? 0 : 1);
        if (tx instanceof LinearLayout) {
            View s = ((LinearLayout) tx).findViewWithTag("subtitle");
            if (s instanceof TextView) return (TextView) s;
        }
        return null;
    }

    private View maintenanceCard() {
        LinearLayout card = Widgets.card(ctx);
        LinearLayout pad = Ui.column(ctx);
        pad.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        pad.addView(Ui.button(ctx, "Сбросить настройки", R.drawable.ic_rotate, Ui.BTN_DANGER, new Runnable() { @Override public void run() {
            Dialogs.confirm(host, "Сбросить настройки?", "Все параметры вернутся к значениям по умолчанию. Ключи API останутся на месте.", "Сбросить", true, new Runnable() { @Override public void run() {
                Map<String, String> all = j.settings.snapshot();
                for (String k : all.keySet()) j.settings.remove(k);
                host.overlays().toast("Настройки сброшены", 1);
            } });
        } }));
        pad.addView(Ui.space(ctx, 1, Ui.dp(8)));
        pad.addView(Ui.button(ctx, "Очистить историю чатов", R.drawable.ic_trash, Ui.BTN_DANGER, new Runnable() { @Override public void run() {
            Dialogs.confirm(host, "Очистить историю чатов?", "Все разговоры будут удалены без возможности восстановления. Память ассистента и журнал не трогаем.", "Удалить всё", true, new Runnable() { @Override public void run() {
                int n = 0;
                for (app.jarvis.data.ChatStore.Conv c : j.chat.conversations()) { j.chat.deleteConversation(c.id); n++; }
                host.overlays().toast("Удалено разговоров: " + n, 0);
            } });
        } }));
        pad.addView(PageUi.note(ctx, "Ключи API хранятся отдельно и не удаляются этими действиями."), Ui.lpm(-2, -2, 0, Ui.dp(8), 0, 0));
        card.addView(pad);
        return card;
    }

    private View crashCard() {
        LinearLayout card = Widgets.card(ctx);
        final File f = new File(j.home, "crash/previous.txt");
        boolean has = f.isFile() && f.length() > 0;
        card.addView(Widgets.row(ctx, R.drawable.ic_bug, "Предыдущий сбой", has ? "отчёт сохранён" : "отчётов нет",
            null, has ? new Runnable() { @Override public void run() {
                Thread th = new Thread(new Runnable() { @Override public void run() {
                    String text;
                    try (FileInputStream in = new FileInputStream(f)) {
                        byte[] b = new byte[(int) Math.min(f.length(), 40000)];
                        int off = 0;
                        while (off < b.length) { int n = in.read(b, off, b.length - off); if (n < 0) break; off += n; }
                        text = new String(b, 0, off, StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        text = "Не удалось прочитать отчёт: " + e;
                    }
                    final String t = text;
                    j.main.post(new Runnable() { @Override public void run() { Dialogs.info(host, "Отчёт о предыдущем сбое", t); } });
                } }, "jv-crash-read");
                th.setDaemon(true);
                th.start();
            } } : null));
        return card;
    }
}
