package app.jarvis.ui.voiceui;

import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.adb.AdbApi;
import app.jarvis.core.adb.AdbException;
import app.jarvis.core.adb.AdbInput;
import app.jarvis.core.adb.AdbShellResult;
import app.jarvis.core.adb.AdbStatus;
import app.jarvis.core.util.CancelToken;
import app.jarvis.data.Settings;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;
import app.jarvis.ui.WrapButton;
import app.jarvis.ui.Widgets;
import app.jarvis.ui.settings.SettingsPage;

/**
 * «ADB по Wi-Fi»: статус из j.adb.status(), инструкция по подготовке телефона, поля адреса/портов/кода,
 * вставка готового текста (AdbInput.parse), сопряжение и подключение в фоне с человеческими ошибками,
 * реальная проверка «echo jarvis», отключение. Код сопряжения одноразовый: после успешного сопряжения
 * поле очищается, код нигде не сохраняется.
 */
public class AdbSettingsPage extends SettingsPage {
    private LinearLayout content, statusHost, runHost;
    private EditText addr, pairPort, pairCode, connPort;

    public AdbSettingsPage(Host host) { super(host); }

    @Override public String title() { return "ADB по Wi-Fi"; }

    @Override protected View build() {
        ScrollView sv = Ui.scroll(ctx);
        sv.setFillViewport(true);
        content = Ui.column(ctx);
        content.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(24));
        content.addView(Widgets.sectionTitle(ctx, "Состояние"));
        statusHost = Ui.column(ctx);
        content.addView(statusHost);
        renderStatus();
        content.addView(Widgets.sectionTitle(ctx, "Подключение"));
        content.addView(formCard());
        content.addView(Widgets.sectionTitle(ctx, "Как подготовить телефон"));
        content.addView(howtoCard());
        content.addView(Widgets.sectionTitle(ctx, "Что это такое"));
        content.addView(aboutCard());
        sv.addView(content);
        return sv;
    }

    // -------------------------------------------------------------------------------------------- status
    private void renderStatus() {
        statusHost.removeAllViews();
        AdbApi adb = j.adb;
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));
        if (adb == null) {
            card.addView(Widgets.empty(ctx, R.drawable.ic_plug, "ADB-модуль не подключён",
                "Беспроводная отладка появится после перезапуска приложения; подробности — в журнале."));
            statusHost.addView(card);
            return;
        }
        AdbStatus st = adb.status();
        LinearLayout head = Ui.row(ctx);
        head.addView(Widgets.pill(ctx, stateLabel(st.state), stateKind(st.state)), Ui.lpw(0f));
        head.addView(Ui.space(ctx, 8, 1));
        TextView where = Ui.text(ctx, st.host.isEmpty() ? "нет адреса" : st.host + (st.port > 0 ? ":" + st.port : ""), Ui.CAPTION, Ui.P.text2);
        where.setSingleLine(true);
        where.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        head.addView(where, Ui.lpw(1f));
        card.addView(head);
        StringBuilder sub = new StringBuilder();
        if (!st.device.isEmpty()) sub.append("устройство: ").append(st.device).append("\n");
        if (!st.keyFingerprint.isEmpty()) sub.append("ключ: ").append(st.keyFingerprint.substring(0, Math.min(19, st.keyFingerprint.length()))).append("…\n");
        if (st.state == AdbStatus.State.ERROR && !st.lastError.isEmpty()) sub.append(st.lastError);
        if (sub.length() > 0) card.addView(VoiceUiKit.note(ctx, sub.toString().trim()), Ui.lpm(-2, -2, 0, Ui.dp(8), 0, 0));

        LinearLayout btns = Ui.row(ctx);
        btns.addView(WrapButton.create(ctx, "Проверить соединение", R.drawable.ic_activity, Ui.BTN_GLASS, new Runnable() { @Override public void run() { runCheck(); } }), Ui.lpw(1f));
        btns.addView(Ui.space(ctx, 6, 1));
        btns.addView(WrapButton.create(ctx, "Отключить", R.drawable.ic_close, Ui.BTN_GHOST, new Runnable() { @Override public void run() {
            j.adb.disconnect();
            renderStatus();
        } }), Ui.lpw(1f));
        card.addView(btns, Ui.lpm(-1, -2, 0, Ui.dp(10), 0, 0));

        runHost = Ui.column(ctx);
        card.addView(runHost);
        statusHost.addView(card);
    }

    private static String stateLabel(AdbStatus.State s) {
        switch (s) {
            case NOT_PAIRED: return "не сопряжено";
            case PAIRED: return "сопряжено";
            case CONNECTING: return "подключаюсь…";
            case CONNECTED: return "подключено";
            default: return "ошибка";
        }
    }

    private static int stateKind(AdbStatus.State s) {
        switch (s) {
            case CONNECTED: return Widgets.OK;
            case PAIRED: return Widgets.INFO;
            case CONNECTING: return Widgets.MUTED;
            case ERROR: return Widgets.ERR;
            default: return Widgets.MUTED;
        }
    }

    // -------------------------------------------------------------------------------------------- form
    private View formCard() {
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));

        // paste box first: fills the fields below
        final EditText paste = Widgets.field(ctx, "Вставить текст с экрана сопряжения…", "", false, false);
        card.addView(VoiceUiKit.caption(ctx, "Быстрая вставка"), Ui.lpm(-2, -2, 0, 0, 0, Ui.dp(8)));
        LinearLayout pasteRow = Ui.row(ctx);
        pasteRow.addView(paste, Ui.lpw(1f));
        pasteRow.addView(Ui.iconButton(ctx, R.drawable.ic_paperclip, 40, Ui.P.text2, new Runnable() { @Override public void run() {
            String v = clipboard();
            if (v == null || v.isEmpty()) { host.overlays().toast("Буфер обмена пуст", 2); return; }
            paste.setText(v.trim());
            applyPaste(v.trim());
        } }));
        pasteRow.addView(Ui.iconButton(ctx, R.drawable.ic_check, 40, Ui.P.accent, new Runnable() { @Override public void run() {
            applyPaste(paste.getText().toString().trim());
        } }));
        card.addView(pasteRow);
        card.addView(VoiceUiKit.note(ctx, "Понимаю форматы «192.168.1.5:37123 123456» и «adb pair 10.0.0.2:41234 код 654321»."),
            Ui.lpm(-2, -2, 0, Ui.dp(4), 0, 0));

        Widgets.divide(card);
        card.addView(VoiceUiKit.caption(ctx, "Поля вручную"), Ui.lpm(-2, -2, Ui.dp(16), Ui.dp(12), Ui.dp(16), 0));
        addr = VoiceUiKit.saveField(ctx, "Адрес Wi-Fi (например 192.168.1.5)", j.settings.get(Settings.ADB_HOST), new VoiceUiKit.TextListener() { @Override public void on(String s) {
            j.settings.set(Settings.ADB_HOST, s.trim());
        } });
        card.addView(Widgets.fieldBox(ctx, addr), Ui.lpm(-1, -2, Ui.dp(16), Ui.dp(8), Ui.dp(16), 0));

        pairPort = numField("Порт сопряжения", Settings.ADB_PAIR_PORT, card);
        pairCode = Widgets.field(ctx, "Код из 6 цифр (одноразовый)", "", false, true);
        card.addView(Widgets.fieldBox(ctx, pairCode), Ui.lpm(-1, -2, Ui.dp(16), Ui.dp(8), Ui.dp(16), 0));
        connPort = numField("Порт подключения", Settings.ADB_PORT, card);

        LinearLayout btns = VoiceUiKit.buttonRow(ctx,
            WrapButton.create(ctx, "Сопрячь", R.drawable.ic_link, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() { runPair(); } }),
            WrapButton.create(ctx, "Подключить", R.drawable.ic_plug, Ui.BTN_GLASS, new Runnable() { @Override public void run() { runConnect(); } }));
        card.addView(btns, Ui.lpm(-1, -2, Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14)));
        card.addView(VoiceUiKit.note(ctx, "Код сопряжения одноразовый: после успешного сопряжения он не нужен и не сохраняется."),
            Ui.lpm(-2, -2, Ui.dp(16), 0, Ui.dp(16), Ui.dp(12)));
        return card;
    }

    private EditText numField(String hint, final String key, LinearLayout card) {
        EditText e = VoiceUiKit.saveField(ctx, hint, j.settings.get(key), new VoiceUiKit.TextListener() { @Override public void on(String s) {
            String v = s.trim();
            if (v.isEmpty()) j.settings.remove(key);
            else {
                try { int p = Integer.parseInt(v); if (p < 1 || p > 65535) throw new NumberFormatException(); }
                catch (NumberFormatException err) { host.overlays().toast("Порт — число от 1 до 65535", 2); return; }
                j.settings.set(key, v);
            }
        } });
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        card.addView(Widgets.fieldBox(ctx, e), Ui.lpm(-1, -2, Ui.dp(16), Ui.dp(8), Ui.dp(16), 0));
        return e;
    }

    private void applyPaste(String text) {
        AdbInput in = AdbInput.parse(text);
        if (in == null) { host.overlays().toast("Не нашёл адрес и порт в тексте", 2); return; }
        addr.setText(in.host);
        j.settings.set(Settings.ADB_HOST, in.host);
        // The pairing and connect ports look similar but live on different screens; guess by presence of a code.
        if (!in.code.isEmpty()) {
            pairPort.setText(String.valueOf(in.port));
            j.settings.set(Settings.ADB_PAIR_PORT, String.valueOf(in.port));
            pairCode.setText(in.code);
            host.overlays().toast("Адрес, порт сопряжения и код заполнены", 0);
        } else {
            connPort.setText(String.valueOf(in.port));
            j.settings.set(Settings.ADB_PORT, String.valueOf(in.port));
            host.overlays().toast("Адрес и порт подключения заполнены", 0);
        }
        host.hideKeyboard();
    }

    private String clipboard() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            android.content.ClipData d = cm == null ? null : cm.getPrimaryClip();
            return d == null || d.getItemCount() == 0 ? "" : String.valueOf(d.getItemAt(0).getText());
        } catch (Throwable t) { return ""; }
    }

    // -------------------------------------------------------------------------------------------- actions
    private boolean validFields() {
        if (j.adb == null) { host.overlays().toast("ADB-модуль не подключён", 2); return false; }
        if (addr.getText().toString().trim().isEmpty()) { host.overlays().toast("Введите адрес Wi-Fi", 2); return false; }
        return true;
    }

    private void runPair() {
        if (!validFields()) return;
        int port;
        try { port = Integer.parseInt(pairPort.getText().toString().trim()); if (port < 1 || port > 65535) throw new NumberFormatException(); }
        catch (NumberFormatException e) { host.overlays().toast("Введите порт сопряжения с экрана «Сопряжение с устройством»", 2); return; }
        String code = pairCode.getText().toString().trim();
        if (!code.matches("\\d{6}")) { host.overlays().toast("Код — ровно 6 цифр", 2); return; }
        final String hostAddr = addr.getText().toString().trim();
        final Dialogs.ProgressController progress = Dialogs.progress(host, "Сопряжение", "Подключаюсь к " + hostAddr + ":" + port + "…", new Runnable() { @Override public void run() { } });
        final CancelToken token = new CancelToken();
        new Thread(new Runnable() { @Override public void run() {
            try {
                j.adb.pair(hostAddr, port, code, token);
                j.settings.set(Settings.ADB_HOST, hostAddr);
                j.settings.set(Settings.ADB_PAIR_PORT, String.valueOf(port));
                j.main.post(new Runnable() { @Override public void run() {
                    progress.close();
                    pairCode.setText("");            // one-time: never keep it around
                    renderStatus();
                    host.overlays().toast("Сопряжение успешно — теперь «Подключить»", 1);
                } });
            } catch (final AdbException e) {
                j.main.post(new Runnable() { @Override public void run() {
                    progress.close();
                    if (e.kind != AdbException.Kind.CANCELLED) showError(e);
                    renderStatus();
                } });
            }
        } }, "jv-adb-pair").start();
    }

    private void runConnect() {
        if (!validFields()) return;
        int port;
        try { port = Integer.parseInt(connPort.getText().toString().trim()); if (port < 1 || port > 65535) throw new NumberFormatException(); }
        catch (NumberFormatException e) { host.overlays().toast("Введите порт подключения (он отличается от порта сопряжения)", 2); return; }
        final String hostAddr = addr.getText().toString().trim();
        final Dialogs.ProgressController progress = Dialogs.progress(host, "Подключение", hostAddr + ":" + port + "…", new Runnable() { @Override public void run() { } });
        final CancelToken token = new CancelToken();
        new Thread(new Runnable() { @Override public void run() {
            try {
                j.adb.connect(hostAddr, port, token);
                j.settings.set(Settings.ADB_HOST, hostAddr);
                j.settings.set(Settings.ADB_PORT, String.valueOf(port));
                j.main.post(new Runnable() { @Override public void run() {
                    progress.close();
                    renderStatus();
                    host.overlays().toast("Подключено", 1);
                } });
            } catch (final AdbException e) {
                j.main.post(new Runnable() { @Override public void run() {
                    progress.close();
                    if (e.kind != AdbException.Kind.CANCELLED) showError(e);
                    renderStatus();
                } });
            }
        } }, "jv-adb-connect").start();
    }

    /** Harmless end-to-end check: "echo jarvis" on the remote device. */
    private void runCheck() {
        if (j.adb == null) { host.overlays().toast("ADB-модуль не подключён", 2); return; }
        runHost.removeAllViews();
        LinearLayout row = Ui.row(ctx);
        row.addView(Widgets.spinner(ctx, 16));
        row.addView(Ui.text(ctx, "  Выполняю echo jarvis…", Ui.CAPTION, Ui.P.text2));
        runHost.addView(row);
        final long t0 = System.currentTimeMillis();
        new Thread(new Runnable() { @Override public void run() {
            try {
                final AdbShellResult r = j.adb.shell("echo jarvis", 10_000, new CancelToken());
                j.main.post(new Runnable() { @Override public void run() {
                    runHost.removeAllViews();
                    LinearLayout res = Ui.row(ctx);
                    boolean ok = r.exitCode == 0 && r.stdout.trim().equals("jarvis");
                    res.addView(Widgets.pill(ctx, ok ? "связь есть" : "неожиданный ответ", ok ? Widgets.OK : Widgets.WARN));
                    runHost.addView(res);
                    String out = "stdout: «" + r.stdout.trim() + "» · код " + r.exitCode + " · " + r.durationMs + " мс"
                        + (r.timedOut ? " · таймаут" : "") + (r.truncated ? " · обрезано" : "");
                    TextView t = VoiceUiKit.note(ctx, out);
                    t.setMaxLines(3);
                    runHost.addView(t, Ui.lpm(-2, -2, 0, Ui.dp(6), 0, 0));
                } });
            } catch (final AdbException e) {
                j.main.post(new Runnable() { @Override public void run() {
                    runHost.removeAllViews();
                    LinearLayout res = Ui.row(ctx);
                    res.addView(Widgets.pill(ctx, "нет связи", Widgets.ERR));
                    TextView t = Ui.text(ctx, "  " + e.userMessage, Ui.CAPTION, Ui.P.err);
                    t.setMaxLines(2);
                    res.addView(t, Ui.lpw(1f));
                    runHost.addView(res);
                } });
            }
        } }, "jv-adb-check").start();
    }

    private void showError(final AdbException e) {
        host.overlays().toast(e.userMessage, 2);
        Dialogs.info(host, "Не получилось",
            e.userMessage + "\n\nТехнически: " + (e.technical.isEmpty() ? String.valueOf(e.kind) : e.kind + "\n" + e.technical));
    }

    // -------------------------------------------------------------------------------------------- static cards
    private View howtoCard() {
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));
        card.addView(VoiceUiKit.caption(ctx,
            "1. На другом телефоне: Настройки → Для разработчиков → Беспроводная отладка → включить.\n"
            + "2. «Сопряжение с устройством с кодом» — там показаны адрес, ПОРТ СОПРЯЖЕНИЯ и 6-значный код.\n"
            + "3. Главный экран беспроводной отладки показывает ДРУГОЙ порт — порт подключения.\n"
            + "4. IP-адрес — тот же, что на экране беспроводной отладки (адрес Wi-Fi).\n"
            + "5. Сопряжение делается один раз; подключение — при каждом новом сеансе (порт подключения меняется после перезагрузки Wi-Fi)."));
        return card;
    }

    private View aboutCard() {
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));
        card.addView(VoiceUiKit.caption(ctx,
            "ADB — это отладочный канал Android поверх Wi-Fi, отдельный от встроенного терминала Jarvis: команды идут на ДРУГОЕ устройство (или в эмулятор) от его имени.\n\n"
            + "Честное ограничение: против реального телефона этот код пока не проверялся — тесты выполнялись с симулятором adbd на локальном адресе. Если сопряжение не выходит, посмотрите «Подробности» в ошибке."));
        return card;
    }

    @Override public void onShow() {
        renderStatus();
    }
}
