package app.jarvis.mcp;

import android.view.KeyEvent;
import app.jarvis.core.mcp.McpRegistry;
import app.jarvis.core.mcp.McpResult;
import app.jarvis.core.mcp.McpTools;
import app.jarvis.core.util.J;
import app.jarvis.data.Settings;
import app.jarvis.rt.Jarvis;
import org.json.JSONArray;
import org.json.JSONObject;

/** ui_* tools over a {@link UiHost}. host=live is the visible window; host=headless is a skeleton until spike S1 (docs/DEVICE_CHECKLIST_mcp2.md). */
final class UiTools {
    private UiTools() { }

    static boolean liveAvailable() { return LiveUiHost.present(); }

    private static final String[] SCREENS = {"home", "agent", "files", "terminal", "studio", "log", "settings", "settings/llm", "settings/voice", "settings/wake",
        "settings/music", "settings/sandbox", "settings/adb", "settings/background", "settings/appearance", "settings/advanced", "settings/diagnostics", "settings/mcp"};

    private interface Op { McpResult run(UiHost h) throws Exception; }

    private static McpResult with(UiHost live, UiHost headless, JSONObject a, Op op) {
        String which = J.str(a, "host", "live");
        UiHost h = "headless".equals(which) ? headless : live;
        try {
            return op.run(h);
        } catch (UiHost.NotImplemented e) {
            return Tl.err("NOT_IMPLEMENTED", "Невидимое окно ещё не готово: нужен спайк S1 на телефоне. Используйте host=live.", e.getMessage());
        } catch (UiHost.NotVisible e) {
            return Tl.err("NOT_VISIBLE", e.getMessage(), "ui host " + h.id());
        } catch (UiHost.Unavailable e) {
            return Tl.err("UI_UNAVAILABLE", e.getMessage(), "ui host " + h.id());
        } catch (Exception e) {
            return Tl.err("UI_ERROR", "Операция с интерфейсом не удалась: " + e.getClass().getSimpleName(), String.valueOf(e));
        }
    }

    private static McpTools.Schema sel(McpTools.Schema s) {
        return s.str("text", "Текст вида (подстрока; «=текст» — точное совпадение)", false).str("desc", "contentDescription (подстрока)", false)
            .str("res", "Имя ресурса id", false).str("tag", "Подстрока тега вида", false).str("class", "Простое имя класса вида", false)
            .str("path", "Путь из ui_tree, например 0.1.3", false).integer("index", "Какое совпадение брать (с 0)", false)
            .object("selector", "То же самое одним объектом {text,desc,res,tag,class,path,index}", false);
    }

    private static UiHost.Selector selector(JSONObject a) { JSONObject o = a.optJSONObject("selector"); return UiHost.Selector.from(o != null ? o : a); }

    private static McpTools.Schema hostArg(McpTools.Schema s) { return s.oneOf("host", "live (по умолчанию) или headless", false, "live", "headless"); }

