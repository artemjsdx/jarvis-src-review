package app.jarvis.core.skills;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/** The skills shipped in app/assets/skills must pass the same parser the agent's skill_manage uses. */
public class SeedSkillsTest {
    @Test public void everyBundledSkillParses() throws Exception {
        File dir = new File("app/assets/skills");
        assumeTrue(dir.isDirectory());
        File[] seeds = dir.listFiles();
        assertTrue(seeds != null && seeds.length >= 4);
        int n = 0;
        for (File d : seeds) {
            if (!d.isDirectory()) continue;
            String text = new String(Files.readAllBytes(new File(d, "SKILL.md").toPath()), StandardCharsets.UTF_8);
            SkillDoc doc = SkillFormat.parse(text, d.getName());
            assertTrue(doc.description, doc.description.length() <= SkillFormat.MAX_DESCRIPTION);
            assertTrue(doc.body.contains("## Шаги"));
            n++;
        }
        assertTrue("seed skills: " + n, n >= 4);
        for (String must : new String[]{"termux-environment", "web-research", "adb-wifi-pairing", "writing-skills"})
            assertTrue(must + " is bundled", new File(dir, must + "/SKILL.md").isFile());
    }
}
