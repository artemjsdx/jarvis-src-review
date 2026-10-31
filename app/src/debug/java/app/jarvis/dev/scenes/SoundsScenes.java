package app.jarvis.dev.scenes;

import android.widget.ScrollView;
import app.jarvis.core.audio.CueSettings;
import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.Shell;
import app.jarvis.ui.Ui;
import app.jarvis.ui.screens.SettingsScreen;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * The "Звук и микрофон" page in both themes (top, middle, bottom), its row in the settings list, and a state where the user
 * picked own files and different sounds for start and end.
 */
public final class SoundsScenes {
    private SoundsScenes() { }

    public static void render() throws Exception {
        Jarvis j = PreviewKit.jarvis();
        for (boolean dark : new boolean[]{true, false}) {
            String tag = dark ? "dark" : "light";
            Ui.setDark(dark);
            Shell s = shellFor("settings/sounds");
            PreviewHost.render(s, "snd_sounds_top_" + tag);
            ScrollView sv = (ScrollView) ((SettingsScreen) s.current()).page().view();
            sv.scrollTo(0, sv.getChildAt(0).getHeight() / 2);
            PreviewHost.pump(250);
            PreviewHost.render(s, "snd_sounds_mid_" + tag);
            sv.scrollTo(0, sv.getChildAt(0).getHeight());
            PreviewHost.pump(250);
            PreviewHost.render(s, "snd_sounds_bottom_" + tag);
        }
        Ui.setDark(true);

        Shell list = shellFor("settings");
        PreviewHost.render(list, "snd_list_dark");

        File dir = new File(PreviewHost.filesDir, "cues");
        dir.mkdirs();
        try (InputStream in = PreviewHost.ctx.getAssets().open("sounds/bell_stop.ogg");
             FileOutputStream out = new FileOutputStream(new File(dir, "custom-start.ogg"))) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        j.settings.set(CueSettings.CUSTOM_START, "custom-start.ogg");
        j.settings.set(CueSettings.STOP_SET, "water");
        Shell custom = shellFor("settings/sounds");
        ScrollView sv = (ScrollView) ((SettingsScreen) custom.current()).page().view();
        PreviewHost.render(custom, "snd_sounds_custom_pre");
        sv.scrollTo(0, sv.getChildAt(0).getHeight() * 2 / 5);
        PreviewHost.pump(250);
        PreviewHost.render(custom, "snd_sounds_custom_dark");
        j.settings.set(CueSettings.CUSTOM_START, "");
        j.settings.set(CueSettings.STOP_SET, "");
        new File(dir, "custom-start.ogg").delete();
    }

    private static Shell shellFor(String target) throws Exception {
        Shell s = new Shell(PreviewKit.host());
        s.setInsets(PreviewHost.px(30), PreviewHost.px(24), 0);
        s.navigate(target);
        PreviewHost.pump(700);
        return s;
    }
}
