package app.jarvis.ui.settings;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.mcp.client.ExternalMcpInfo;
import app.jarvis.core.mcp.client.McpException;
import app.jarvis.core.mcp.client.RemoteTool;
import app.jarvis.core.mcp.client.ServerConfig;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.J;
import app.jarvis.rt.Jarvis;
import app.jarvis.rt.TgHelper;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import java.util.List;
import org.json.JSONObject;

/**
 * External MCP servers: status list, add/edit sheet, per-server screen with exposure and per-tool allow switches.
 * Every hub call that touches the network or the disk runs on a worker thread; results return through the main handler.
 */
public class McpServersPage extends SettingsPage {
    /** Spacing and sizes of this page, in dp. */
    static final int PAD_PAGE = 16, PAD_TOP = 8, PAD_BOTTOM = 24, GAP_SECTION = 24, GAP_ITEM = 8, GAP_LINE = 14, DOT_DP = 8;
    static final String PRESET_URL = TgHelper.MCP_URL;

    private static final String[] TRANSPORTS = {"Авто", "HTTP", "SSE"};
    private static final String[] EXPOSURES = {"Авто", "Напрямую", "Через поиск"};

    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout content;
    private String openId;
    private boolean shown;
    private String lastSignature = "";
    private final Runnable tick = new Runnable() { @Override public void run() {
        if (!shown) return;
        String sig = signature();
        if (!sig.equals(lastSignature)) renderCurrent();
        j.main.postDelayed(this, 3000);
    } };

    public McpServersPage(Host host) { super(host); }

    @Override public String title() { return "Внешние MCP-серверы"; }

    /** One-line status for the settings list. */
    public static String statusLine(Jarvis j) {
        int total = 0, connected = 0;
        for (ExternalMcpInfo.Status s : j.mcp.hub().servers()) {
            total++;
            if (s.state == ExternalMcpInfo.State.CONNECTED) connected++;
        }
        if (total == 0) return "нет серверов";
        return "серверов: " + total + " · подключено: " + connected;
    }

