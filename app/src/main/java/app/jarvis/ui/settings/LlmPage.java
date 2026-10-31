package app.jarvis.ui.settings;

import android.app.Activity;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.LlmConfig;
import app.jarvis.core.llm.LlmException;
import app.jarvis.core.llm.LlmListener;
import app.jarvis.core.llm.LlmRequest;
import app.jarvis.core.llm.LlmResult;
import app.jarvis.core.llm.ModelCaps;
import app.jarvis.core.llm.ModelCatalog;
import app.jarvis.core.llm.ModelInfo;
import app.jarvis.core.llm.Presets;
import app.jarvis.core.llm.ProviderPreset;
import app.jarvis.core.llm.RecentModels;
import app.jarvis.core.net.Http;
import app.jarvis.core.util.CancelToken;
import app.jarvis.data.Settings;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import app.jarvis.ui.settings.llm.CapChips;
import app.jarvis.ui.settings.llm.GenerationCard;
import app.jarvis.ui.settings.llm.KeyBlock;
import app.jarvis.ui.settings.llm.ModelBrowser;
import app.jarvis.ui.settings.llm.ModelSwitch;
import app.jarvis.ui.settings.llm.ProviderSheet;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

/**
 * LLM settings: the provider in one row (its list opens in a sheet), the key and address of that provider, the connection check,
 * the current model with its capabilities, recent models, generation parameters that the model actually supports, and fallbacks.
 * Every model or provider change goes through {@link ModelSwitch}.
 */
public class LlmPage extends SettingsPage {
    static final int REQ_IMPORT_KEYS = 4101;

    private LinearLayout content;
    private LinearLayout providerHost;
    private LinearLayout configHost;
    private LinearLayout checkHost;
    private LinearLayout modelCardHost;
    private LinearLayout recentHost;
    private LinearLayout generationHost;
    private LinearLayout fallbackCard;
    private boolean checking;
    private final CancelToken checkCancel = new CancelToken();
    private final ModelCatalog.Listener catalogListener = new ModelCatalog.Listener() {
        @Override public void onCatalogChanged(final String providerId) {
            j.main.post(new Runnable() { @Override public void run() {
                if (providerId.equals(j.providerId()) || ModelCatalog.OPENROUTER.equals(providerId)) rebuildModelCard();
            } });
        }
    };

    public LlmPage(Host host) { super(host); }

    @Override public String title() { return "Языковая модель (LLM)"; }

    @Override public void onShow() { j.catalog.addListener(catalogListener); }

    @Override public void onHide() { j.catalog.removeListener(catalogListener); }