    static void register(McpRegistry r, McpBridge b) {
        final Jarvis j = b.j;
        final UiHost live = new LiveUiHost(j);
        final UiHost headless = new HeadlessUiHost(j, new android.view.ContextThemeWrapper(b.ctx, app.jarvis.R.style.Theme_Jarvis));

        r.register(McpTools.tool("ui_hosts", "Какие окна доступны инструментам ui_*: live (то, что видит владелец) и headless (невидимый экран Jarvis, открывается ui_host_open).", (a, c) -> {
            JSONArray arr = new JSONArray().put(live.describe()).put(headless.describe());
            return McpResult.json(J.put(new JSONObject(), "hosts", arr));
        }).readOnly());

        r.register(McpTools.tool("ui_host_open", "Подготовить окно. headless: открыть невидимый экран Jarvis (размер в пикселях, по умолчанию 1080x2340). live: проверка, что окно приложения есть (на передний план не выводится).",
            McpTools.Schema.obj().oneOf("kind", "live или headless", true, "live", "headless").integer("width", "Ширина, px (headless)", false).integer("height", "Высота, px (headless)", false),
            (a, c) -> {
                UiHost h = "headless".equals(J.str(a, "kind")) ? headless : live;
                try {
                    JSONObject d = h.open(Tl.intArg(a, "width", 0), Tl.intArg(a, "height", 0));
                    if (!d.optBoolean("available")) return Tl.err("UI_UNAVAILABLE", d.optString("reason"), d.optString("reason"));
                    return McpResult.json(d);
                } catch (UiHost.NotVisible e) {
                    return Tl.err("NOT_VISIBLE", e.getMessage(), "ui host " + h.id());
                } catch (Exception e) {
                    return Tl.err("UI_ERROR", "Не удалось открыть окно: " + e.getClass().getSimpleName(), String.valueOf(e));
                }
            }));

        r.register(McpTools.tool("ui_screens", "Адреса экранов для ui_navigate (главные разделы и страницы настроек).", (a, c) -> {
            JSONArray arr = new JSONArray();
            for (String s : SCREENS) arr.put(s);
            return McpResult.json(J.put(new JSONObject(), "screens", arr));
        }).readOnly());

        r.register(McpTools.tool("ui_navigate", "Открыть экран: home, agent, files, terminal, studio, log, settings, settings/<страница>.", hostArg(McpTools.Schema.obj().str("screen", "Адрес экрана", true)), (a, c) ->
            with(live, headless, a, h -> {
                h.navigate(J.str(a, "screen"));
                Thread.sleep(350);
                return McpResult.json(J.put(J.put(new JSONObject(), "screen", J.str(a, "screen")), "requested", true));
            })));

        r.register(McpTools.tool("ui_tree", "Дерево видов: класс, res-id, тег, текст, desc, границы, видимость, состояния, путь.",
            hostArg(McpTools.Schema.obj().integer("maxDepth", "Глубина (по умолчанию 12)", false).bool("onlyVisible", "Пропускать скрытые поддеревья (по умолчанию да)", false)), (a, c) ->
            with(live, headless, a, h -> McpResult.json(h.tree(Tl.clamp(Tl.intArg(a, "maxDepth", 12), 1, 40), Tl.boolArg(a, "onlyVisible", true))))).readOnly());

        r.register(McpTools.tool("ui_screenshot", "Снимок окна (JPEG/PNG/WEBP). Видимое окно снимается PixelCopy (с размытием), окно в фоне — программной отрисовкой.",
            hostArg(McpTools.Schema.obj().number("scale", "Масштаб 0.1..1 (по умолчанию 0.5)", false).oneOf("format", "Формат", false, "jpeg", "png", "webp")), (a, c) ->
            with(live, headless, a, h -> {
                float scale = (float) Math.max(0.1, Math.min(1.0, a.optDouble("scale", 0.5)));
                String fmt = J.str(a, "format", "jpeg");
                byte[] img = h.screenshot(scale, fmt);
                String mime = "png".equals(fmt) ? "image/png" : "webp".equals(fmt) ? "image/webp" : "image/jpeg";
                return McpResult.image(android.util.Base64.encodeToString(img, android.util.Base64.NO_WRAP), mime)
                    .addText(J.put(J.put(new JSONObject(), "bytes", img.length), "scale", scale).toString());
            })).readOnly().timeout(30_000));

        r.register(McpTools.tool("ui_tap", "Нажать по координатам окна или по локатору (центр найденного вида).",
            hostArg(sel(McpTools.Schema.obj().number("x", "X в окне", false).number("y", "Y в окне", false))), (a, c) -> with(live, headless, a, h -> {
                UiHost.Selector s = selector(a);
                if (s.empty() && !(a.has("x") && a.has("y"))) return Tl.err("INVALID_ARGS", "Нужны x и y либо локатор.", "no target");
                return McpResult.json(h.tap(s, (float) a.optDouble("x", 0), (float) a.optDouble("y", 0), false));
            })));

        r.register(McpTools.tool("ui_long_press", "Долгое нажатие (700 мс) по координатам или локатору.",
            hostArg(sel(McpTools.Schema.obj().number("x", "X в окне", false).number("y", "Y в окне", false))), (a, c) -> with(live, headless, a, h -> {
                UiHost.Selector s = selector(a);
                if (s.empty() && !(a.has("x") && a.has("y"))) return Tl.err("INVALID_ARGS", "Нужны x и y либо локатор.", "no target");
                return McpResult.json(h.tap(s, (float) a.optDouble("x", 0), (float) a.optDouble("y", 0), true));
            })));

        r.register(McpTools.tool("ui_swipe", "Смахнуть от (x1,y1) к (x2,y2) за ms миллисекунд.",
            hostArg(McpTools.Schema.obj().number("x1", "Начало X", true).number("y1", "Начало Y", true).number("x2", "Конец X", true).number("y2", "Конец Y", true).integer("ms", "Длительность (по умолчанию 300)", false)), (a, c) ->
            with(live, headless, a, h -> {
                h.swipe((float) a.optDouble("x1"), (float) a.optDouble("y1"), (float) a.optDouble("x2"), (float) a.optDouble("y2"), Tl.clamp(Tl.intArg(a, "ms", 300), 50, 5000));
                return McpResult.json(J.put(new JSONObject(), "swiped", true));
            })));

        r.register(McpTools.tool("ui_type", "Ввести текст в поле (по локатору или в поле с фокусом); replace=true заменяет содержимое.",
            hostArg(McpTools.Schema.obj().str("text", "Текст для ввода", true).object("selector", "Поле: {text,desc,res,tag,class,path,index}; без него — поле с фокусом", false)
                .bool("replace", "Заменить содержимое (по умолчанию дописать)", false)), (a, c) ->
            with(live, headless, a, h -> McpResult.json(h.type(J.str(a, "text"), UiHost.Selector.from(a.optJSONObject("selector")), Tl.boolArg(a, "replace", false))))));

        r.register(McpTools.tool("ui_key", "Нажать клавишу: код KeyEvent или имя (BACK, ENTER, DEL, TAB, SEARCH, ...).",
            hostArg(McpTools.Schema.obj().any("code", "Код числом или имя", true)), (a, c) -> with(live, headless, a, h -> {
                Object code = a.opt("code");
                int kc = code instanceof Number ? ((Number) code).intValue() : KeyEvent.keyCodeFromString("KEYCODE_" + String.valueOf(code).toUpperCase(java.util.Locale.ROOT).replace("KEYCODE_", ""));
                if (kc == KeyEvent.KEYCODE_UNKNOWN) return Tl.err("INVALID_ARGS", "Неизвестная клавиша.", String.valueOf(code));
                h.key(kc);
                return McpResult.json(J.put(new JSONObject(), "key", kc));
            })));

        r.register(McpTools.tool("ui_wait", "Дождаться появления вида по локатору.", hostArg(sel(McpTools.Schema.obj().integer("timeoutMs", "Сколько ждать (по умолчанию 5000, до 60000)", false))), (a, c) ->
            with(live, headless, a, h -> {
                UiHost.Selector s = selector(a);
                if (s.empty()) return Tl.err("INVALID_ARGS", "Нужен локатор.", "selector");
                long t0 = System.currentTimeMillis();
                JSONObject hit = h.waitFor(s, Tl.clamp(Tl.intArg(a, "timeoutMs", 5000), 100, 60000));
                if (hit == null) return Tl.err("TIMEOUT", "Элемент не появился: " + s, "waited " + (System.currentTimeMillis() - t0) + " ms");
                J.put(hit, "waitedMs", System.currentTimeMillis() - t0);
                return McpResult.json(hit);
            })).timeout(65_000).readOnly());

        r.register(McpTools.tool("ui_anim_probe", "Значения свойства вида покадрово за период (для проверки анимаций): alpha, translationX/Y, scaleX/Y, rotation, x, y, width, height.",
            hostArg(sel(McpTools.Schema.obj().oneOf("property", "Свойство", true, "alpha", "translationX", "translationY", "scaleX", "scaleY", "rotation", "x", "y", "width", "height")
                .integer("durationMs", "Сколько снимать (по умолчанию 800, до 10000)", false))), (a, c) -> with(live, headless, a, h -> {
                UiHost.Selector s = selector(a);
                if (s.empty()) return Tl.err("INVALID_ARGS", "Нужен локатор.", "selector");
                return McpResult.json(h.animProbe(s, J.str(a, "property"), Tl.clamp(Tl.intArg(a, "durationMs", 800), 100, 10000)));
            })).timeout(20_000));

        r.register(McpTools.tool("ui_theme", "Тема интерфейса: dark, light или system (без аргумента — текущая).", McpTools.Schema.obj().oneOf("mode", "Тема", false, "dark", "light", "system"), (a, c) -> {
            if (a.has("mode")) {
                j.settings.set(Settings.UI_THEME, J.str(a, "mode"));
                final android.app.Activity act = LiveUi.activity();
                if (act instanceof app.jarvis.ui.Host) act.runOnUiThread(new Runnable() { @Override public void run() { ((app.jarvis.ui.Host) act).applyTheme(); } });
            }
            return McpResult.json(J.put(new JSONObject(), "theme", j.settings.get(Settings.UI_THEME, "dark")));
        }));
    }
}
