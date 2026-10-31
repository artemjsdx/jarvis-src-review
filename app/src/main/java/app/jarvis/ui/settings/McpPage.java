package app.jarvis.ui.settings;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.data.Settings;
import app.jarvis.mcp.McpBridge;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Bridge for the agent (MCP): on/off, status, copy/reset the token, safety switches. */
public class McpPage extends SettingsPage {
    private TextView statusSub;
    private final Runnable tick = new Runnable() { @Override public void run() { refresh(); if (shown) j.main.postDelayed(this, 2000); } };
    private boolean shown;

    public McpPage(Host host) { super(host); }

    @Override public String title() { return "Мост для агента"; }

    /** One-line status shared with the settings list. */
    public static String statusLine(app.jarvis.rt.Jarvis j) {
        McpBridge b = McpBridge.get();
        if (!McpBridge.enabled(j)) return "выключен";
        return b != null && b.running() ? "включён · порт " + b.port() : "включён, не запущен";
    }

    @Override protected View build() {
        ScrollView sv = Ui.scroll(ctx);
        sv.setFillViewport(true);
        LinearLayout content = Ui.column(ctx);
        content.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(24));

        content.addView(Widgets.sectionTitle(ctx, "Мост"));
        LinearLayout card = Widgets.card(ctx);
        LinearLayout sw = Widgets.row(ctx, R.drawable.ic_plug, "Мост для агента", "Локальный MCP-сервер: агент в Termux видит журнал, чат и экран приложения",
            Widgets.toggle(ctx, McpBridge.enabled(j), new Widgets.Toggle.Listener() { @Override public void onChanged(boolean on) { setEnabled(on); } }), null);
        sw.setTag("mcp.enabled");
        card.addView(sw);
        Widgets.divide(card);
        LinearLayout st = Widgets.row(ctx, R.drawable.ic_activity, "Состояние", "…", null, null);
        statusSub = subtitleOf(st);
        card.addView(st);
        content.addView(card);

        content.addView(Widgets.sectionTitle(ctx, "Токен"));
        LinearLayout tk = Widgets.card(ctx);
        tk.addView(Widgets.row(ctx, R.drawable.ic_copy, "Скопировать токен", "Положите его в ~/.jarvis-keys/mcp-" + ctx.getPackageName() + ".json как {\"token\":\"…\"}", null, new Runnable() { @Override public void run() {
            McpBridge b = McpBridge.get();
            if (b == null) { host.overlays().toast("Мост не запущен", 2); return; }
            host.copyToClipboard("Токен моста", b.token());
            host.overlays().toast("Токен скопирован", 1);
        } }));
        Widgets.divide(tk);
        tk.addView(Widgets.row(ctx, R.drawable.ic_refresh, "Сбросить токен", "Старый токен перестанет работать, агенту нужен новый", null, new Runnable() { @Override public void run() {
            Dialogs.confirm(host, "Сбросить токен?", "Агент потеряет доступ, пока вы не передадите ему новый токен.", "Сбросить", true, new Runnable() { @Override public void run() {
                McpBridge b = McpBridge.get();
                if (b == null) { host.overlays().toast("Мост не запущен", 2); return; }
                try { b.resetToken(); host.overlays().toast("Токен сброшен", 1); } catch (Throwable t) { host.overlays().toast("Не удалось: " + t.getMessage(), 2); }
            } });
        } }));
        content.addView(tk);

        content.addView(Widgets.sectionTitle(ctx, "Безопасность"));
        LinearLayout sf = Widgets.card(ctx);
        sf.addView(toggleRow(R.drawable.ic_volume, "Звук и микрофон", "Агент сможет озвучивать ответы и включать музыку", Settings.MCP_ALLOW_AUDIO, false));
        Widgets.divide(sf);
        sf.addView(toggleRow(R.drawable.ic_alert, "Опасные действия", "Чтение ключей, запись в базу, удаление обычных чатов, сброс настроек", Settings.MCP_ALLOW_DESTRUCTIVE, false));
        Widgets.divide(sf);
        sf.addView(toggleRow(R.drawable.ic_eye, "Показывать тестовые чаты", "Сессии mcp-* видны в списке чатов", Settings.MCP_SHOW_TEST_SESSIONS, false));
        Widgets.divide(sf);
        sf.addView(toggleRow(R.drawable.ic_share, "Копия токена в «Загрузки»", "Не нужно для обычной работы; папка читается другими приложениями", Settings.MCP_MIRROR, false));
        content.addView(sf);

        sv.addView(content);
        return sv;
    }

    private View toggleRow(int icon, String title, String sub, final String key, boolean def) {
        LinearLayout r = Widgets.row(ctx, icon, title, sub, Widgets.toggle(ctx, j.settings.getBool(key, def), new Widgets.Toggle.Listener() { @Override public void onChanged(boolean on) {
            j.settings.setBool(key, on);
        } }), null);
        r.setTag(key);
        return r;
    }

    private void setEnabled(boolean on) {
        j.settings.setBool(Settings.MCP_ENABLED, on);
        if (on) {
            McpBridge.ensure(ctx, j);
            host.overlays().toast("Мост включён", 1);
        } else {
            McpBridge b = McpBridge.get();
            if (b != null) b.stop();
            host.overlays().toast("Мост выключен", 0);
        }
        refresh();
    }

    private static TextView subtitleOf(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c instanceof LinearLayout) { View s = c.findViewWithTag("subtitle"); if (s instanceof TextView) return (TextView) s; }
        }
        return null;
    }

    private void refresh() {
        if (statusSub == null) return;
        McpBridge b = McpBridge.get();
        if (!McpBridge.enabled(j)) { statusSub.setText("выключен"); return; }
        if (b == null || !b.running()) { statusSub.setText("не запущен"); return; }
        StringBuilder sb = new StringBuilder("порт " + b.port() + " · инструментов " + b.registry.tools().size() + " · вызовов " + b.callCount());
        if (b.lastCallMs() > 0) sb.append("\nпоследний: ").append(b.lastTool()).append(" в ").append(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(b.lastCallMs())));
        else sb.append("\nагент ещё не подключался");
        statusSub.setText(sb.toString());
    }

    @Override public void onShow() { shown = true; j.main.post(tick); }

    @Override public void onHide() { shown = false; }
}