    @Override protected View build() {
        ScrollView sv = Ui.scroll(ctx);
        sv.setFillViewport(true);
        content = Ui.column(ctx);
        content.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(24));
        content.addView(Widgets.sectionTitle(ctx, "Провайдер"));
        providerHost = Ui.column(ctx);
        content.addView(providerHost);
        rebuildProviderRow();
        configHost = Ui.column(ctx);
        content.addView(configHost);
        rebuildConfig();
        content.addView(Widgets.sectionTitle(ctx, "Модель"));
        modelCardHost = Ui.column(ctx);
        content.addView(modelCardHost);
        rebuildModelCard();
        content.addView(Widgets.sectionTitle(ctx, "Недавние"));
        recentHost = Ui.column(ctx);
        content.addView(recentHost);
        rebuildRecent();
        content.addView(Widgets.sectionTitle(ctx, "Генерация"));
        generationHost = Ui.column(ctx);
        content.addView(generationHost);
        rebuildGeneration();
        content.addView(Widgets.sectionTitle(ctx, "Запасные провайдеры"));
        fallbackCard = fallbackCard();
        content.addView(fallbackCard);
        sv.addView(content);
        return sv;
    }

    private void rebuildAll() {
        rebuildProviderRow();
        rebuildConfig();
        rebuildModelCard();
        rebuildRecent();
        rebuildGeneration();
    }

    // ------------------------------------------------------------------------------- provider row
    private void rebuildProviderRow() {
        providerHost.removeAllViews();
        ProviderPreset p = Presets.get(j.providerId());
        LinearLayout card = Widgets.card(ctx);
        card.addView(Widgets.row(ctx, 0, p == null ? "Провайдер не выбран" : p.name,
            p == null || p.note.isEmpty() ? "Нажмите, чтобы выбрать из списка" : p.note,
            keyBadge(p), new Runnable() { @Override public void run() { openProviders(); } }));
        providerHost.addView(card);
    }

    private View keyBadge(ProviderPreset p) {
        if (p == null || !p.keyRequired) return Widgets.pill(ctx, "ключ не нужен", Widgets.MUTED);
        if (j.secrets.has("key." + p.id)) return Widgets.pill(ctx, "ключ задан", Widgets.OK);
        return Widgets.pill(ctx, "ключа нет", Widgets.WARN);
    }

    private void openProviders() {
        ProviderSheet.open(host, new ProviderSheet.Pick() { @Override public void on(String pid) {
            ModelSwitch.request(host, pid, j.modelFor(pid), new Runnable() { @Override public void run() { rebuildAll(); } });
        } });
    }

    // ------------------------------------------------------------------------------- current model
    private void rebuildModelCard() {
        modelCardHost.removeAllViews();
        final String pid = j.providerId();
        final String model = j.modelFor(pid);
        ProviderPreset p = Presets.get(pid);
        ModelCaps caps = j.catalog.caps(pid, model);
        LinearLayout card = Widgets.card(ctx);
        LinearLayout head = Ui.column(ctx);
        head.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), 0);
        TextView t = Ui.text(ctx, model.isEmpty() ? "Модель не выбрана" : model, Ui.BODY);
        t.setTypeface(Ui.mono);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        head.addView(t);
        head.addView(Ui.text(ctx, (p == null ? pid : p.name) + " · " + sourceLabel(caps), Ui.CAPTION, Ui.P.text3), Ui.lpm(-2, -2, 0, Ui.dp(2), 0, 0));
        card.addView(head);
        if (!model.isEmpty()) {
            card.addView(CapChips.chips(ctx, caps), Ui.lpm(-1, -2, Ui.dp(16), Ui.dp(8), Ui.dp(16), 0));
            TextView price = CapChips.priceLine(ctx, caps);
            if (price != null) card.addView(price, Ui.lpm(-1, -2, Ui.dp(16), Ui.dp(6), Ui.dp(16), 0));
        }
        ModelCatalog.Status st = j.catalog.status(pid);
        card.addView(Ui.text(ctx, statusLine(st), Ui.CAPTION, st.lastError.isEmpty() ? Ui.P.text3 : Ui.P.warn),
            Ui.lpm(-1, -2, Ui.dp(16), Ui.dp(8), Ui.dp(16), 0));
        Widgets.divide(card);
        LinearLayout btns = Ui.row(ctx);
        btns.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(14), Ui.dp(12));
        btns.addView(Ui.button(ctx, "Выбрать модель", R.drawable.ic_edit, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() {
            ModelBrowser.open(host, pid, new Runnable() { @Override public void run() { rebuildAll(); } });
        } }));
        btns.addView(Ui.space(ctx, 8, 1));
        btns.addView(Ui.button(ctx, "Обновить список", R.drawable.ic_refresh, Ui.BTN_GLASS, new Runnable() { @Override public void run() {
            j.catalog.refresh(pid);
        } }));
        card.addView(btns);
        modelCardHost.addView(card);
    }

    private String sourceLabel(ModelCaps caps) {
        switch (caps.source) {
            case PROVIDER: return "данные провайдера";
            case OPENROUTER: return "каталог OpenRouter";
            case BUILTIN: return "встроенная таблица";
            case LEARNED: return "проверено ответом провайдера";
            default: return "провайдер не сообщает";
        }
    }

    private String statusLine(ModelCatalog.Status st) {
        if (st.loading) return "Загружаю список моделей…";
        if (!st.lastError.isEmpty()) return "Ошибка загрузки: " + st.lastError + (st.lastHttp > 0 ? " (HTTP " + st.lastHttp + ")" : "") + ". Записано в журнал.";
        if (st.fetchedAt <= 0) return "Список моделей ещё не загружен: нажмите «Выбрать модель».";
        return "Моделей: " + st.count + " · обновлено " + CapChips.ago(System.currentTimeMillis() - st.fetchedAt);
    }

    // ------------------------------------------------------------------------------- recent
    private void rebuildRecent() {
        recentHost.removeAllViews();
        List<String> entries = j.settings.getList(Settings.LLM_RECENT);
        if (entries.isEmpty()) {
            recentHost.addView(Ui.text(ctx, "Здесь появятся последние выбранные модели (до восьми).", Ui.CAPTION, Ui.P.text3));
            return;
        }
        String current = RecentModels.key(j.providerId(), j.modelFor(j.providerId()));
        HorizontalScrollView sv = new HorizontalScrollView(ctx);
        sv.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = Ui.row(ctx);
        for (String entry : entries) {
            final String[] parts = RecentModels.split(entry);
            if (parts == null) continue;
            ProviderPreset pp = Presets.get(parts[0]);
            boolean on = entry.equals(current);
            LinearLayout tile = Ui.column(ctx);
            tile.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(8));
            tile.setBackground(on ? Ui.rect(Ui.P.accentSoft, 12) : Ui.glass(12));
            TextView m = Ui.text(ctx, parts[1], Ui.CAPTION, on ? Ui.P.accent : Ui.P.text);
            m.setTypeface(Ui.mono);
            m.setSingleLine(true);
            m.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            m.setMaxWidth(Ui.dp(180));
            tile.addView(m);
            TextView pn = Ui.text(ctx, pp == null ? parts[0] : pp.name, Ui.CAPTION, Ui.P.text3);
            pn.setSingleLine(true);
            tile.addView(pn);
            tile.setClickable(true);
            tile.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                ModelSwitch.request(host, parts[0], parts[1], new Runnable() { @Override public void run() { rebuildAll(); } });
            } });
            strip.addView(tile, Ui.lpm(-2, -2, 0, 0, Ui.dp(8), 0));
        }
        sv.addView(strip);
        recentHost.addView(sv);
    }

    // ------------------------------------------------------------------------------- generation
    private void rebuildGeneration() {
        generationHost.removeAllViews();
        generationHost.addView(GenerationCard.build(host));
    }

    private void rebuildConfig() {
        configHost.removeAllViews();
        final ProviderPreset p = Presets.get(j.providerId());
        if (p == null) {
            configHost.addView(PageUi.note(ctx, "Провайдер не выбран."));
            return;
        }
        final String pid = p.id;
        LinearLayout card = Widgets.card(ctx);
        TextView head = Ui.text(ctx, p.name, Ui.BODY);
        head.setTypeface(Ui.medium);
        card.addView(head, Ui.lpm(-2, -2, Ui.dp(16), Ui.dp(12), Ui.dp(16), 0));
        if (!p.note.isEmpty()) card.addView(PageUi.caption(ctx, p.note), Ui.lpm(-2, -2, Ui.dp(16), Ui.dp(4), Ui.dp(16), 0));
        card.addView(PageUi.note(ctx, "Проверено: " + (p.verified == null || p.verified.isEmpty() ? "нет данных" : p.verified)),
            Ui.lpm(-2, -2, Ui.dp(16), Ui.dp(3), Ui.dp(16), Ui.dp(12)));

        if (p.keyRequired || j.secrets.has("key." + pid)) {
            Widgets.divide(card);
            card.addView(new KeyBlock(host, p, new Runnable() { @Override public void run() { rebuildProviderRow(); } }).view());
        }
        if (pid.equals("custom")) {
            Widgets.divide(card);
            card.addView(baseUrlBlock(p));
        }

        Widgets.divide(card);
        checkHost = Ui.column(ctx);
        checkHost.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        renderCheckIdle();
        card.addView(checkHost);
        Widgets.divide(card);
        LinearLayout importRow = Ui.row(ctx);
        importRow.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(14), Ui.dp(12));
        importRow.addView(Ui.button(ctx, "Импорт ключей из файла", R.drawable.ic_upload, Ui.BTN_GHOST, new Runnable() { @Override public void run() {
            host.pickDocument(screen(), REQ_IMPORT_KEYS, new String[]{"application/json", "text/plain"}, false);
        } }));
        card.addView(importRow);
        configHost.addView(card);
        configHost.addView(proxyCard());
    }

    private View baseUrlBlock(final ProviderPreset p) {
        LinearLayout block = Ui.column(ctx);
        block.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        TextView t = Ui.text(ctx, "Адрес API", Ui.BODY);
        t.setTypeface(Ui.medium);
        block.addView(t);
        block.addView(PageUi.note(ctx, "OpenAI-совместимый адрес; обычно заканчивается на /v1"), Ui.lpm(-2, -2, 0, Ui.dp(2), 0, 0));
        final EditText e = PageUi.saveField(ctx, p.baseUrl.isEmpty() ? "https://…/v1" : p.baseUrl, j.settings.get("llm.baseUrl." + p.id), new PageUi.TextListener() { @Override public void on(String s) {
            String v = s.trim();
            if (!v.isEmpty() && !v.startsWith("http://") && !v.startsWith("https://")) {
                host.overlays().toast("Адрес должен начинаться с http:// или https://", 2);
                return;
            }
            if (v.isEmpty()) j.settings.remove("llm.baseUrl." + p.id); else j.settings.set("llm.baseUrl." + p.id, v);
            host.overlays().toast(v.isEmpty() ? "Адрес сброшен" : "Адрес сохранён", 0);
        } });
        block.addView(Widgets.fieldBox(ctx, e), Ui.lpm(-1, -2, 0, Ui.dp(8), 0, 0));
        return block;
    }

    private View proxyCard() {
        LinearLayout card = Widgets.card(ctx);
        TextView t = Ui.text(ctx, "Прокси для LLM", Ui.BODY);
        t.setTypeface(Ui.medium);
        card.addView(t, Ui.lpm(-2, -2, Ui.dp(16), Ui.dp(12), Ui.dp(16), 0));
        card.addView(PageUi.note(ctx, "http://хост:порт или socks5://хост:порт; пусто — прямое подключение. Действует только на запросы к языковой модели."),
            Ui.lpm(-2, -2, Ui.dp(16), Ui.dp(2), Ui.dp(16), 0));
        final EditText e = PageUi.saveField(ctx, "например, socks5://127.0.0.1:1080", j.settings.get(Settings.LLM_PROXY), new PageUi.TextListener() { @Override public void on(String s) {
            String v = s.trim();
            if (v.isEmpty()) { j.settings.remove(Settings.LLM_PROXY); host.overlays().toast("Прокси отключён", 0); return; }
            try {
                Http.parseProxy(v);
                j.settings.set(Settings.LLM_PROXY, v);
                host.overlays().toast("Прокси сохранён", 1);
            } catch (Throwable err) {
                host.overlays().toast("Неверный прокси: " + err.getMessage(), 2);
            }
        } });
        card.addView(Widgets.fieldBox(ctx, e), Ui.lpm(-1, -2, Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(14)));
        return card;
    }

    // ------------------------------------------------------------------------------- connection check
    private void renderCheckIdle() {
        checkHost.removeAllViews();
        checkHost.addView(Ui.button(ctx, "Проверить подключение", R.drawable.ic_activity, Ui.BTN_GLASS, new Runnable() { @Override public void run() { runConnectionCheck(); } }));
        checkHost.addView(PageUi.note(ctx, "Реальный запрос: список моделей и короткий тестовый ответ."), Ui.lpm(-2, -2, 0, Ui.dp(8), 0, 0));
    }

    /** Runs GET /models then one tiny streaming completion; also invoked from headless previews. */
    public void runConnectionCheck() {
        if (checking) { checkCancel.cancel(); return; }
        final Jarvis.LlmChoice ch = j.choiceFor(j.providerId(), null);
        if (ch == null) {
            renderCheckFail(new LlmException(LlmException.Kind.CONFIG, 0, "Провайдер не найден", "preset missing", 0, null, null));
            return;
        }
        checking = true;
        setCheckText("Запрашиваю список моделей…");
        Thread th = new Thread(new Runnable() { @Override public void run() {
            try {
                final List<ModelInfo> ms = j.llm.listModels(ch.config, checkCancel);
                j.main.post(new Runnable() { @Override public void run() { if (checking) setCheckText("Запрашиваю тестовый ответ…"); } });
                if (ch.model.isEmpty())
                    throw new LlmException(LlmException.Kind.CONFIG, 0, "Не выбрана модель — задайте её в разделе «Модель»", "model is empty", 0, null, null);
                LlmRequest req = new LlmRequest();
                req.model = ch.model;
                req.messages.add(ChatMessage.user("Ответь одним словом: да"));
                req.maxTokens = 20;
                req.stream = true;
                long t0 = System.nanoTime();
                LlmResult r = j.llm.chat(ch.config, req, LlmListener.NOP, checkCancel);
                final long total = (System.nanoTime() - t0) / 1_000_000L;
                final int n = ms.size();
                final String answer = r.content.trim();
                final long first = r.firstDeltaMs;
                j.main.post(new Runnable() { @Override public void run() { renderCheckOk(n, first, total, answer); } });
            } catch (LlmException e) {
                final LlmException fe = e;
                j.main.post(new Runnable() { @Override public void run() { renderCheckFail(fe); } });
            } catch (Throwable t2) {
                final LlmException fe = new LlmException(LlmException.Kind.PROTOCOL, 0, "Неожиданная ошибка проверки",
                    t2.getClass().getSimpleName() + ": " + t2.getMessage(), 0, null, t2);
                j.main.post(new Runnable() { @Override public void run() { renderCheckFail(fe); } });
            } finally {
                checking = false;
            }
        } }, "jv-llm-check");
        th.setDaemon(true);
        th.start();
    }

    private void setCheckText(String s) {
        checkHost.removeAllViews();
        LinearLayout run = Ui.row(ctx);
        run.addView(Widgets.spinner(ctx, 20));
        TextView t = Ui.text(ctx, " " + s, Ui.BODY, Ui.P.text2);
        run.addView(t);
        checkHost.addView(run);
    }

    private void renderCheckOk(int modelCount, long firstMs, long totalMs, String answer) {
        checkHost.removeAllViews();
        LinearLayout row = Ui.row(ctx);
        row.addView(Widgets.pill(ctx, "подключение есть", Widgets.OK));
        row.addView(Ui.space(ctx, 8, 1));
        row.addView(Ui.iconButton(ctx, R.drawable.ic_refresh, 36, Ui.P.text2, new Runnable() { @Override public void run() { renderCheckIdle(); } }));
        checkHost.addView(row);
        checkHost.addView(PageUi.caption(ctx, "Моделей: " + PageUi.group(modelCount)
            + " · первый токен: " + (firstMs < 0 ? totalMs : firstMs) + " мс"
            + " · всего: " + totalMs + " мс"), Ui.lpm(-2, -2, 0, Ui.dp(8), 0, 0));
        checkHost.addView(PageUi.note(ctx, "Ответ модели: " + (answer.isEmpty() ? "(пусто)" : "«" + (answer.length() > 40 ? answer.substring(0, 40) + "…" : answer) + "»")),
            Ui.lpm(-2, -2, 0, Ui.dp(3), 0, 0));
    }

    private void renderCheckFail(final LlmException e) {
        checkHost.removeAllViews();
        LinearLayout row = Ui.row(ctx);
        row.addView(Widgets.pill(ctx, e.kind == LlmException.Kind.GEO_BLOCK ? "гео-блок" : "ошибка", Widgets.ERR));
        row.addView(Ui.space(ctx, 8, 1));
        row.addView(Ui.iconButton(ctx, R.drawable.ic_refresh, 36, Ui.P.text2, new Runnable() { @Override public void run() { renderCheckIdle(); } }));
        checkHost.addView(row);
        checkHost.addView(PageUi.caption(ctx, e.userMessage), Ui.lpm(-2, -2, 0, Ui.dp(8), 0, 0));
        if (e.kind == LlmException.Kind.GEO_BLOCK)
            checkHost.addView(PageUi.note(ctx, "Сервер закрыл доступ из вашей страны. Варианты: прокси на этой странице, VPN на всём устройстве или Mercury через OpenRouter как запасной провайдер."),
                Ui.lpm(-2, -2, 0, Ui.dp(4), 0, 0));
        LinearLayout btns = Ui.row(ctx);
        btns.addView(Ui.button(ctx, "Подробности", R.drawable.ic_info, Ui.BTN_GHOST, new Runnable() { @Override public void run() {
            Dialogs.info(host, "Технические подробности", e.technical.isEmpty() ? e.toString() : "HTTP " + e.status + "\n" + e.technical);
        } }));
        if (e.kind == LlmException.Kind.GEO_BLOCK)
            btns.addView(Ui.button(ctx, "Как обойти", R.drawable.ic_globe, Ui.BTN_GHOST, new Runnable() { @Override public void run() {
                Dialogs.info(host, "Обход гео-блока",
                    "1. Прокси: поле «Прокси для LLM» — адрес http:// или socks5:// за пределами заблокированного региона. Прокси в Jarvis действует только на запросы к языковой модели.\n\n"
                    + "2. OpenRouter: агрегатор, Mercury доступна как inception/mercury-3 без VPN. Выберите провайдера OpenRouter, получите ключ на openrouter.ai и сохраните его.\n\n"
                    + "3. VPN на всём устройстве тоже подходит, но влияет на весь трафик телефона.");
            } }));
        checkHost.addView(btns, Ui.lpm(-2, -2, 0, Ui.dp(8), 0, 0));
    }

    private static String readable(Throwable t) {
        if (t instanceof LlmException) return ((LlmException) t).userMessage;
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    // ------------------------------------------------------------------------------- fallbacks
    private LinearLayout fallbackCard() {
        LinearLayout card = Widgets.card(ctx);
        LinearLayout intro = Ui.column(ctx);
        intro.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(4));
        TextView t = Ui.text(ctx, "Если основной провайдер недоступен", Ui.BODY);
        t.setTypeface(Ui.medium);
        intro.addView(t);
        intro.addView(PageUi.note(ctx, "Например: Mercury через OpenRouter, если Inception недоступен из вашей страны. Используются только запасные с заданным ключом."),
            Ui.lpm(-2, -2, 0, Ui.dp(4), 0, 0));
        card.addView(intro);
        List<String> sel = j.settings.getList(Settings.LLM_FALLBACKS);
        for (final ProviderPreset p : Presets.all()) {
            boolean on = sel.isEmpty() ? p.id.equals("openrouter") : sel.contains(p.id);
            FrameLayout trail = new FrameLayout(ctx);
            if (on) trail.addView(Ui.icon(ctx, R.drawable.ic_check, 20, Ui.P.accent),
                new FrameLayout.LayoutParams(Ui.dp(20), Ui.dp(20), Gravity.CENTER));
            LinearLayout row = Widgets.row(ctx, 0, p.name, null, trail, new Runnable() { @Override public void run() {
                List<String> cur = j.settings.getList(Settings.LLM_FALLBACKS);
                boolean had = cur.contains(p.id) || (cur.isEmpty() && p.id.equals("openrouter"));
                if (had) cur.remove(p.id); else cur.add(p.id);
                j.settings.setList(Settings.LLM_FALLBACKS, cur);
                refreshFallbackChecks();
            } });
            row.setTag("fbtrail." + p.id);
            if (p.id.equals("openrouter")) {
                LinearLayout tx = (LinearLayout) row.getChildAt(0);
                tx.addView(PageUi.note(ctx, "рекомендуется для обхода гео-блока Inception"), Ui.lpm(-2, -2, 0, Ui.dp(2), 0, 0));
            }
            card.addView(row);
        }
        return card;
    }

    private void refreshFallbackChecks() {
        List<String> sel = j.settings.getList(Settings.LLM_FALLBACKS);
        for (ProviderPreset p : Presets.all()) {
            View row = content.findViewWithTag("fbtrail." + p.id);
            if (!(row instanceof LinearLayout)) continue;
            LinearLayout rl = (LinearLayout) row;
            View trail = rl.getChildAt(rl.getChildCount() - 1);
            if (!(trail instanceof FrameLayout)) continue;
            FrameLayout f = (FrameLayout) trail;
            f.removeAllViews();
            boolean on = sel.isEmpty() ? p.id.equals("openrouter") : sel.contains(p.id);
            if (on) f.addView(Ui.icon(ctx, R.drawable.ic_check, 20, Ui.P.accent),
                new FrameLayout.LayoutParams(Ui.dp(20), Ui.dp(20), Gravity.CENTER));
        }
    }

    // ------------------------------------------------------------------------------- key import
    @Override public void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        if (requestCode != REQ_IMPORT_KEYS || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();
        Thread th = new Thread(new Runnable() { @Override public void run() {
            try {
                InputStream in = ctx.getContentResolver().openInputStream(uri);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                final int count = j.secrets.importJson(bo.toString("UTF-8"));
                j.main.post(new Runnable() { @Override public void run() {
                    host.overlays().toast(count > 0 ? "Импортировано ключей: " + count : "В файле не найдено ключей", count > 0 ? 1 : 2);
                    rebuildProviderRow();
                    rebuildConfig();
                } });
            } catch (Exception e) {
                j.main.post(new Runnable() { @Override public void run() { host.overlays().toast("Не удалось прочитать файл: " + readable(e), 2); } });
            }
        } }, "jv-import-keys");
        th.setDaemon(true);
        th.start();
    }
}
