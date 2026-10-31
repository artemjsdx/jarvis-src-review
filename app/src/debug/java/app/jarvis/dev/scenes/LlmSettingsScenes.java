package app.jarvis.dev.scenes;

import android.widget.ScrollView;
import app.jarvis.core.agent.ContextFit;
import app.jarvis.core.llm.Cap;
import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.ModelCaps;
import app.jarvis.core.llm.ModelInfo;
import app.jarvis.core.llm.RecentModels;
import app.jarvis.data.ChatItem;
import app.jarvis.data.Settings;
import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.Host;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Shell;
import app.jarvis.ui.Ui;
import app.jarvis.ui.screens.SettingsScreen;
import app.jarvis.ui.settings.llm.ModelBrowser;
import app.jarvis.ui.settings.llm.ModelSwitch;
import app.jarvis.ui.settings.llm.ProviderSheet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * LLM settings with the capability model. The catalog is seeded with values taken from real answers (OpenRouter /models for
 * deepseek/deepseek-v4-flash and mercury-3, DeepSeek GET /models for deepseek-flash), plus one model the provider does not describe.
 */
public final class LlmSettingsScenes {
    private LlmSettingsScenes() { }

    public static void render() throws Exception {
        Jarvis j = PreviewKit.jarvis();
        seed(j);
        List<String> recent = new ArrayList<>();
        recent = RecentModels.push(recent, "deepseek", "deepseek-flash");
        recent = RecentModels.push(recent, "openrouter", "inception/mercury-3");
        recent = RecentModels.push(recent, "openrouter", "deepseek/deepseek-v4-flash");
        recent = RecentModels.push(recent, "openrouter", "particle-ai/particle-1");
        j.settings.setList(Settings.LLM_RECENT, recent);

        Ui.init(PreviewHost.ctx, true);
        j.settings.set(Settings.LLM_PROVIDER, "openrouter");
        j.setModel("openrouter", "deepseek/deepseek-v4-flash");
        Shell s = PreviewKit.shell("settings");
        s.navigate("settings/llm");
        PreviewHost.pump(250);
        PreviewHost.render(s, "llm_or_dark");
        ScrollView sv = (ScrollView) ((SettingsScreen) s.current()).page().view();
        sv.scrollTo(0, 2600);
        PreviewHost.pump(200);
        PreviewHost.render(s, "llm_or_gen_dark");

        Ui.init(PreviewHost.ctx, false);
        s.navigate("settings");
        s.navigate("settings/llm");
        PreviewHost.pump(250);
        PreviewHost.render(s, "llm_or_light");

        Ui.init(PreviewHost.ctx, true);
        j.settings.set(Settings.LLM_PROVIDER, "deepseek");
        j.setModel("deepseek", "deepseek-flash");
        s.navigate("settings");
        s.navigate("settings/llm");
        PreviewHost.pump(250);
        PreviewHost.render(s, "llm_ds_dark");
        sv = (ScrollView) ((SettingsScreen) s.current()).page().view();
        sv.scrollTo(0, 2600);
        PreviewHost.pump(200);
        PreviewHost.render(s, "llm_ds_gen_dark");

        j.settings.set(Settings.LLM_PROVIDER, "openrouter");
        j.setModel("openrouter", "my-private-model");
        s.navigate("settings");
        s.navigate("settings/llm");
        PreviewHost.pump(250);
        PreviewHost.render(s, "llm_unknown_dark");

        j.setModel("openrouter", "deepseek/deepseek-v4-flash");
        s.navigate("settings");
        s.navigate("settings/llm");
        PreviewHost.pump(200);
        Host host = PreviewKit.host();
        PreviewHost.render(ProviderSheet.content(host, new ProviderSheet.Pick() { @Override public void on(String id) { } }, null), "llm_providers_dark", 392, 640);
        PreviewHost.render(ModelBrowser.content(host, "openrouter", null, new Runnable[1]), "llm_browser_dark", 392, 640);

        long previous = j.sessions.active();
        long conv = j.chat.newConversation("preview llm switch");
        j.sessions.select(conv);
        ChatItem big = new ChatItem();
        big.convId = conv;
        big.kind = ChatItem.Kind.USER;
        big.text = repeat("Расскажи подробно про сборку проекта и всё, что мы обсуждали. ", 500);
        j.chat.add(big);
        List<ChatMessage> hist = new ArrayList<>();
        hist.add(big.kind == ChatItem.Kind.USER ? ChatMessage.user(big.text) : ChatMessage.user(""));
        ContextFit.Result fit = ContextFit.check(hist, false, j.catalog.caps("openrouter", "tiny-window"), j.catalog.caps("openrouter", "deepseek/deepseek-v4-flash"));
        PreviewHost.render(ModelSwitch.questionView(host, "openrouter", "tiny-window", hist, fit, conv, null, new OverlayHost.Layer[1]), "llm_switch_body_dark", 392, 420);
        ModelSwitch.request(host, "openrouter", "tiny-window", null);
        PreviewHost.pump(1200);
        PreviewHost.render(s, "llm_switch_dark");
        host.overlays().dismissAll();
        PreviewHost.pump(100);
        j.sessions.select(previous);
        j.chat.deleteConversation(conv);
        j.setModel("openrouter", "deepseek/deepseek-v4-flash");
        j.settings.set(Settings.LLM_PROVIDER, "openrouter");
        keyStates(host, j);
    }

