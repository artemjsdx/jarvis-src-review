package app.jarvis.dev.scenes;

import android.widget.ScrollView;
import app.jarvis.data.Settings;
import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Shell;
import app.jarvis.ui.screens.SettingsScreen;
import app.jarvis.ui.settings.DiagnosticsPage;
import app.jarvis.ui.settings.LlmPage;
import app.jarvis.ui.settings.MusicPage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Settings screen previews: the section list, every page, the genuine geo-block failure of the connection check,
 * an optional real-success check (JV_OR_KEY env with an OpenRouter key), the memory dialog, and live diagnostics.
 */
public final class SettingsScenes {
    private static final String RES = "/data/data/com.termux/files/home/jarvis/app/src/test/resources";

    public static void render() throws Exception {
        Shell shell = PreviewKit.shell("settings");
        Jarvis j = PreviewKit.jarvis();
        PreviewHost.pump(150);
        PreviewHost.render(shell, "s_list");

        // placeholders owned by the voice-ui / adb agents
        shell.navigate("settings/voice");
        PreviewHost.pump(120);
        PreviewHost.render(shell, "s_voice");

        // --- LLM page: top, generation params, fallbacks
        shell.navigate("settings/llm");
        PreviewHost.pump(150);
        PreviewHost.render(shell, "s_llm");
        ScrollView page = (ScrollView) ((SettingsScreen) shell.current()).page().view();
        page.scrollTo(0, 20000);
        PreviewHost.pump(250);
        PreviewHost.render(shell, "s_llm_bottom");

        // --- genuine failure: Inception is geo-blocked from this bench (CloudFront 403)
        j.secrets.set("key.inception", "sk-inception-bogus-key-000");
        LlmPage llm = (LlmPage) ((SettingsScreen) shell.current()).page();
        llm.runConnectionCheck();
        PreviewHost.pump(15000);
        PreviewHost.render(shell, "s_llm_geo");

        // --- real success with OpenRouter (only when a key is provided through the environment)
        String orKey = System.getenv("JV_OR_KEY");
        if (orKey != null && !orKey.trim().isEmpty()) {
            j.secrets.set("key.openrouter", orKey.trim());
            j.settings.set(Settings.LLM_PROVIDER, "openrouter");
            shell.navigate("settings");
            shell.navigate("settings/llm");
            PreviewHost.pump(150);
            LlmPage llm2 = (LlmPage) ((SettingsScreen) shell.current()).page();
            llm2.runConnectionCheck();
            PreviewHost.pump(25000);
            PreviewHost.render(shell, "s_llm_ok");
        }

        // --- music page with real audio files in the sandbox
        File music = j.sandbox.music;
        copy(RES + "/speech48st.mp3", new File(music, "morning-sun.mp3"));
        copy(RES + "/speech22k.mp3", new File(music, "svetlana-hello.mp3"));
        copy(RES + "/speech48st.mp3", new File(music, "lofi-night.mp3"));
        j.settings.set(Settings.MUSIC_FAVORITE, "music/morning-sun.mp3");
        j.settings.setBool(Settings.MUSIC_MORNING_GREETING, true);
        shell.navigate("settings");
        shell.navigate("settings/music");
        PreviewHost.pump(600);
        PreviewHost.render(shell, "s_music");
        MusicPage mp = (MusicPage) ((SettingsScreen) shell.current()).page();
        mp.runMorningCheck();
        PreviewHost.pump(15000);
        PreviewHost.render(shell, "s_music_check");

        // --- sandbox & tools with memory entries
        j.memory.put("любимый напиток", "чай с бергамотом, без сахара");
        j.memory.put("как обращаться", "Даня");
        j.memory.put("рабочий язык", "Java 8, сборка без Gradle");
        j.memory.put("утро", "не звонить и не включать музыку до 9:00");
        j.memory.put("проект", "Jarvis: ассистент на телефоне, Realme Note 50");
        shell.navigate("settings");
        shell.navigate("settings/sandbox");
        PreviewHost.pump(1200);
        PreviewHost.render(shell, "s_sandbox");
        ScrollView sb = (ScrollView) ((SettingsScreen) shell.current()).page().view();
        sb.scrollTo(0, 20000);
        PreviewHost.pump(250);
        PreviewHost.render(shell, "s_sandbox_memory");

        // --- confirm dialog over the memory list
        Dialogs.confirm(PreviewKit.host(), "Очистить память?", "Ассистент забудет все записи: предпочтения, привычки, факты о вас.", "Очистить", true, null);
        PreviewHost.pump(350);
        PreviewHost.render(shell, "s_dialog_confirm");

        // --- background, appearance, advanced
        shell.navigate("settings");
        shell.navigate("settings/background");
        PreviewHost.pump(150);
        PreviewHost.render(shell, "s_background");

        shell.navigate("settings");
        shell.navigate("settings/appearance");
        PreviewHost.pump(150);
        PreviewHost.render(shell, "s_appearance");

        shell.navigate("settings");
        shell.navigate("settings/advanced");
        PreviewHost.pump(150);
        PreviewHost.render(shell, "s_advanced");

        // --- diagnostics: idle, then real checks (network + keystore + audio)
        shell.navigate("settings");
        shell.navigate("settings/diagnostics");
        PreviewHost.pump(150);
        PreviewHost.render(shell, "s_diag");
        DiagnosticsPage diag = (DiagnosticsPage) ((SettingsScreen) shell.current()).page();
        diag.runAll();
        PreviewHost.pump(30000);
        PreviewHost.render(shell, "s_diag_done");
    }

    private static void copy(String src, File dst) {
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } catch (Exception e) {
            System.out.println("[scene] copy failed: " + e);
        }
    }
}
