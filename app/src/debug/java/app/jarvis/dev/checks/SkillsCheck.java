package app.jarvis.dev.checks;

import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.log.Journal;
import app.jarvis.core.skills.SkillStore;
import app.jarvis.core.skills.SkillTools;
import app.jarvis.core.tools.MemoryStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real-runtime check of the skill store, skill tools and memory budget on ART. Not shipped. */
public final class SkillsCheck {
    static int fails;

    static void check(boolean ok, String what) { System.out.println((ok ? "  ok   " : "  FAIL ") + what); if (!ok) fails++; System.out.flush(); }

    static final String MD = "---\nname: room-lights\ndescription: \"Используй, когда пользователь просит включить свет. Работает через ADB.\"\n---\n\n## Шаги\n1. adb_shell\n";

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { System.out.println("CRASH " + t); t.printStackTrace(System.out); fails++; }
        System.out.println(fails == 0 ? "SKILLSCHECK OK" : "SKILLSCHECK FAILURES: " + fails);
    }

    static void run() throws Exception {
        String tmp = System.getenv("TMPDIR");
        File base = Files.createTempDirectory(new File(tmp != null ? tmp : "/data/local/tmp").toPath(), "skchk").toFile();
        SkillStore s = new SkillStore(new File(base, "skills"), Journal.NOP);
        SkillStore.Op create = new SkillStore.Op();
        create.action = "create"; create.name = "room-lights"; create.content = MD;
        s.apply(Arrays.asList(create));
        check(s.listForUi().size() == 1, "create + list");
        check(s.systemBlock(1200).contains("- room-lights — Используй, когда пользователь просит включить свет."), "index text");

        SkillStore.Op bad = new SkillStore.Op();
        bad.action = "patch"; bad.name = "room-lights"; bad.oldString = "adb_shell"; bad.newString = "X";
        SkillStore.Op missing = new SkillStore.Op();
        missing.action = "patch"; missing.name = "нет-такого"; missing.oldString = "a"; missing.newString = "b";
        try {
            s.apply(Arrays.asList(bad, missing));
            check(false, "batch with a failing op must throw");
        } catch (app.jarvis.core.skills.SkillException e) {
            check(e.op == 2, "failing op index reported: " + e.getMessage());
        }
        check(s.read("room-lights", "").contains("1. adb_shell"), "batch rolled back on device fs");
        check(!new File(base, "skills/.tx").exists(), "staging dir removed after rollback");

        ToolRegistry reg = new ToolRegistry();
        SkillTools.register(reg, s);
        JSONObject args = new JSONObject().put("operations", new JSONArray().put(new JSONObject()
            .put("action", "patch").put("name", "room-lights").put("old_string", "1. adb_shell").put("new_string", "1. adb_shell: input keyevent 224")));
        ToolResult r = reg.get("skill_manage").run(args, null);
        check(r.ok && s.read("room-lights", "").contains("keyevent 224"), "skill_manage patch through the tool");
        ToolResult v = reg.get("skill_view").run(new JSONObject().put("name", "room-lights"), null);
        check(v.ok && v.data.getString("content").contains("keyevent 224"), "skill_view returns the patched text");

        File zip = new File(base, "room-lights.zip");
        s.exportZip("room-lights", zip);
        SkillStore other = new SkillStore(new File(base, "other/skills"), Journal.NOP);
        other.importZip(zip);
        check(other.listForUi().size() == 1, "zip export/import round trip on device");

        MemoryStore m = new MemoryStore(new File(base, "memory.json"));
        m.setBudget(() -> 120);
        m.put("имя", "Даня");
        m.put("точки", "меньше точек в ответах", MemoryStore.STYLE);
        try {
            m.put("длинно", new String(new char[80]).replace('\0', 'я'));
            check(false, "memory budget must refuse");
        } catch (MemoryStore.FullException e) {
            check(e.limit == 120 && e.used == m.used(), "memory budget refusal carries usage: used=" + e.used + " limit=" + e.limit);
        }
        MemoryStore.PromptParts p = m.promptParts(120);
        check(p.style.equals("- точки: меньше точек в ответах") && p.facts.equals("- имя: Даня"), "prompt split facts/style");

        List<String> files = new ArrayList<>(s.supportingFiles("room-lights"));
        check(files.isEmpty(), "no supporting files by default");
        System.out.println("  bytes on device: " + new File(base, "skills/room-lights/SKILL.md").length()
            + " B; text: " + new String(Files.readAllBytes(new File(base, "skills/room-lights/SKILL.md").toPath()), StandardCharsets.UTF_8).length() + " chars");
    }
}