    /** Puts a view on the dark page colour, so the preview shows the text colours the real screen has. */
    private static android.view.View onDark(android.view.View v) {
        android.widget.FrameLayout f = new android.widget.FrameLayout(PreviewHost.ctx);
        f.setBackgroundColor(Ui.P.bg0);
        f.addView(v);
        return f;
    }

    /** The API-key block in its states: no key, saved (the store path the app uses), check passed, check refused. */
    private static void keyStates(Host host, Jarvis j) throws Exception {
        Ui.init(PreviewHost.ctx, true);
        app.jarvis.core.llm.ProviderPreset or = app.jarvis.core.llm.Presets.get("openrouter");
        j.secrets.remove("key.openrouter");
        j.settings.remove("key.openrouter.savedAt");
        PreviewHost.render(onDark(new app.jarvis.ui.settings.llm.KeyBlock(host, or, null).view()), "key_empty_dark", 392, 560);
        app.jarvis.ui.settings.llm.KeyBlock.store(j, "openrouter", "REDACTED");
        app.jarvis.ui.settings.llm.KeyBlock ok = new app.jarvis.ui.settings.llm.KeyBlock(host, or, null);
        ok.showCheck(app.jarvis.core.llm.KeyCheck.Result.passed(412, "/key"));
        PreviewHost.render(onDark(ok.view()), "key_ok_dark", 392, 640);
        app.jarvis.ui.settings.llm.KeyBlock wrong = new app.jarvis.ui.settings.llm.KeyBlock(host, or, null);
        wrong.showCheck(app.jarvis.core.llm.KeyCheck.Result.failed(new app.jarvis.core.llm.LlmException(
            app.jarvis.core.llm.LlmException.Kind.AUTH, 401, "Неверный, просроченный или отозванный API-ключ",
            "HTTP 401: Invalid API key [401]", 0, null, null), "/key"));
        PreviewHost.render(onDark(wrong.view()), "key_wrong_dark", 392, 640);
        app.jarvis.ui.settings.llm.KeyBlock.forget(j, "openrouter");
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    private static ModelCaps caps(String id, int ctx, int out, Cap tools, Cap temp, Cap reasoning, Cap vision, String source) {
        ModelCaps c = new ModelCaps(id);
        c.contextTokens = ctx;
        c.maxOutputTokens = out;
        c.tools = tools;
        c.temperature = temp;
        c.reasoning = reasoning;
        c.vision = vision;
        c.source = ModelCaps.Source.valueOf(source);
        return c;
    }

    private static void seed(Jarvis j) {
        List<ModelInfo> or = new ArrayList<>();
        ModelCaps flash = caps("deepseek/deepseek-v4-flash", 1_048_576, 943_717, Cap.YES, Cap.YES, Cap.YES, Cap.NO, "OPENROUTER");
        flash.priceInPerM = 0.091; flash.priceOutPerM = 0.182;
        or.add(new ModelInfo(flash.id, "DeepSeek: DeepSeek V4.1 Flash", "deepseek", flash));
        ModelCaps mercury = caps("inception/mercury-3", 1_000_000, 131_072, Cap.YES, Cap.YES, Cap.YES, Cap.NO, "OPENROUTER");
        mercury.priceInPerM = 0.1; mercury.priceOutPerM = 0.35;
        or.add(new ModelInfo(mercury.id, "Inception: Mercury 3", "inception", mercury));
        ModelCaps particle = caps("particle-ai/particle-1", 1_048_576, 131_072, Cap.YES, Cap.YES, Cap.YES, Cap.YES, "OPENROUTER");
        particle.priceInPerM = 0.25; particle.priceOutPerM = 1.0;
        or.add(new ModelInfo(particle.id, "Particle AI: Particle-1", "particle-ai", particle));
        or.add(new ModelInfo("tiny-window", "Маленькое окно (демо)", "demo", caps("tiny-window", 8_000, 1_000, Cap.YES, Cap.YES, Cap.NO, Cap.NO, "OPENROUTER")));
        or.add(new ModelInfo("my-private-model", null, "", ModelCaps.unknown("my-private-model")));
        j.catalog.seed("openrouter", or);

        List<ModelInfo> ds = new ArrayList<>();
        ModelCaps dsFlash = caps("deepseek-flash", 1_048_576, 393_216, Cap.UNKNOWN, Cap.UNKNOWN, Cap.YES, Cap.YES, "PROVIDER");
        dsFlash.reasoningLevels.addAll(Arrays.asList("low", "high", "max"));
        ds.add(new ModelInfo(dsFlash.id, "DeepSeek-V4.1-Flash", "deepseek", dsFlash));
        ModelCaps dsPro = caps("deepseek-v4-pro", 1_048_576, 393_216, Cap.UNKNOWN, Cap.UNKNOWN, Cap.YES, Cap.NO, "PROVIDER");
        dsPro.reasoningLevels.addAll(Arrays.asList("low", "high", "max"));
        ds.add(new ModelInfo(dsPro.id, "DeepSeek-V4-Pro", "deepseek", dsPro));
        j.catalog.seed("deepseek", ds);
    }
}
