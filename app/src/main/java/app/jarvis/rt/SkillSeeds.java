package app.jarvis.rt;

import android.content.res.AssetManager;
import app.jarvis.core.log.Journal;
import app.jarvis.core.skills.SkillStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Installs the skills bundled in assets/skills into the sandbox, once per name (see {@link SkillStore#installSeed}). */
final class SkillSeeds {
    private SkillSeeds() { }

    static void install(AssetManager am, SkillStore store, Journal journal) {
        String[] names;
        try {
            names = am.list("skills");
        } catch (IOException e) {
            journal.w(Journal.Cat.AGENT, "Встроенные навыки не читаются: " + e.getMessage());
            return;
        }
        if (names == null) return;
        for (String n : names) {
            try (InputStream in = am.open("skills/" + n + "/" + SkillStore.SKILL_MD)) {
                store.installSeed(n, text(in));
            } catch (IOException e) {
                journal.w(Journal.Cat.AGENT, "Встроенный навык «" + n + "» не установлен: " + e.getMessage());
            }
        }
    }

    private static String text(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
        return new String(bo.toByteArray(), "UTF-8");
    }
}
