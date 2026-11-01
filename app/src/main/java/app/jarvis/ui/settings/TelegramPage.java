package app.jarvis.ui.settings;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.mcp.client.ServerConfig;
import app.jarvis.core.telegram.TgAccount;
import app.jarvis.core.telegram.TgReply;
import app.jarvis.core.telegram.TgRights;
import app.jarvis.rt.Jarvis;
import app.jarvis.rt.TgHelper;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/**
 * Settings → Telegram: the local Telegram MCP server, the accounts behind it (login by code, QR or imported session),
 * what the agent may do, and removal. All network work goes through {@link TgHelper} on a background thread.
 */
public class TelegramPage extends SettingsPage {
    /** Spacing and sizes of this page, in dp. */
    static final int PAD_PAGE = 16, PAD_TOP = 8, PAD_BOTTOM = 24, GAP_ITEM = 8, GAP_LINE = 14;
    static final String API_LINK = "https://my.telegram.org/apps";
    static final String HELPER_START = "bash ~/tg-mcp/bin/run.sh helper-start";
    private static final int REQ_PICK = 7;
    private static final int QR_POLL_MS = 2000;

    /** What the page shows. Filled by {@link #loadSnapshot}; previews build it by hand. */
    public static final class Snapshot {
        public boolean helperUp;
        public boolean tokenSaved;
        public String helperProblem = "";
        public boolean serverRunning;
        public boolean portOpen;
        public boolean mcpOk;
        public String mcpError = "";
        public boolean entry;
        public boolean confirmWrites;
        public TgRights rights = TgRights.ALL;
        public final List<TgAccount> accounts = new ArrayList<>();
    }

    private final TgHelper helper;
    private final Map<String, Boolean> checked = new ConcurrentHashMap<>();
    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout content;
    private boolean shown;
    private boolean frozen;
    private Snapshot snap = new Snapshot();
    private ImportForm importForm;
    private AddFlow addFlow;

    public TelegramPage(Host host) {
        super(host);
        this.helper = new TgHelper(j);
    }

    @Override public String title() { return "Telegram"; }

    /** One-line status for the settings list, from the McpServers entry only (no network). */
    public static String statusLine(Jarvis j) {
        ServerConfig cfg = j.mcp.hub().config(TgHelper.SERVER_ID);
        if (cfg == null) return "не подключено";
        return cfg.enabled ? "аккаунты подключены" : "аккаунтов нет";
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
        render(snap);
        refresh();
    }

