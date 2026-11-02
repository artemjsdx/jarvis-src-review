package app.jarvis.ui.settings;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.web.ExtraSearch;
import app.jarvis.core.web.SearchBackend;
import app.jarvis.core.web.SearchHit;
import app.jarvis.core.web.SearchRequest;
import app.jarvis.core.web.WebConfig;
import app.jarvis.data.Secrets;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.HelpPopup;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import app.jarvis.ui.WrapButton;
import java.util.List;

/**
 * Additive agent web search: configured providers never overwrite the default web search.
 * Keys live in Secrets. A real nonempty search proves access and binds it to a key fingerprint.
 */
public final class SearchProvidersPage extends SettingsPage {
    private LinearLayout column;
    private volatile String testing = "";

    public SearchProvidersPage(Host host) { super(host); }
    @Override public String title() { return "Дополнительный веб-поиск"; }

    @Override protected View build() {
        ScrollView scroll = Ui.scroll(ctx);
        scroll.setFillViewport(true);
        column = Ui.column(ctx);
        column.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(26));
        scroll.addView(column);
        render();
        return scroll;
    }

    @Override public void onShow() { if (column != null) render(); }

    private void render() {
        column.removeAllViews();
        column.addView(Widgets.sectionTitle(ctx, "Два разных способа поиска"));
        LinearLayout explanation = Widgets.card(ctx);
        explanation.setPadding(Ui.dp(16), Ui.dp(15), Ui.dp(16), Ui.dp(16));
        explanation.addView(Ui.text(ctx, "Обычный поиск всегда остаётся", Ui.BODY));
        explanation.addView(Ui.text(ctx,
            "Текущая цепочка web_search не меняется. Ниже можно подключить дополнительные сервисы, и Джарвис сам выберет подходящий. " +
            "Ни один не получает ваши ключи из других аккаунтов автоматически.",
            Ui.CAPTION, Ui.P.text2), Ui.lpm(-1, -2, 0, Ui.dp(8), 0, 0));
        explanation.addView(HelpPopup.button(ctx, host, "Как Джарвис выбирает поиск",
            "1. Обычный web_search по-прежнему работает без изменений.",
            "2. Вы сохраняете ключ отдельного провайдера и нажимаете «Проверить». Jarvis выполняет настоящий поисковый запрос.",
            "3. Только после удачной проверки появляется отдельный web_search_extra. Агент выбирает его по задаче, а не автоматически расходует сразу все квоты.",
            "4. Нужны детали — агент вызывает web_provider_guide. Если доппоиск недоступен, остаётся стандартный web_search."));
        column.addView(explanation);
        column.addView(Widgets.sectionTitle(ctx, "Подключаемые поисковики"));
        for (ExtraSearch.Provider p : ExtraSearch.CATALOG)
            column.addView(providerCard(p), Ui.lpm(-1, -2, 0, Ui.dp(14), 0, 0));
        LinearLayout mcp = Widgets.card(ctx);
        mcp.setPadding(Ui.dp(16), Ui.dp(15), Ui.dp(16), Ui.dp(15));
        mcp.addView(Ui.text(ctx, "Parallel Search MCP — отдельный бесплатный путь", Ui.BODY));
        mcp.addView(Ui.text(ctx,
            "Для бесплатного MCP без API-ключа можно добавить удалённый сервер " +
            "https://search.parallel.ai/mcp через раздел внешних MCP-серверов. " +
            "Этот путь не равен платному Search API, который настраивается выше.",
            Ui.CAPTION, Ui.P.text2), Ui.lpm(-1, -2, 0, Ui.dp(8), 0, Ui.dp(12)));
        mcp.addView(WrapButton.create(ctx, "Открыть MCP-серверы", R.drawable.ic_plug,
            Ui.BTN_GLASS, () -> host.navigate("settings/mcpsrv")));
        column.addView(mcp, Ui.lpm(-1, -2, 0, Ui.dp(18), 0, 0));
    }

    private View providerCard(final ExtraSearch.Provider p) {
        final boolean valid = ExtraSearch.validated(p.id, j.settings::get, j.secrets::get);
        final boolean enabled = j.settings.getBool(ExtraSearch.keyEnabled(p.id), false);
        final boolean hasKey = j.secrets.has(p.secret);
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(16), Ui.dp(16), Ui.dp(17));
        LinearLayout header = Ui.row(ctx);
        TextView title = Ui.text(ctx, p.title, Ui.BODY);
        title.setTypeface(Ui.medium);
        header.addView(title, Ui.lpw(1f));
        header.addView(HelpPopup.button(ctx, host, p.title,
            p.purpose, "Бесплатный уровень: " + p.freeText, "Ограничения: " + p.caveat,
            "Подключение: ключ хранится в защищённом хранилище телефона. После проверки провайдером можно управлять отдельно от стандартного поиска.",
            "Документация для агента: " + p.docs));
        card.addView(header);

        LinearLayout status = Ui.row(ctx);
        String flag = p.id.equals(testing) ? "проверка…" : valid ? "работает · включён" :
            enabled && hasKey ? "нужна повторная проверка" : hasKey ? "ключ сохранён · выключен" : "не настроен";
        status.addView(Widgets.pill(ctx, flag, valid ? Widgets.OK : Widgets.MUTED));
        card.addView(status, Ui.lpm(-1, -2, 0, Ui.dp(10), 0, 0));
        card.addView(Ui.text(ctx, p.purpose, Ui.CAPTION, Ui.P.text2),
            Ui.lpm(-1, -2, 0, Ui.dp(10), 0, 0));
        card.addView(Ui.text(ctx, p.freeText, Ui.CAPTION, Ui.P.text3),
            Ui.lpm(-1, -2, 0, Ui.dp(5), 0, 0));

        LinearLayout toggleRow = Ui.row(ctx);
        toggleRow.addView(Ui.text(ctx, enabled ? "Использовать в агенте" : "Не использовать в агенте",
            Ui.CAPTION, Ui.P.text2), Ui.lpw(1f));
        toggleRow.addView(Widgets.toggle(ctx, enabled, on -> {
            if (on && !isVerifiedKey(p)) {
                host.overlays().toast("Сначала проверьте подключение реальным поиском", 2);
                render();
                return;
            }
            j.settings.setBool(ExtraSearch.keyEnabled(p.id), on);
            render();
        }));
        card.addView(toggleRow, Ui.lpm(-1, -2, 0, Ui.dp(12), 0, Ui.dp(4)));

        LinearLayout row = Ui.row(ctx);
        row.addView(WrapButton.create(ctx, hasKey ? "Ключ: " + Secrets.mask(j.secrets.get(p.secret)) : "Добавить API-ключ",
            0, Ui.BTN_GLASS, () -> editKey(p)), Ui.lpw(1f));
        card.addView(row, Ui.lpm(-1, -2, 0, Ui.dp(10), 0, Ui.dp(8)));

        LinearLayout actions = Ui.row(ctx);
        actions.addView(WrapButton.create(ctx, p.id.equals(testing) ? "Проверяю…" : "Проверить",
            R.drawable.ic_activity, Ui.BTN_GLASS, () -> test(p)), Ui.lpw(1f));
        actions.addView(Ui.space(ctx, 8, 1));
        actions.addView(WrapButton.create(ctx, "Для чего подходит", R.drawable.ic_help,
            Ui.BTN_GHOST, () -> HelpPopup.show(host, p.title,
                p.purpose, "Бесплатный уровень: " + p.freeText, p.caveat,
                "Пример для агента: web_search_extra(provider=\"" + p.id +
                    "\", query=\"последние новости Android\", limit=5).",
                "Если результатов нет, агент использует обычный web_search.")), Ui.lpw(1f));
        card.addView(actions);

        if (j.settings.has(ExtraSearch.keyCheckedAt(p.id))) {
            String when = j.settings.get(ExtraSearch.keyCheckedAt(p.id));
            String ms = j.settings.get("web.extra." + p.id + ".latency", "");
            card.addView(Ui.text(ctx,
                "Последняя успешная проверка: " + new java.text.SimpleDateFormat("dd.MM, HH:mm",
                    java.util.Locale.ROOT).format(new java.util.Date(parseLong(when))) +
                    (ms.isEmpty() ? "" : " · " + ms + " мс"),
                Ui.CAPTION, Ui.P.text3), Ui.lpm(-1, -2, 0, Ui.dp(9), 0, 0));
        }
        return card;
    }

    private boolean isVerifiedKey(ExtraSearch.Provider p) {
        String current = ExtraSearch.fingerprint(p.id, j.secrets.get(p.secret));
        return !current.isEmpty() && current.equals(j.settings.get(ExtraSearch.keyChecked(p.id), ""));
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
    }

    private void editKey(final ExtraSearch.Provider p) {
        Dialogs.prompt(host, "Ключ " + p.title, "Вставьте API-ключ (старый не показывается)", "",
            false, true, "Сохранить", value -> {
                String key = value.trim();
                if (key.isEmpty()) return;
                j.secrets.set(p.secret, key);
                j.settings.setBool(ExtraSearch.keyEnabled(p.id), false);
                j.settings.remove(ExtraSearch.keyChecked(p.id));
                j.settings.remove(ExtraSearch.keyCheckedAt(p.id));
                host.overlays().toast("Ключ сохранён. Для активации нажмите «Проверить».", 1);
                render();
            });
    }

    private void test(final ExtraSearch.Provider p) {
        if (!j.secrets.has(p.secret)) {
            host.overlays().toast("Сначала сохраните API-ключ", 2); return;
        }
        if (!testing.isEmpty()) {
            host.overlays().toast("Дождитесь завершения текущей проверки", 0); return;
        }
        testing = p.id;
        render();
        final String key = j.secrets.get(p.secret);
        final String hash = ExtraSearch.fingerprint(p.id, key);
        new Thread(() -> {
            final long started = System.currentTimeMillis();
            String error = "";
            int total = 0;
            try {
                WebConfig cfg = WebConfig.from(j.settings::get, j.secrets::get,
                    j.settings.get(Settings.LLM_PROXY, ""));
                SearchBackend backend = ExtraSearch.backend(p.id, key, cfg.proxy, System::currentTimeMillis);
                List<SearchHit> hits = backend.search(
                    new SearchRequest("Android official documentation", 3, "", ""),
                    new CancelToken());
                if (hits != null) for (SearchHit hit : hits)
                    if (hit.url.startsWith("https://") && !hit.title.isEmpty()) total++;
                if (total == 0) error = "Сервис ответил, но не вернул читаемых ссылок.";
            } catch (Exception e) {
                error = e instanceof app.jarvis.core.web.WebException
                    ? ((app.jarvis.core.web.WebException) e).userMessage : e.getClass().getSimpleName();
            }
            final String resultError = error;
            final int found = total;
            final long ms = System.currentTimeMillis() - started;
            j.main.post(() -> {
                testing = "";
                // A key changed while checking must never activate a different credential.
                if (!hash.equals(ExtraSearch.fingerprint(p.id, j.secrets.get(p.secret)))) {
                    host.overlays().toast("Ключ изменился во время проверки. Повторите тест.", 2);
                } else if (resultError.isEmpty()) {
                    j.settings.set(ExtraSearch.keyChecked(p.id), hash);
                    j.settings.set(ExtraSearch.keyCheckedAt(p.id), String.valueOf(System.currentTimeMillis()));
                    j.settings.set("web.extra." + p.id + ".latency", String.valueOf(ms));
                    j.settings.setBool(ExtraSearch.keyEnabled(p.id), true);
                    host.overlays().toast(p.title + ": " + found + " ссылок, " + ms + " мс. Включён.", 1);
                } else {
                    j.settings.setBool(ExtraSearch.keyEnabled(p.id), false);
                    j.settings.remove(ExtraSearch.keyChecked(p.id));
                    j.settings.remove(ExtraSearch.keyCheckedAt(p.id));
                    Dialogs.info(host, "Поиск " + p.title + " не прошёл проверку", resultError +
                        "\nКлюч остался сохранён. Проверьте настройки, квоту и подключение.");
                }
                render();
            });
        }, "jv-search-check-" + p.id).start();
    }

    public static String statusLine(app.jarvis.rt.Jarvis j) {
        int enabled=0;
        for (ExtraSearch.Provider p : ExtraSearch.CATALOG)
            if (ExtraSearch.validated(p.id,j.settings::get,j.secrets::get)) enabled++;
        return enabled==0 ? "дополнительные источники не включены" : enabled + " провайдеров проверено и включено";
    }
}
