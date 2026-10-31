package app.jarvis.dev.scenes;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.HelpPopup;
import app.jarvis.ui.Shell;
import app.jarvis.ui.screens.SettingsScreen;
import app.jarvis.ui.settings.SandboxPage;
import app.jarvis.ui.settings.SettingsPage;

/** Voice tab cards, settings list with "?" rows, voice/wake/ADB pages, memory list and full-text sheet. */
public final class VoiceSetScenes {
    private VoiceSetScenes() { }

    public static void render() throws Exception {
        Shell shell = PreviewKit.shell("studio");
        Jarvis j = PreviewKit.jarvis();
        PreviewHost.pump(200);
        PreviewHost.render(shell, "v_studio_top");
        ScrollView sv = firstScroll(shell);
        if (sv != null) {
            sv.scrollTo(0, sv.getChildAt(0).getHeight());
            PreviewHost.pump(150);
            PreviewHost.render(shell, "v_studio_bottom");
        }

        shell.navigate("settings");
        PreviewHost.pump(200);
        PreviewHost.render(shell, "set_list");

        shell.navigate("settings/voice");
        PreviewHost.pump(200);
        PreviewHost.render(shell, "set_voice");

        shell.navigate("settings/wake");
        PreviewHost.pump(200);
        PreviewHost.render(shell, "set_wake");

        shell.navigate("settings/adb");
        PreviewHost.pump(200);
        PreviewHost.render(shell, "set_adb");

        j.memory.put("стиль ответов", "Пользователь просит использовать меньше точек в ответах и короткие фразы без списков.");
        j.memory.put("любимый напиток", "Чёрный чай без сахара, по утрам зелёный.");
        j.memory.put("длинная заметка", "Очень длинная запись памяти, которая должна переноситься по словам, а не рваться посреди слова; "
            + "проверяем, что русские слова переносятся аккуратно и что полный текст открывается по нажатию, с правкой и копированием.");
        shell.navigate("settings");
        shell.navigate("settings/sandbox");
        PreviewHost.pump(300);
        ScrollView msv = firstScroll(((SettingsScreen) shell.current()).page().view());
        if (msv != null) {
            msv.scrollTo(0, 4000);
            PreviewHost.pump(150);
        }
        PreviewHost.render(shell, "set_memory");
        View row = findTag(shell, "mem.длинная заметка");
        if (row != null) {
            row.performClick();
            PreviewHost.pump(400);
            PreviewHost.render(shell, "set_memory_full");
        }

        HelpPopup.show(PreviewKit.host(), "Текст проверки", "Фраза синтезируется выбранным провайдером напрямую.",
            "Варианты ниже подставляются в поле; можно ввести свой текст.", "«Озвучить» — один синтез.");
        PreviewHost.pump(400);
        PreviewHost.render(shell, "help_sheet");
    }

    static ScrollView firstScroll(View v) {
        if (v instanceof ScrollView) return (ScrollView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ScrollView r = firstScroll(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    static View findTag(View v, Object tag) {
        if (tag.equals(v.getTag())) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findTag(g.getChildAt(i), tag);
                if (r != null) return r;
            }
        }
        return null;
    }
}