    @Override public void onHide() { shown = false; }

    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK || resultCode != android.app.Activity.RESULT_OK || data == null) return;
        final Uri uri = data.getData();
        if (uri == null || importForm == null) return;
        background(new Runnable() { @Override public void run() { readPicked(uri); } }, null);
    }

    /** Re-reads the helper and the McpServers entry, then redraws. */
    public void refresh() {
        if (frozen) return;
        background(new Runnable() { @Override public void run() {
            final Snapshot s = loadSnapshot();
            j.main.post(new Runnable() { @Override public void run() {
                snap = s;
                if (shown) render(s);
            } });
        } }, null);
    }

    /** Used by the previews to draw a known state without the helper; a frozen page never reloads itself. */
    public void showSnapshot(Snapshot s) {
        frozen = true;
        snap = s;
        render(s);
    }

    /** Preview hook: opens the add sheet at a given step (0 form, 1 code, 2 password). */
    public void previewAddStep(int step) {
        openAddSheet();
        if (step == 1) addFlow.showCode();
        if (step == 2) addFlow.showPassword();
    }

    /** Blocking: call off the UI thread. */
    Snapshot loadSnapshot() {
        Snapshot s = new Snapshot();
        s.tokenSaved = helper.hasToken();
        ServerConfig cfg = helper.entry();
        s.entry = helper.hasEntry();
        s.confirmWrites = s.entry && cfg.confirmWrites;
        TgReply h = helper.health();
        s.helperUp = h.ok;
        if (!h.ok) {
            s.helperProblem = h.userMessage();
            return s;
        }
        if (!s.tokenSaved) return s;
        TgReply a = helper.accounts();
        if (a.ok) {
            s.accounts.addAll(TgAccount.parseList(a.json));
            s.rights = TgRights.fromExposed(a.json.optString("rights", "all"));
        } else {
            s.helperProblem = a.userMessage();
        }
        TgReply st = helper.status();
        if (st.ok) {
            JSONObject o = st.json;
            s.serverRunning = !o.isNull("server_loop_pid");
            s.portOpen = o.optBoolean("server_port_open", false);
            s.mcpOk = o.optBoolean("mcp_ok", false);
            s.mcpError = o.optString("mcp_error", "");
        }
        return s;
    }

    private void render(Snapshot s) {
        if (content == null) return;
        content.removeAllViews();
        addServerSection(s);
        addAccountsSection(s);
        addRightsSection(s);
        addSafetySection(s);
        addTokenSection(s);
        addRemovalSection(s);
        content.addView(PageUi.note(ctx, "Сессии Telegram хранятся на телефоне: в файле .env в Termux (доступ только у вашего пользователя Termux, права 0600). "
            + "Сервер слушает только 127.0.0.1 и не открыт в сеть. Автоматизация пользовательских аккаунтов может привести к ограничению аккаунта Telegram; держите объём действий человеческим."),
            Ui.lpm(-1, -2, 0, GAP_LINE, 0, 0));
    }

    private void addServerSection(final Snapshot s) {
        content.addView(Widgets.sectionTitle(ctx, "Сервер"));
        LinearLayout card = Widgets.card(ctx);
        String state = !s.helperUp ? "помощник не запущен" : s.serverRunning ? "работает" : "остановлен";
        int kind = !s.helperUp ? Widgets.ERR : s.serverRunning ? (s.mcpOk ? Widgets.OK : Widgets.WARN) : Widgets.MUTED;
        String sub = s.serverRunning && !s.mcpOk && !s.mcpError.isEmpty() ? s.mcpError
            : "адрес 127.0.0.1:8765 · " + (s.portOpen ? "порт открыт" : "порт закрыт");
        card.addView(Widgets.row(ctx, R.drawable.ic_plug, "Telegram MCP", sub, Widgets.pill(ctx, state, kind), null));
        LinearLayout actions = PageUi.buttonRow(ctx,
            Ui.button(ctx, s.serverRunning ? "Остановить" : "Запустить", 0, s.serverRunning ? Ui.BTN_GHOST : Ui.BTN_PRIMARY,
                new Runnable() { @Override public void run() { serverAction(s.serverRunning ? "stop" : "start"); } }));
        card.addView(actions, Ui.lpm(-1, -2, 14, 0, 14, 12));
        content.addView(card);
        if (!s.helperUp) {
            if (s.helperProblem != null && !s.helperProblem.isEmpty()) {
                String problem = s.helperProblem;
                int instruction = problem.indexOf("В Termux выполните:");
                if (instruction >= 0) problem = problem.substring(0, instruction).trim();
                if (!problem.isEmpty())
                    content.addView(PageUi.note(ctx, problem), Ui.lpm(-1, -2, 0, GAP_ITEM, 0, 0));
            }
            content.addView(termuxCommand("1. Установить Telegram-помощник",
                "bash ~/jarvis/tools/tg/setup.sh"), Ui.lpm(-1, -2, 0, GAP_ITEM, 0, 0));
            content.addView(termuxCommand("2. Запустить помощник", HELPER_START),
                Ui.lpm(-1, -2, 0, GAP_ITEM, 0, 0));
        }
    }

    private void addAccountsSection(final Snapshot s) {
        content.addView(Widgets.sectionTitle(ctx, "Аккаунты"));
        LinearLayout card = Widgets.card(ctx);
        if (s.accounts.isEmpty()) {
            card.addView(Widgets.row(ctx, R.drawable.ic_bot, "Аккаунтов пока нет", "Добавьте по коду, по QR или импортируйте сессию", null, null));
        }
        boolean first = true;
        for (final TgAccount acc : s.accounts) {
            if (!first) Widgets.divide(card);
            first = false;
            Boolean ok = checked.get(acc.label);
            TextView pill = Widgets.pill(ctx, ok == null ? "не проверен" : ok ? "в сети" : "ошибка", ok == null ? Widgets.MUTED : ok ? Widgets.OK : Widgets.ERR);
            card.addView(Widgets.row(ctx, R.drawable.ic_bot, acc.title(), acc.subtitle(), pill, new Runnable() { @Override public void run() {
                openAccountSheet(acc);
            } }));
        }
        card.addView(PageUi.buttonRow(ctx,
            Ui.button(ctx, "Добавить аккаунт", 0, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() { openAddSheet(); } }),
            Ui.button(ctx, "Импорт сессии", 0, Ui.BTN_GLASS, new Runnable() { @Override public void run() { openImportSheet(); } })),
            Ui.lpm(-1, -2, 14, 0, 14, 12));
        content.addView(card);
    }

    private void addRightsSection(final Snapshot s) {
        content.addView(Widgets.sectionTitle(ctx, "Права агента"));
        LinearLayout card = Widgets.card(ctx);
        boolean first = true;
        for (final TgRights chosen : TgRights.values()) {
            if (!first) Widgets.divide(card);
            first = false;
            boolean selected = chosen == s.rights;
            card.addView(Widgets.row(ctx, R.drawable.ic_wrench, chosen.title, rightsHint(chosen),
                Widgets.pill(ctx, selected ? "выбрано" : "выбрать", selected ? Widgets.OK : Widgets.MUTED), new Runnable() { @Override public void run() {
                    if (chosen == s.rights) return;
                    background(new Runnable() { @Override public void run() {
                        TgReply r = helper.setRights(chosen);
                        if (!r.ok) j.journal.w("system", "Telegram: права не сохранены — " + r.userMessage());
                    } }, new Runnable() { @Override public void run() { refresh(); } });
                } }));
        }
        content.addView(card);
        content.addView(PageUi.note(ctx, "Права общие для всех аккаунтов: так устроен сервер Telegram. «Всё» — отправка, изменение групп, удаление и блокировки."),
            Ui.lpm(-1, -2, 0, GAP_ITEM, 0, 0));
    }

    private static String rightsHint(TgRights r) {
        switch (r) {
            case READ: return "списки, поиск, история: ничего не отправляется";
            case SEND: return "чтение, отправка сообщений и файлов, отметки прочитанного";
            default: return "всё: отправка, группы, удаление, блокировки";
        }
    }

    private void addSafetySection(final Snapshot s) {
        content.addView(Widgets.sectionTitle(ctx, "Подтверждение"));
        LinearLayout card = Widgets.card(ctx);
        card.addView(Widgets.row(ctx, R.drawable.ic_wrench, "Спрашивать перед действиями",
            s.confirmWrites ? "Агент ждёт ваше «да» перед каждой отправкой и изменением" : "Выключено: агент действует сразу, в пределах 20 действий в минуту",
            Widgets.toggle(ctx, s.confirmWrites, new Widgets.Toggle.Listener() {
                @Override public void onChanged(final boolean on) {
                    background(new Runnable() { @Override public void run() {
                        try {
                            helper.setConfirmWrites(on);
                        } catch (java.io.IOException e) {
                            j.journal.e("system", "Telegram: не сохранил подтверждение", e);
                        }
                    } }, new Runnable() { @Override public void run() { refresh(); } });
                }
            }), null));
        content.addView(card);
    }

    private void addTokenSection(final Snapshot s) {
        content.addView(Widgets.sectionTitle(ctx, "Токен помощника"));
        LinearLayout card = Widgets.card(ctx);
        if (s.tokenSaved) {
            card.addView(Widgets.row(ctx, R.drawable.ic_plug, "Токен сохранён", "Хранится в зашифрованных секретах приложения", null, null));
            card.addView(Ui.button(ctx, "Сбросить токен", 0, Ui.BTN_GHOST, new Runnable() { @Override public void run() {
                helper.saveToken("");
                refresh();
            } }), Ui.lpm(-1, -2, 14, 0, 14, 12));
        } else {
            final EditText token = Widgets.field(ctx, "вставьте токен из ~/tg-mcp/helper.token", "", false, true);
            card.addView(Widgets.fieldBox(ctx, token), Ui.lpm(-1, -2, 14, 12, 14, 0));
            card.addView(Ui.button(ctx, "Сохранить токен", 0, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() {
                helper.saveToken(token.getText().toString());
                refresh();
            } }), Ui.lpm(-1, -2, 14, 8, 14, 12));
        }
        content.addView(card);
        content.addView(PageUi.note(ctx, "Токен нужен для связи с помощником входа. Не отправляйте его никому."),
            Ui.lpm(-1, -2, 0, GAP_ITEM, 0, 0));
        content.addView(termuxCommand("Показать токен в Termux", "cat ~/tg-mcp/helper.token"),
            Ui.lpm(-1, -2, 0, GAP_ITEM, 0, 0));
    }

    /** Readable Termux command with a dedicated clipboard action; no terminal side effects. */
    private View termuxCommand(String title, final String command) {
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(12));
        card.addView(Ui.text(ctx, title, Ui.CAPTION, Ui.P.text2));
        TextView code = Ui.text(ctx, command, Ui.MONO, Ui.P.text);
        code.setTextIsSelectable(true);
        code.setSingleLine(false);
        card.addView(code, Ui.lpm(-1, -2, 0, Ui.dp(8), 0, 0));
        card.addView(Ui.button(ctx, "Копировать команду", R.drawable.ic_copy, Ui.BTN_GHOST,
            new Runnable() { @Override public void run() {
                host.copyToClipboard("Команда Termux", command);
                host.overlays().toast("Команда скопирована", 1);
            } }), Ui.lpm(-1, -2, 0, Ui.dp(6), 0, 0));
        return card;
    }

    private void addRemovalSection(final Snapshot s) {
        if (s.accounts.isEmpty() && !s.entry) return;
        content.addView(Ui.button(ctx, "Удалить все данные Telegram", 0, Ui.BTN_DANGER, new Runnable() { @Override public void run() {
            Dialogs.confirm(host, "Удалить все данные Telegram?",
                "Сессии всех аккаунтов будут стёрты из .env, сервер остановится, пункт «Telegram» удалится из MCP-серверов. Сами аккаунты в Telegram не меняются.",
                "Удалить", true, new Runnable() { @Override public void run() { removeEverything(); } });
        } }), Ui.lpm(-1, -2, 0, GAP_LINE, 0, 0));
    }

    // ------------------------------------------------------------------------------------------ actions

    private void serverAction(final String action) {
        background(new Runnable() { @Override public void run() {
            TgReply r = helper.server(action);
            j.main.post(new Runnable() { @Override public void run() {
                host.overlays().toast(r.ok ? (action.equals("start") ? "Сервер запускается" : "Сервер остановлен") : r.userMessage(), r.ok ? OverlayHost.OK : OverlayHost.ERR);
            } });
        } }, new Runnable() { @Override public void run() { refresh(); } });
    }

    private void openAccountSheet(final TgAccount acc) {
        LinearLayout col = Ui.column(ctx);
        col.addView(PageUi.note(ctx, acc.subtitle() + "\nСессия хранится в .env на телефоне, в журнал не попадает."));
        final OverlayHost.Layer[] layer = new OverlayHost.Layer[1];
        col.addView(Ui.button(ctx, "Проверить подключение", 0, Ui.BTN_GLASS, new Runnable() { @Override public void run() {
            background(new Runnable() { @Override public void run() {
                TgReply r = helper.check(acc.label);
                checked.put(acc.label, r.ok);
                post(r.ok ? "Аккаунт «" + acc.label + "» в сети" : r.userMessage(), r.ok ? OverlayHost.OK : OverlayHost.ERR);
            } }, new Runnable() { @Override public void run() { refresh(); } });
        } }), Ui.lpm(-1, -2, 0, GAP_ITEM, 0, 0));
        col.addView(Ui.button(ctx, "Удалить аккаунт", 0, Ui.BTN_DANGER, new Runnable() { @Override public void run() {
            Dialogs.confirm(host, "Удалить «" + acc.label + "»?", "Сессия будет стёрта с телефона. В Telegram аккаунт остаётся.",
                "Удалить", true, new Runnable() { @Override public void run() {
                    host.overlays().dismiss(layer[0]);
                    removeAccount(acc.label);
                } });
        } }), Ui.lpm(-1, -2, 0, GAP_ITEM, 0, 0));
        layer[0] = Dialogs.sheet(host, acc.title(), col, true);
    }

    private void removeAccount(final String label) {
        background(new Runnable() { @Override public void run() {
            TgReply r = helper.remove(label);
            checked.remove(label);
            if (r.ok) {
                try {
                    helper.syncMcpEntry(!TgAccount.parseList(helper.accounts().json).isEmpty());
                } catch (java.io.IOException e) {
                    j.journal.e("system", "Telegram: не обновил MCP-серверы", e);
                }
            }
            post(r.ok ? "Аккаунт «" + label + "» удалён" : r.userMessage(), r.ok ? OverlayHost.OK : OverlayHost.ERR);
        } }, new Runnable() { @Override public void run() { refresh(); } });
    }

    private void removeEverything() {
        background(new Runnable() { @Override public void run() {
            Snapshot s = loadSnapshot();
            for (TgAccount acc : s.accounts) helper.remove(acc.label);
            helper.server("stop");
            try {
                helper.removeEntry();
            } catch (java.io.IOException e) {
                j.journal.e("system", "Telegram: не удалил MCP-сервер", e);
            }
            checked.clear();
            post("Все данные Telegram удалены", OverlayHost.OK);
        } }, new Runnable() { @Override public void run() { refresh(); } });
    }

    // ------------------------------------------------------------------------------------------ add account

    private void openAddSheet() {
        addFlow = new AddFlow();
        addFlow.showForm();
        addFlow.layer[0] = Dialogs.sheet(host, "Новый аккаунт Telegram", addFlow.col, true);
    }

    /** The "add account" sheet: form → code → password, or QR. One instance per open sheet. */
    private final class AddFlow {
        final LinearLayout col = Ui.column(ctx);
        final OverlayHost.Layer[] layer = new OverlayHost.Layer[1];
        final EditText apiId = Widgets.field(ctx, "api_id, например 1234567", "", false, false);
        final EditText apiHash = Widgets.field(ctx, "api_hash, 32 символа", "", false, true);
        final EditText phone = Widgets.field(ctx, "+7 900 123 45 67", "", false, false);
        final EditText label = Widgets.field(ctx, "имя аккаунта, латиницей", "main", false, false);
        final EditText field = Widgets.field(ctx, "", "", false, false);
        final EditText secret = Widgets.field(ctx, "", "", false, true);
        private boolean polling;
        private String qrUrl = "";

        void showForm() {
            col.removeAllViews();
            col.addView(PageUi.note(ctx, "Нужны api_id и api_hash приложения Telegram (бесплатно)."));
            TextView link = Ui.text(ctx, "где взять: my.telegram.org/apps", Ui.BODY, Ui.P.accent);
            link.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openApiPage(); } });
            col.addView(link, Ui.lpm(-1, -2, 0, GAP_ITEM, 0, GAP_ITEM));
            col.addView(Widgets.sectionTitle(ctx, "api_id"));
            col.addView(Widgets.fieldBox(ctx, apiId));
            col.addView(Widgets.sectionTitle(ctx, "api_hash"));
            col.addView(Widgets.fieldBox(ctx, apiHash));
            col.addView(Widgets.sectionTitle(ctx, "Телефон"));
            col.addView(Widgets.fieldBox(ctx, phone));
            col.addView(Widgets.sectionTitle(ctx, "Имя аккаунта"));
            col.addView(Widgets.fieldBox(ctx, label));
            col.addView(PageUi.buttonRow(ctx,
                Ui.button(ctx, "Закрыть", 0, Ui.BTN_GHOST, new Runnable() { @Override public void run() { close(); } }),
                Ui.button(ctx, "Код в Telegram", 0, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() { sendCode(); } })),
                Ui.lpm(-1, -2, 0, GAP_LINE, 0, 0));
            col.addView(Ui.button(ctx, "Войти по QR-коду", 0, Ui.BTN_GLASS, new Runnable() { @Override public void run() { startQr(); } }),
                Ui.lpm(-1, -2, 0, GAP_ITEM, 0, 0));
        }

        void showCode() {
            col.removeAllViews();
            col.addView(PageUi.note(ctx, "Код отправлен в Telegram: на другое ваше устройство или в чат «Telegram». Введите его."));
            col.addView(Widgets.sectionTitle(ctx, "Код"));
            col.addView(Widgets.fieldBox(ctx, field));
            col.addView(Ui.button(ctx, "Войти", 0, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() { submitCode(); } }),
                Ui.lpm(-1, -2, 0, GAP_LINE, 0, 0));
        }

        void showPassword() {
            col.removeAllViews();
            col.addView(PageUi.note(ctx, "Включена двухэтапная проверка Telegram. Введите пароль (он не сохраняется)."));
            col.addView(Widgets.sectionTitle(ctx, "Пароль"));
            col.addView(Widgets.fieldBox(ctx, secret));
            col.addView(Ui.button(ctx, "Войти", 0, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() { submitPassword(); } }),
                Ui.lpm(-1, -2, 0, GAP_LINE, 0, 0));
        }

        void showQr() {
            col.removeAllViews();
            col.addView(PageUi.note(ctx, "В Telegram на другом устройстве: Настройки → Устройства → Подключить устройство → отсканируйте код."));
            ImageView img = new ImageView(ctx);
            img.setTag("qr.image");
            img.setAdjustViewBounds(true);
            col.addView(img, Ui.lpm(-1, 240, 0, GAP_LINE, 0, GAP_ITEM));
            col.addView(PageUi.note(ctx, "Ссылка обновляется сама, если код устарел."));
            col.addView(Ui.button(ctx, "Отмена", 0, Ui.BTN_GHOST, new Runnable() { @Override public void run() { close(); } }),
                Ui.lpm(-1, -2, 0, GAP_LINE, 0, 0));
            loadQrImage(img);
            polling = true;
            j.main.postDelayed(new Runnable() { @Override public void run() { pollQr(); } }, QR_POLL_MS);
        }

        private void sendCode() {
            final int id = parseId();
            final String hash = apiHash.getText().toString().trim();
            final String ph = phone.getText().toString().trim();
            final String lb = label.getText().toString().trim();
            if (id <= 0) { toastErr("api_id — это число. Возьмите его на my.telegram.org/apps."); return; }
            background(new Runnable() { @Override public void run() {
                TgReply r = helper.startCode(id, hash, ph, lb);
                if (r.ok) {
                    j.main.post(new Runnable() { @Override public void run() { showCode(); } });
                } else {
                    post(r.userMessage(), OverlayHost.ERR);
                }
            } }, null);
        }

        private void submitCode() {
            final String code = field.getText().toString().trim();
            background(new Runnable() { @Override public void run() {
                TgReply r = helper.submitCode(code);
                finishOrNext(r);
            } }, null);
        }

        private void submitPassword() {
            final String pw = secret.getText().toString();
            background(new Runnable() { @Override public void run() {
                TgReply r = helper.submitPassword(pw);
                finishOrNext(r);
            } }, null);
        }

        private void startQr() {
            final int id = parseId();
            final String hash = apiHash.getText().toString().trim();
            final String lb = label.getText().toString().trim();
            if (id <= 0) { toastErr("api_id — это число. Возьмите его на my.telegram.org/apps."); return; }
            background(new Runnable() { @Override public void run() {
                TgReply r = helper.startQr(id, hash, lb);
                j.main.post(new Runnable() { @Override public void run() {
                    if (r.ok) showQr(); else host.overlays().toast(r.userMessage(), OverlayHost.ERR);
                } });
            } }, null);
        }

        private void pollQr() {
            if (!polling) return;
            background(new Runnable() { @Override public void run() {
                TgReply r = helper.qrStatus();
                if (r.ok && "ok".equals(r.state)) {
                    j.main.post(new Runnable() { @Override public void run() { accountAdded(r.label); } });
                } else if (r.ok && "qr_password_needed".equals(r.state)) {
                    j.main.post(new Runnable() { @Override public void run() { polling = false; showPassword(); } });
                } else if (!r.ok || "expired".equals(r.state) || "error".equals(r.state)) {
                    final String msg = r.userMessage();
                    j.main.post(new Runnable() { @Override public void run() { polling = false; host.overlays().toast(msg, OverlayHost.ERR); showForm(); } });
                } else if (!r.url.isEmpty() && !r.url.equals(qrUrl)) {
                    qrUrl = r.url;
                }
            } }, new Runnable() { @Override public void run() {
                if (polling) j.main.postDelayed(new Runnable() { @Override public void run() { pollQr(); } }, QR_POLL_MS);
            } });
        }

        private void finishOrNext(final TgReply r) {
            j.main.post(new Runnable() { @Override public void run() {
                if (r.ok && "password_needed".equals(r.state)) { showPassword(); return; }
                if (r.ok) { accountAdded(r.label); return; }
                host.overlays().toast(r.userMessage(), OverlayHost.ERR);
            } });
        }

        private void accountAdded(final String added) {
            polling = false;
            close();
            background(new Runnable() { @Override public void run() {
                try {
                    helper.syncMcpEntry(true);
                } catch (java.io.IOException e) {
                    j.journal.e("system", "Telegram: не обновил MCP-серверы", e);
                }
                post("Аккаунт «" + added + "» подключён. Сервер перезапущен.", OverlayHost.OK);
            } }, new Runnable() { @Override public void run() { refresh(); } });
        }

        private void loadQrImage(final ImageView img) {
            background(new Runnable() { @Override public void run() {
                final byte[] png = helper.qrImage();
                final Bitmap bmp = png == null ? null : BitmapFactory.decodeByteArray(png, 0, png.length);
                j.main.post(new Runnable() { @Override public void run() { if (bmp != null) img.setImageBitmap(bmp); } });
            } }, null);
        }

        private int parseId() {
            try {
                return Integer.parseInt(apiId.getText().toString().trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        private void close() {
            polling = false;
            if (layer[0] != null) host.overlays().dismiss(layer[0]);
            if (addFlow == this) addFlow = null;
        }
    }

    // ------------------------------------------------------------------------------------------ import

    private void openImportSheet() {
        importForm = new ImportForm();
        importForm.show();
    }

    /** Import sheet: a pasted session string, or a .session / .json file picked with the system picker. */
    private final class ImportForm {
        final LinearLayout col = Ui.column(ctx);
        final EditText apiId = Widgets.field(ctx, "api_id", "", false, false);
        final EditText apiHash = Widgets.field(ctx, "api_hash", "", false, true);
        final EditText label = Widgets.field(ctx, "имя аккаунта", "imported", false, false);
        final EditText session = Widgets.field(ctx, "строка сессии Telethon (начинается с 1)", "", true, true);
        final OverlayHost.Layer[] layer = new OverlayHost.Layer[1];

        void show() {
            col.addView(PageUi.note(ctx, "Вставьте строку сессии или выберите файл: .session (SQLite) или .json с api_id, api_hash и сессией. Данные уходят только на помощника в Termux."));
            col.addView(Widgets.sectionTitle(ctx, "api_id"));
            col.addView(Widgets.fieldBox(ctx, apiId));
            col.addView(Widgets.sectionTitle(ctx, "api_hash"));
            col.addView(Widgets.fieldBox(ctx, apiHash));
            col.addView(Widgets.sectionTitle(ctx, "Имя аккаунта"));
            col.addView(Widgets.fieldBox(ctx, label));
            col.addView(Widgets.sectionTitle(ctx, "Строка сессии"));
            col.addView(Widgets.fieldBox(ctx, session));
            col.addView(Ui.button(ctx, "Импортировать строку", 0, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() {
                importText(session.getText().toString().trim());
            } }), Ui.lpm(-1, -2, 0, GAP_LINE, 0, 0));
            col.addView(Ui.button(ctx, "Выбрать файл (.session или .json)", 0, Ui.BTN_GLASS, new Runnable() { @Override public void run() {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                host.startActivityForResult(screen(), i, REQ_PICK);
            } }), Ui.lpm(-1, -2, 0, GAP_ITEM, 0, 0));
            layer[0] = Dialogs.sheet(host, "Импорт сессии", col, true);
        }

        private boolean fieldsOk() {
            try {
                Integer.parseInt(apiId.getText().toString().trim());
            } catch (NumberFormatException e) {
                host.overlays().toast("api_id — это число", OverlayHost.ERR);
                return false;
            }
            return true;
        }

        void importText(final String text) {
            if (!fieldsOk()) return;
            final JSONObject extra = new JSONObject();
            try {
                extra.put("string_session", text);
            } catch (org.json.JSONException ignored) {
                return;
            }
            send(extra);
        }

        void importBytes(byte[] data) {
            if (!fieldsOk()) return;
            JSONObject extra = new JSONObject();
            String head = new String(data, 0, Math.min(data.length, 16), java.nio.charset.StandardCharsets.ISO_8859_1);
            try {
                if (head.startsWith("SQLite format 3")) {
                    extra.put("session_file_b64", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP));
                } else {
                    String txt = new String(data, java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (txt.startsWith("{")) {
                        extra.put("session_json", new JSONObject(txt));
                    } else {
                        extra.put("string_session", txt);
                    }
                }
            } catch (org.json.JSONException e) {
                host.overlays().toast("Файл не похож на сессию Telegram", OverlayHost.ERR);
                return;
            }
            send(extra);
        }

        private void send(final JSONObject extra) {
            final int id = Integer.parseInt(apiId.getText().toString().trim());
            final String hash = apiHash.getText().toString().trim();
            final String lb = label.getText().toString().trim();
            background(new Runnable() { @Override public void run() {
                final TgReply r = helper.importAccount(lb, id, hash, extra);
                j.main.post(new Runnable() { @Override public void run() {
                    if (!r.ok) {
                        host.overlays().toast(r.userMessage(), OverlayHost.ERR);
                        return;
                    }
                    host.overlays().toast("Аккаунт «" + r.label + "» импортирован", OverlayHost.OK);
                    if (layer[0] != null) host.overlays().dismiss(layer[0]);
                    importForm = null;
                } });
                if (r.ok) {
                    try {
                        helper.syncMcpEntry(true);
                    } catch (java.io.IOException e) {
                        j.journal.e("system", "Telegram: не обновил MCP-серверы", e);
                    }
                }
            } }, new Runnable() { @Override public void run() { refresh(); } });
        }
    }

    private void readPicked(Uri uri) {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 8 * 1024 * 1024) {
                    post("Файл слишком большой для сессии (до 8 МБ)", OverlayHost.ERR);
                    return;
                }
            }
            final byte[] data = out.toByteArray();
            j.main.post(new Runnable() { @Override public void run() {
                if (importForm != null) importForm.importBytes(data);
            } });
        } catch (java.io.IOException e) {
            post("Не удалось прочитать файл", OverlayHost.ERR);
        }
    }

    private void openApiPage() {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(API_LINK));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (RuntimeException e) {
            host.overlays().toast(API_LINK, OverlayHost.INFO);
        }
    }

    // ------------------------------------------------------------------------------------------ plumbing

    private void toastErr(String msg) { host.overlays().toast(msg, OverlayHost.ERR); }

    private void post(final String msg, final int kind) {
        j.main.post(new Runnable() { @Override public void run() { host.overlays().toast(msg, kind); } });
    }

    private void background(final Runnable work, final Runnable after) {
        new Thread(new Runnable() { @Override public void run() {
            try {
                work.run();
            } catch (RuntimeException e) {
                j.journal.e("system", "Telegram: действие не выполнено", e);
                post("Не удалось: " + e.getMessage(), OverlayHost.ERR);
            }
            if (after != null) j.main.post(after);
        } }, "jv-telegram").start();
    }
}
