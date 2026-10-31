package app.jarvis.ui.screens;

import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.llm.ProviderPreset;
import app.jarvis.core.llm.Presets;
import app.jarvis.data.Settings;
import app.jarvis.ui.HelpPopup;
import app.jarvis.ui.Host;
import app.jarvis.ui.Screen;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import app.jarvis.ui.settings.AdvancedPage;
import app.jarvis.ui.settings.AppearancePage;
import app.jarvis.ui.settings.BackgroundPage;
import app.jarvis.ui.settings.DiagnosticsPage;
import app.jarvis.ui.settings.LlmPage;
import app.jarvis.ui.settings.MusicPage;
import app.jarvis.ui.settings.SandboxPage;
import app.jarvis.ui.settings.SettingsPage;
import app.jarvis.ui.voiceui.AdbSettingsPage;
import app.jarvis.ui.voiceui.VoiceSettingsPage;
import app.jarvis.ui.voiceui.WakeSettingsPage;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings: a compact list of sections (icon + title + live status line) that opens sub-pages in place.
 * Pages are registered in {@link #PAGES} by id (navigation targets like "settings/llm" open them directly);
 * other agents add pages there.
 */
public class SettingsScreen extends Screen {
    /** Page ids in display order; the list groups them into cards below. */
    private static final String[] PAGES = {"llm", "voice", "wake", "sounds", "music", "sandbox", "adb", "background", "appearance", "advanced", "diagnostics", "mcp", "mcpsrv", "telegram", "context", "triggers"};

    private static final String[] TITLES = {"Языковая модель (LLM)", "Голос и озвучка", "Обращение и распознавание", "Звук и микрофон", "Музыка",
        "Песочница и инструменты", "ADB по Wi-Fi", "Фон и батарея", "Внешний вид", "Дополнительно", "Диагностика", "Мост для агента", "Внешние MCP-серверы", "Telegram-аккаунты",
        "Контекст и промпт", "Фразы-триггеры"};

    private static final int[] ICONS = {R.drawable.ic_bot, R.drawable.ic_voice, R.drawable.ic_ear, R.drawable.ic_volume, R.drawable.ic_music,
        R.drawable.ic_folder, R.drawable.ic_plug, R.drawable.ic_battery, R.drawable.ic_palette, R.drawable.ic_wrench, R.drawable.ic_activity, R.drawable.ic_plug,
        R.drawable.ic_plug, R.drawable.ic_plug, R.drawable.ic_layers, R.drawable.ic_bolt};

    /** Groups of page ids, each rendered as one card with a section title. */
    private static final String[][] GROUPS = {{"llm", "context"}, {"voice", "wake", "sounds"}, {"music", "triggers", "sandbox"}, {"adb", "background"}, {"appearance"}, {"advanced", "diagnostics", "mcp", "mcpsrv", "telegram"}};

    private static final String[] GROUP_TITLES = {"Подключение", "Голос", "Музыка и данные", "Система", "Интерфейс", "Обслуживание"};

    /** Short "?" text per page id, same order as {@link #PAGES}. */
    private static final String[][] HELP = {
        {"Модель, которая понимает запросы и отвечает.", "Ключ API хранится на этом устройстве, в зашифрованном виде."},
        {"Как ассистент говорит вслух: провайдер, голос и скорость.", "Проверка озвучки на отдельной вкладке «Голос»."},
        {"Как ассистент слышит обращение и распознаёт речь.", "Список обращений можно менять, если нужны другие слова."},
        {"Звуки начала и конца записи: какой звук, громкость, вибрация, свои файлы и наборы из интернета.", "Микрофон уступается Telegram и звонкам и возвращается, когда освободится."},
        {"Любимая песня и приветствие, которое проигрывается из файлов песочницы."},
        {"Файлы, которые видит ассистент, и инструменты, которые он может вызывать."},
        {"Беспроводная отладка Android для команд на другом устройстве.", "Сопряжение нужно один раз; подключение — при новом сеансе."},
        {"Разрешения системы, без которых фоновая работа может прерываться."},
        {"Тема и анимации интерфейса. Тема меняется сразу, без перезапуска."},
        {"Журнал, очистка истории и редкие параметры."},
        {"Проверки работы: сеть, микрофон, модели и сохранение. Запускаются только по нажатию."},
        {"Локальный сервер для удалённой проверки приложения разработчиком-агентом. Доступен только на этом телефоне.", "Токен можно скопировать или сбросить."},
        {"Внешние MCP-серверы, чьи инструменты получает ассистент (например, Telegram через Termux).", "Каждый сервер можно отключить и ограничить список инструментов."},
        {"Аккаунты Telegram для агента: вход по коду, QR или импорт сессии. Права и подтверждения настраиваются здесь.", "Сервер Telegram запускается в Termux; приложение только управляет им."},
        {"Что уходит модели при каждом запросе: правила, память, инструменты, история, и сколько это занимает от окна контекста.", "Память, история и напоминания правил настраиваются здесь."},
        {"«Когда говорю X — сделай Y». Готовое действие выполняется сразу, без нейросети; инструкция — для модели, на секунду дольше."},
    };

    private FrameLayout root;
    private ScrollView listScroll;
    private final List<TextView> statusViews = new ArrayList<>();
    private SettingsPage current;
    private LinearLayout pageColumn;
    private TextView pageTitle;

    public SettingsScreen(Host host) { super(host); }

    @Override public String title() { return "Настройки"; }

    /** Currently open sub-page, or null (used by headless previews to drive pages directly). */
    public SettingsPage page() { return current; }

    @Override protected View build() {
        root = new FrameLayout(ctx);
        listScroll = Ui.scroll(ctx);
        listScroll.setFillViewport(true);
        listScroll.addView(buildList());
        root.addView(listScroll, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    private View buildList() {
        LinearLayout col = Ui.column(ctx);
        col.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(24));
        for (int g = 0; g < GROUPS.length; g++) {
            col.addView(Widgets.sectionTitle(ctx, GROUP_TITLES[g]));
            LinearLayout card = Widgets.card(ctx);
            for (int i = 0; i < GROUPS[g].length; i++) {
                final String id = GROUPS[g][i];
                int idx = index(id);
                LinearLayout row = Widgets.row(ctx, ICONS[idx], TITLES[idx], statusFor(id), HelpPopup.button(ctx, host, TITLES[idx], HELP[idx]), new Runnable() { @Override public void run() { openPage(id, null); } });
                row.setTag("row." + id);
                TextView st = (TextView) row.findViewWithTag("subtitle");
                if (st != null) { st.setTag("status." + id); statusViews.add(st); }
                if (i > 0) Widgets.divide(card);
                card.addView(row);
            }
            col.addView(card);
        }
        TextView foot = Ui.text(ctx, "Jarvis " + app.jarvis.BuildConfig.VERSION_NAME + " · сборка на устройстве", Ui.CAPTION, Ui.P.text3);
        foot.setGravity(Gravity.CENTER);
        col.addView(foot, Ui.lpm(-1, -2, 0, 24, 0, 0));
        return col;
    }

    private static int index(String id) {
        for (int i = 0; i < PAGES.length; i++) if (PAGES[i].equals(id)) return i;
        return 0;
    }

    /** Live one-line status for the section list; cheap (settings and system calls only, no disk/network). */
    private String statusFor(String id) {
        try {
            switch (id) {
                case "llm": {
                    ProviderPreset p = Presets.get(j.providerId());
                    String model = j.modelFor(j.providerId());
                    String name = p == null ? "провайдер не выбран" : p.name;
                    return model.isEmpty() ? name : name + " · " + model;
                }
                case "voice": return "скорость " + String.format(java.util.Locale.US, "%.2f×", j.settings.getFloat(Settings.TTS_SPEED, 1f));
                case "wake": return j.settings.getBool(Settings.WAKE_ENABLED, false) ? "слушает «Джарвис»" : "выключено";
                case "sounds": return app.jarvis.ui.settings.SoundsPage.statusLine(j);
                case "music": {
                    String fav = j.settings.get(Settings.MUSIC_FAVORITE);
                    if (fav.isEmpty()) return "любимая песня не выбрана";
                    int s = fav.lastIndexOf('/');
                    return "любимая: " + (s >= 0 ? fav.substring(s + 1) : fav);
                }
                case "sandbox": return j.settings.getBool(Settings.SANDBOX_EXTERNAL, false) ? "память приложения + общая" : "память приложения";
                case "adb": {
                    String adbHost = j.settings.get(Settings.ADB_HOST);
                    return adbHost.isEmpty() ? "не настроено" : "адрес " + adbHost;
                }
                case "background": return batteryIgnored() ? "фон разрешён системой" : "батарея может ограничивать";
                case "appearance": {
                    String t = j.settings.get(Settings.UI_THEME, "dark");
                    return t.equals("light") ? "светлая тема" : t.equals("system") ? "системная тема" : "тёмная тема";
                }
                case "advanced": return "версия " + app.jarvis.BuildConfig.VERSION_NAME;
                case "diagnostics": return "9 проверок по запросу";
                case "mcp": return app.jarvis.ui.settings.McpPage.statusLine(j);
                case "mcpsrv": return app.jarvis.ui.settings.McpServersPage.statusLine(j);
                case "telegram": return app.jarvis.ui.settings.TelegramPage.statusLine(j);
                case "context": return app.jarvis.ui.settings.ContextPage.statusLine(j);
                case "triggers": return app.jarvis.ui.settings.TriggersPage.statusLine(j);
                default: return "";
            }
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean batteryIgnored() {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(android.content.Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private void refreshStatuses() {
        for (TextView t : statusViews) {
            String tag = (String) t.getTag();
            String id = tag.substring("status.".length());
            t.setText(statusFor(id));
        }
    }

    // --------------------------------------------------------------------------------------- sub-pages
    /** @param deepLink optional "voice"/"adb/…" part after the page id, forwarded to the page via onShow. */
    public void openPage(String id, String deepLink) {
        if (PAGES.length == 0) return;
        SettingsPage page = createPage(id);
        if (page == null) return;
        if (current != null) closePage(false);
        current = page;
        page.attach(this);
        if (pageColumn == null) {
            pageColumn = Ui.column(ctx);
            LinearLayout header = Widgets.pageHeader(ctx, "", new Runnable() { @Override public void run() { closePage(true); } });
            pageTitle = (TextView) header.getChildAt(1);
            pageColumn.addView(header, new LinearLayout.LayoutParams(-1, -2));
            root.addView(pageColumn, new FrameLayout.LayoutParams(-1, -1));
        }
        pageTitle.setText(page.title());
        FrameLayout content = new FrameLayout(ctx);
        content.addView(page.view(), new FrameLayout.LayoutParams(-1, -1));
        pageColumn.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        pageColumn.setVisibility(View.VISIBLE);
        page.onShow();
        refreshStatuses();
        if (!Ui.reduceMotion) {
            pageColumn.setTranslationX(Ui.dp(48));
            pageColumn.setAlpha(0f);
            pageColumn.animate().translationX(0f).alpha(1f).setDuration(app.jarvis.ui.motion.Motion.screenTime(220))
                .withEndAction(new Runnable() { @Override public void run() { if (current != null) listScroll.setVisibility(View.INVISIBLE); } }).start();
            listScroll.animate().translationX(-Ui.dp(48)).alpha(0.35f)
                .setDuration(app.jarvis.ui.motion.Motion.screenTime(220)).start();
        } else {
            pageColumn.setTranslationX(0);
            pageColumn.setAlpha(1f);
            listScroll.setVisibility(View.INVISIBLE); // the page covers the list; keep it from grabbing touch
        }
    }

    private void closePage(boolean animate) {
        if (current == null) return;
        final SettingsPage p = current;
        current = null;
        p.onHide();
        final LinearLayout pc = pageColumn;
        // remove exactly this page's content; a page opened mid-animation must survive
        final View stale = pc.getChildCount() > 1 ? pc.getChildAt(pc.getChildCount() - 1) : null;
        Runnable finish = new Runnable() { @Override public void run() {
            if (stale != null) pc.removeView(stale);
            if (pc.getChildCount() <= 1) {   // only the header remains
                pc.setVisibility(View.GONE);
                listScroll.setVisibility(View.VISIBLE);
            }
        } };
        if (animate && !Ui.reduceMotion) {
            pc.animate().translationX(Ui.dp(64)).alpha(0f).setDuration(app.jarvis.ui.motion.Motion.screenTime(180)).withEndAction(new Runnable() { @Override public void run() {
                pc.setTranslationX(0); pc.setAlpha(1f); finish.run();
            } }).start();
            listScroll.animate().translationX(0).alpha(1f)
                .setDuration(app.jarvis.ui.motion.Motion.screenTime(180)).start();
        } else finish.run();
        refreshStatuses();
    }

    private SettingsPage createPage(String id) {
        try {
            switch (id) {
                case "llm": return new LlmPage(host);
                case "voice": return new VoiceSettingsPage(host);
                case "wake": return new WakeSettingsPage(host);
                case "sounds": return new app.jarvis.ui.settings.SoundsPage(host);
                case "adb": return new AdbSettingsPage(host);
                case "music": return new MusicPage(host);
                case "sandbox": return new SandboxPage(host);
                case "background": return new BackgroundPage(host);
                case "appearance": return new AppearancePage(host);
                case "advanced": return new AdvancedPage(host);
                case "diagnostics": return new DiagnosticsPage(host);
                case "mcp": return new app.jarvis.ui.settings.McpPage(host);
                case "mcpsrv": return new app.jarvis.ui.settings.McpServersPage(host);
                case "telegram": return new app.jarvis.ui.settings.TelegramPage(host);
                case "context": return new app.jarvis.ui.settings.ContextPage(host);
                case "triggers": return new app.jarvis.ui.settings.TriggersPage(host);
                default: return null;
            }
        } catch (Throwable t) {
            j.journal.e("system", "Не удалось открыть страницу настроек " + id, t);
            host.overlays().toast("Страница не открылась: " + t.getClass().getSimpleName(), 2);
            return null;
        }
    }

    @Override public void onShow(String arg) {
        refreshStatuses();
        if (arg != null && !arg.isEmpty()) {
            int s = arg.indexOf('/');
            openPage(s > 0 ? arg.substring(0, s) : arg, s > 0 ? arg.substring(s + 1) : null);
        } else if (current == null && pageColumn != null && pageColumn.getVisibility() == View.VISIBLE) {
            // navigated to plain "settings" while a page was left open: restore the list
            closePage(false);
        }
    }

    @Override public void onHide() { if (current != null) current.onHide(); }

    @Override public boolean onBack() {
        if (current != null) { closePage(true); return true; }
        return false;
    }

    @Override public void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        if (current != null) current.onActivityResult(requestCode, resultCode, data);
    }

    @Override public void onPermissionResult(String permission, boolean granted) {
        if (current != null) current.onPermissionResult(permission, granted);
        refreshStatuses();
    }
}