    @Override protected View build() {
        root = new FrameLayout(ctx);
        scroll = Ui.scroll(ctx);
        scroll.setFillViewport(true);
        content = Ui.column(ctx);
        content.setPadding(Ui.dp(PAD_PAGE), Ui.dp(PAD_TOP), Ui.dp(PAD_PAGE), Ui.dp(PAD_BOTTOM));
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    @Override public void onShow() {
        shown = true;
        renderCurrent();
        j.main.postDelayed(tick, 3000);
    }

    private String signature() {
        StringBuilder sb = new StringBuilder();
        for (ExternalMcpInfo.Status s : j.mcp.hub().servers()) {
            sb.append(s.id).append('|').append(s.state).append('|').append(s.toolCount).append('|').append(s.latencyMs).append('|').append(s.error).append(';');
        }
        return sb.toString();
    }

    @Override public void onHide() { shown = false; }

    private void renderCurrent() {
        if (content == null) return;
        lastSignature = signature();
        if (openId == null) renderList(); else renderDetail(openId);
        refreshSectionHelps();
    }

    private static String clip(String s, int max) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max - 1) + "…" : t;
    }

    // ------------------------------------------------------------------------------------------ list

    private void renderList() {
        content.removeAllViews();
        content.addView(Widgets.sectionTitle(ctx, "Готовые подключения"));
        LinearLayout preset = Widgets.card(ctx);
        preset.addView(Widgets.row(ctx, R.drawable.ic_plug, "Telegram (через Termux)",
            "Адрес " + PRESET_URL + ". Сервер запускается в Termux; три шага покажем при добавлении.", null, new Runnable() { @Override public void run() {
                openEditor(null, true);
            } }));
        content.addView(preset);

        content.addView(Widgets.sectionTitle(ctx, "Серверы"));
        List<ExternalMcpInfo.Status> list = j.mcp.hub().servers();
        if (list.isEmpty()) {
            content.addView(Widgets.empty(ctx, R.drawable.ic_plug, "Серверов пока нет", "Добавьте внешний MCP-сервер: агент получит его инструменты."));
        } else {
            LinearLayout card = Widgets.card(ctx);
            for (int i = 0; i < list.size(); i++) {
                final ExternalMcpInfo.Status s = list.get(i);
                if (i > 0) Widgets.divide(card);
                LinearLayout row = Widgets.row(ctx, R.drawable.ic_plug, s.name, subtitle(s), dot(s.state), new Runnable() { @Override public void run() {
                    openId = s.id;
                    renderCurrent();
                } });
                row.setTag("mcpsrv." + s.id);
                card.addView(row);
            }
            content.addView(card);
        }
        content.addView(Ui.button(ctx, "Добавить сервер", 0, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() { openEditor(null, false); } }),
            Ui.lpm(-1, -2, 0, GAP_SECTION / 2, 0, 0));
    }

    static String stateText(ExternalMcpInfo.State st) {
        switch (st) {
            case CONNECTED: return "подключён";
            case CONNECTING: return "подключение…";
            case ERROR: return "ошибка";
            case DISABLED: return "выключен";
            default: return "ожидание";
        }
    }

    private String subtitle(ExternalMcpInfo.Status s) {
        StringBuilder sb = new StringBuilder(stateText(s.state));
        if (s.state == ExternalMcpInfo.State.ERROR && !s.error.isEmpty()) sb.append(": ").append(s.error);
        if (s.toolCount > 0) sb.append(" · инструментов ").append(s.toolCount);
        if (s.latencyMs >= 0) sb.append(" · ").append(s.latencyMs).append(" мс");
        return sb.toString();
    }

    private View dot(ExternalMcpInfo.State st) {
        int color;
        switch (st) {
            case CONNECTED: color = Ui.P.ok; break;
            case ERROR: color = Ui.P.err; break;
            case CONNECTING: color = Ui.P.warn; break;
            default: color = Ui.P.text3;
        }
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        View d = new View(ctx);
        d.setBackground(g);
        FrameLayout wrap = new FrameLayout(ctx);
        wrap.setContentDescription(stateText(st));
        wrap.addView(d, new FrameLayout.LayoutParams(Ui.dp(DOT_DP), Ui.dp(DOT_DP), Gravity.CENTER));
        return wrap;
    }

    // ------------------------------------------------------------------------------------------ detail

    private void renderDetail(final String id) {
        final ServerConfig cfg = j.mcp.hub().config(id);
        content.removeAllViews();
        if (cfg == null) { openId = null; renderList(); return; }
        ExternalMcpInfo.Status st = statusOf(id);

        LinearLayout back = Ui.row(ctx);
        back.addView(Ui.button(ctx, "← К списку", 0, Ui.BTN_GHOST, new Runnable() { @Override public void run() { openId = null; renderCurrent(); } }));
        content.addView(back);
        content.addView(Widgets.sectionTitle(ctx, cfg.name));
        TextView url = Ui.text(ctx, cfg.url, Ui.CAPTION, Ui.P.text2);
        content.addView(url, Ui.lpm(-1, -2, 4, 0, 4, GAP_ITEM));

        LinearLayout status = Widgets.card(ctx);
        status.addView(Widgets.row(ctx, R.drawable.ic_activity, "Состояние", st == null ? "" : subtitle(st), st == null ? null : dot(st.state), null));
        content.addView(status);

        content.addView(Widgets.sectionTitle(ctx, "Поведение"));
        LinearLayout settings = Widgets.card(ctx);
        settings.addView(Widgets.row(ctx, R.drawable.ic_plug, "Включён", "Инструменты доступны агенту", Widgets.toggle(ctx, cfg.enabled, new Widgets.Toggle.Listener() {
            @Override public void onChanged(boolean on) { update(cfg, on ? "включаю" : "выключаю", new Mutator() { @Override public void apply(ServerConfig c) { c.enabled = on; } }); }
        }), null));
        Widgets.divide(settings);
        settings.addView(Widgets.row(ctx, R.drawable.ic_alert, "Спрашивать перед записью", "Действия без пометки «только чтение» ждут подтверждения", Widgets.toggle(ctx, cfg.confirmWrites, new Widgets.Toggle.Listener() {
            @Override public void onChanged(boolean on) { update(cfg, "сохраняю", new Mutator() { @Override public void apply(ServerConfig c) { c.confirmWrites = on; } }); }
        }), null));
        Widgets.divide(settings);
        content.addView(settings);
        content.addView(Widgets.sectionTitle(ctx, "Показ инструментов агенту"));
        content.addView(PageUi.note(ctx, "Напрямую — каждый инструмент в списке; через поиск — только два общих инструмента, остальные ищутся по запросу."),
            Ui.lpm(-1, -2, 4, 0, 4, GAP_ITEM));
        content.addView(Widgets.segmented(ctx, EXPOSURES, exposureIndex(cfg), new Widgets.IntListener() {
            @Override public void on(int index) {
                final ServerConfig.Exposure e = index == 1 ? ServerConfig.Exposure.DIRECT : index == 2 ? ServerConfig.Exposure.LAZY : ServerConfig.Exposure.AUTO;
                update(cfg, "сохраняю", new Mutator() { @Override public void apply(ServerConfig c) { c.exposure = e; } });
            }
        }), Ui.lpm(-1, -2, 0, 0, 0, GAP_ITEM));

        content.addView(Widgets.sectionTitle(ctx, "Инструменты (" + cfg.tools.size() + ")"));
        if (cfg.tools.isEmpty()) {
            content.addView(PageUi.note(ctx, "Список пуст: нажмите «Проверить подключение», чтобы получить инструменты сервера."),
                Ui.lpm(-1, -2, 4, 0, 4, GAP_ITEM));
        } else {
            LinearLayout tools = Widgets.card(ctx);
            for (int i = 0; i < cfg.tools.size(); i++) {
                final RemoteTool t = cfg.tools.get(i);
                if (i > 0) Widgets.divide(tools);
                String sub = (t.readOnly ? "только чтение" : "может менять данные") + (t.description.isEmpty() ? "" : " · " + clip(t.description, 120));
                tools.addView(Widgets.row(ctx, 0, t.name, sub, Widgets.toggle(ctx, cfg.allowed(t.name), new Widgets.Toggle.Listener() {
                    @Override public void onChanged(boolean on) {
                        update(cfg, "сохраняю", new Mutator() { @Override public void apply(ServerConfig c) { setAllowed(c, t.name, on); } });
                    }
                }), null));
            }
            content.addView(tools);
        }

        content.addView(Widgets.sectionTitle(ctx, "Действия"));
        LinearLayout actions = Widgets.card(ctx);
        actions.addView(Widgets.row(ctx, R.drawable.ic_activity, "Проверить подключение", "Подключиться, получить список инструментов", null, new Runnable() {
            @Override public void run() { testConnection(cfg.id); }
        }));
        Widgets.divide(actions);
        actions.addView(Widgets.row(ctx, R.drawable.ic_wrench, "Изменить", "Адрес, транспорт, заголовок авторизации", null, new Runnable() {
            @Override public void run() { openEditor(cfg, false); }
        }));
        Widgets.divide(actions);
        actions.addView(Widgets.row(ctx, R.drawable.ic_alert, "Удалить сервер", "Инструменты пропадут; токен удаляется из секретов", null, new Runnable() {
            @Override public void run() {
                Dialogs.confirm(host, "Удалить сервер?", "«" + cfg.name + "» и его токен будут удалены.", "Удалить", true, new Runnable() {
                    @Override public void run() {
                        openId = null;
                        background("удаляю", new Runnable() { @Override public void run() {
                            try { j.mcp.hub().remove(cfg.id); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
                        } }, null);
                    }
                });
            }
        }));
        content.addView(actions);
    }

    private ExternalMcpInfo.Status statusOf(String id) {
        for (ExternalMcpInfo.Status s : j.mcp.hub().servers()) if (s.id.equals(id)) return s;
        return null;
    }

    private static int exposureIndex(ServerConfig c) {
        switch (c.exposure) {
            case DIRECT: return 1;
            case LAZY: return 2;
            default: return 0;
        }
    }

    /** Edits one copy of the config and saves it on a worker thread. */
    private interface Mutator { void apply(ServerConfig c); }

    private void update(ServerConfig cfg, String what, final Mutator m) {
        final ServerConfig copy = cfg.copy();
        m.apply(copy);
        background(what, new Runnable() { @Override public void run() {
            try { j.mcp.hub().save(copy, null); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        } }, null);
    }

    static void setAllowed(ServerConfig c, String tool, boolean on) {
        if (on) {
            c.deny.remove(tool);
            if (!c.allow.isEmpty() && !c.allow.contains(tool)) c.allow.add(tool);
        } else {
            if (!c.deny.contains(tool)) c.deny.add(tool);
            c.allow.remove(tool);
        }
    }

    private void testConnection(final String id) {
        host.overlays().toast("Проверяю подключение…", 1);
        new Thread(new Runnable() { @Override public void run() {
            final String message;
            final String title;
            try {
                List<String> names = j.mcp.hub().testServer(id, new CancelToken());
                title = "Подключение работает";
                message = "Инструментов: " + names.size() + (names.isEmpty() ? "" : "\n" + join(names));
            } catch (McpException e) {
                j.journal.w("system", "Проверка MCP-сервера «" + id + "»: " + e.technical);
                showInfo("Не удалось подключиться", e.getMessage());
                return;
            }
            showInfo(title, message);
        } }, "jv-mcp-test").start();
    }

    private void showInfo(final String title, final String message) {
        j.main.post(new Runnable() { @Override public void run() {
            Dialogs.info(host, title, message);
            if (shown) renderCurrent();
        } });
    }

    private static String join(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(n);
            if (sb.length() > 600) { sb.append(" …"); break; }
        }
        return sb.toString();
    }

    /** Runs a hub operation off the main thread, then refreshes the visible list. */
    private void background(final String what, final Runnable work, final Runnable after) {
        new Thread(new Runnable() { @Override public void run() {
            try {
                work.run();
            } catch (RuntimeException e) {
                j.journal.e("system", "MCP-серверы: не удалось " + what, e);
                j.main.post(new Runnable() { @Override public void run() { host.overlays().toast("Не удалось: " + e.getMessage(), 2); } });
            }
            j.main.post(new Runnable() { @Override public void run() {
                if (after != null) after.run();
                if (shown) renderCurrent();
            } });
        } }, "jv-mcp-settings").start();
    }

    // ------------------------------------------------------------------------------------------ editor

    /** Add or edit sheet. A preset shows the three manual steps and prefills the Telegram address. */
    public void openEditor(final ServerConfig existing, boolean preset) {
        LinearLayout col = Ui.column(ctx);
        final EditText name = Widgets.field(ctx, "Например, Telegram", existing != null ? existing.name : preset ? "Telegram" : "", false, false);
        final EditText url = Widgets.field(ctx, PRESET_URL, existing != null ? existing.url : preset ? PRESET_URL : "", false, false);
        final EditText hName = Widgets.field(ctx, "Заголовок, например Authorization", "", false, false);
        final EditText hValue = Widgets.field(ctx, existing != null ? "оставьте пустым, чтобы не менять" : "Например, Bearer <токен>", "", false, true);
        final ServerConfig.Transport[] tr = {existing != null ? existing.transport : ServerConfig.Transport.AUTO};
        final TextView err = PageUi.note(ctx, "");
        err.setTextColor(Ui.P.err);
        err.setVisibility(View.GONE);

        if (preset) {
            col.addView(PageUi.note(ctx, "1. Для Telegram не нужно ничего вручную: откройте «Telegram-аккаунты» в настройках. Там установка в Termux, вход и сервер.\n"
                + "2. Свой сервер на том же адресе: MCP_TRANSPORT=http MCP_PORT=8765 и ваш запуск.\n"
                + "3. Нажмите «Проверить подключение». Автоматическая настройка появится позже (tg1)."), Ui.lpm(-1, -2, 0, 0, 0, GAP_ITEM));
        }
        col.addView(Widgets.sectionTitle(ctx, "Название"));
        col.addView(Widgets.fieldBox(ctx, name));
        col.addView(Widgets.sectionTitle(ctx, "Адрес"));
        col.addView(Widgets.fieldBox(ctx, url));
        col.addView(Widgets.sectionTitle(ctx, "Транспорт"));
        col.addView(Widgets.segmented(ctx, TRANSPORTS, tr[0].ordinal(), new Widgets.IntListener() {
            @Override public void on(int index) { tr[0] = ServerConfig.Transport.values()[index]; }
        }));
        col.addView(Widgets.sectionTitle(ctx, "Заголовок авторизации"));
        col.addView(Widgets.fieldBox(ctx, hName));
        col.addView(Widgets.fieldBox(ctx, hValue));
        col.addView(PageUi.note(ctx, "Токен хранится в зашифрованных секретах приложения, в файл серверов не попадает."),
            Ui.lpm(-1, -2, 0, GAP_ITEM / 2, 0, GAP_ITEM));
        col.addView(err, Ui.lpm(-1, -2, 0, 0, 0, GAP_ITEM));

        final OverlayHost.Layer[] layer = new OverlayHost.Layer[1];
        LinearLayout buttons = PageUi.buttonRow(ctx,
            Ui.button(ctx, "Отмена", 0, Ui.BTN_GHOST, new Runnable() { @Override public void run() { host.overlays().dismiss(layer[0]); } }),
            Ui.button(ctx, "Сохранить", 0, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() { submit(existing, name, url, hName, hValue, tr[0], err, layer[0], false); } }));
        col.addView(buttons, Ui.lpm(-1, -2, 0, GAP_LINE, 0, 0));
        col.addView(Ui.button(ctx, "Проверить подключение", 0, Ui.BTN_GLASS, new Runnable() {
            @Override public void run() { submit(existing, name, url, hName, hValue, tr[0], err, layer[0], true); }
        }), Ui.lpm(-1, -2, 0, GAP_ITEM, 0, 0));
        layer[0] = Dialogs.sheet(host, existing == null ? "Новый сервер" : "Сервер «" + existing.name + "»", col, true);
    }

    private void submit(final ServerConfig existing, EditText name, EditText url, EditText hName, EditText hValue,
                         final ServerConfig.Transport transport, TextView err, final OverlayHost.Layer layer, final boolean test) {
        String n = name.getText().toString().trim();
        String u = url.getText().toString().trim();
        String hn = hName.getText().toString().trim();
        String hv = hValue.getText().toString().trim();
        String problem = ServerConfig.validateUrl(u);
        if (problem.isEmpty() && n.isEmpty()) problem = "Укажите название";
        if (problem.isEmpty() && !hn.isEmpty() && hv.isEmpty() && existing == null) problem = "Укажите значение заголовка";
        if (problem.isEmpty() && (hn.isEmpty() != hv.isEmpty())) problem = "Заполните и имя, и значение заголовка";
        if (problem.isEmpty() && !asciiOnly(hn + hv)) problem = "Заголовок и значение — только латиница, цифры и знаки ASCII";
        if (!problem.isEmpty()) {
            err.setText(problem);
            err.setVisibility(View.VISIBLE);
            return;
        }
        final ServerConfig cfg = existing == null ? new ServerConfig() : existing.copy();
        cfg.name = n;
        cfg.url = u;
        cfg.transport = transport;
        final String headersJson;
        if (existing != null && hn.isEmpty()) headersJson = null;
        else {
            JSONObject h = new JSONObject();
            if (!hn.isEmpty()) J.put(h, hn, hv);
            headersJson = h.toString();
        }
        final String id = existing == null ? j.mcp.hub().newId(n) : existing.id;
        cfg.id = id;
        if (layer != null) host.overlays().dismiss(layer);
        background("сохраняю сервер", new Runnable() { @Override public void run() {
            try { j.mcp.hub().save(cfg, headersJson); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        } }, new Runnable() { @Override public void run() {
            openId = id;
            if (test) testConnection(id);
        } });
    }

    private static boolean asciiOnly(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0x7e) return false;
        return true;
    }

    /** Used by the debug previews to place the page in a known state. */
    public void showList() { openId = null; renderCurrent(); }

    /** Used by the debug previews. */
    public void showDetail(String id) { openId = id; renderCurrent(); }
}
