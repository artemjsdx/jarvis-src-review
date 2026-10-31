package app.jarvis.core.skills;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.jarvis.core.log.Journal;
import app.jarvis.core.skills.SkillStore.Op;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public class SkillStoreTest {
    static final String LIGHTS = "---\nname: room-lights\n"
        + "description: \"Используй, когда пользователь просит включить или выключить свет в комнате. Работает через ADB-команды.\"\n"
        + "version: 1.0.0\ntags: [adb, home]\nrequires_tools: [adb_shell]\n---\n\n"
        + "## Когда применять\nСвет в комнате.\n\n## Шаги\n1. adb_shell: `input keyevent 224`\n2. Проверить.\n";

    private static String rep(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    private static Op create(String name, String md) {
        Op o = new Op();
        o.action = "create"; o.name = name; o.content = md;
        return o;
    }

    private static Op patch(String name, String oldS, String newS, boolean all) {
        Op o = new Op();
        o.action = "patch"; o.name = name; o.oldString = oldS; o.newString = newS; o.replaceAll = all;
        return o;
    }

    private static Op file(String action, String name, String path, String content) {
        Op o = new Op();
        o.action = action; o.name = name; o.filePath = path; o.fileContent = content;
        return o;
    }

    private static Op simple(String action, String name) {
        Op o = new Op();
        o.action = action; o.name = name;
        return o;
    }

    private static File tmp() throws IOException { return Files.createTempDirectory("jv-skills").toFile(); }

    private static SkillStore store(File root) { return new SkillStore(new File(root, "skills"), Journal.NOP); }

    private static void expectFail(SkillStore s, List<Op> ops, String part) throws IOException {
        try {
            s.apply(ops);
            fail("ожидалась ошибка с «" + part + "»");
        } catch (SkillException e) {
            assertTrue("«" + e.getMessage() + "» должно содержать «" + part + "»", e.getMessage().contains(part));
        }
    }

    @Test public void parsesFrontMatterAndBody() throws Exception {
        SkillDoc d = SkillFormat.parse(LIGHTS, "room-lights");
        assertEquals("room-lights", d.name);
        assertEquals("1.0.0", d.version);
        assertEquals(Arrays.asList("adb", "home"), d.tags);
        assertEquals(Arrays.asList("adb_shell"), d.requiresTools);
        assertTrue(d.body.startsWith("## Когда применять"));
        SkillDoc crlf = SkillFormat.parse(LIGHTS.replace("\n", "\r\n"), "room-lights");
        assertEquals(d.body, crlf.body);
        SkillDoc block = SkillFormat.parse("---\nname: x-y\ndescription: Use when the user asks. Do it.\ntags:\n  - a\n  - \"b c\"\nmetadata:\n  hermes:\n    k: v\n---\nТекст\n", "x-y");
        assertEquals(Arrays.asList("a", "b c"), block.tags);
        assertEquals("Текст", block.body);
    }

    @Test public void rejectsBadFormatNamesAndDescriptions() {
        String[][] cases = {
            {"нет шапки", "начинаться"},
            {"name: x-y\n", "---"},
            {"---\nname: other\ndescription: Use when a. b.\n---\nT", "не совпадает"},
            {"---\nname: x-y\ndescription: Просто описание без триггера.\n---\nT", "Используй, когда"},
            {"---\nname: x-y\ndescription: Use when " + rep("a", 300) + ".\n---\nT", "длиннее"},
            {"---\nname: x-y\ndescription: Use when a.\n---\n", "нет инструкции"},
            {"---\nname: X_Y\ndescription: Use when a.\n---\nT", "X_Y"},
            {"---\nname: x-y\ndescription: Use when a.\nнепонятно\n---\nT", "Непонятная"},
        };
        for (String[] c : cases) {
            try {
                SkillFormat.parse(c[0], "x-y");
                fail("должно быть отвергнуто: " + c[0]);
            } catch (SkillException e) {
                assertTrue("«" + e.getMessage() + "» должно содержать «" + c[1] + "»", e.getMessage().contains(c[1]));
            }
        }
        try {
            SkillFormat.parse("---\nname: X_Y\ndescription: Use when a.\n---\nT", "X_Y");
            fail();
        } catch (SkillException e) {
            assertTrue(e.getMessage().contains("X_Y"));
        }
    }

    @Test public void createListViewAndIndexText() throws Exception {
        SkillStore s = store(tmp());
        s.apply(Arrays.asList(create("room-lights", LIGHTS)));
        assertEquals(1, s.listForUi().size());
        assertTrue(s.read("room-lights", "").contains("input keyevent 224"));
        String block = s.systemBlock(1200);
        assertTrue(block.startsWith("НАВЫКИ:\n"));
        assertTrue(block.contains("- room-lights — Используй, когда пользователь просит включить"));
        assertTrue(block.contains("<available_skills>") && block.endsWith("</available_skills>"));
        assertTrue(block.length() <= 1200);
        assertEquals("", store(tmp()).systemBlock(1200));
    }

    @Test public void indexIsBoundedWithManySkills() throws Exception {
        SkillStore s = store(tmp());
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            String n = "skill-" + i;
            ops.add(create(n, "---\nname: " + n + "\ndescription: \"Используй, когда нужна проверка номер " + i + ". " + rep("Описание ", 25) + "\"\n---\nТело " + i + "\n"));
        }
        s.apply(ops);
        String block = s.systemBlock(1200);
        assertTrue(block.length() <= 1200 + 80);
        assertTrue(block.contains("…ещё"));
        assertTrue(block.contains("skills_list"));
    }

    @Test public void patchReplacesOneUniqueOrAllOccurrences() throws Exception {
        SkillStore s = store(tmp());
        s.apply(Arrays.asList(create("room-lights", LIGHTS)));
        s.apply(Arrays.asList(patch("room-lights", "input keyevent 224", "input keyevent 223", false)));
        assertTrue(s.read("room-lights", "").contains("keyevent 223"));
        expectFail(s, Arrays.asList(patch("room-lights", "нет такого", "x", false)), "не найден");
        expectFail(s, Arrays.asList(patch("room-lights", "adb_shell", "x", false)), "найден 2 раз");
        s.apply(Arrays.asList(patch("room-lights", "1.", "I.", true)));
        assertTrue(s.read("room-lights", "").contains("I. adb_shell"));
    }

    @Test public void batchIsAtomicAndRollsBackEverything() throws Exception {
        File root = tmp();
        SkillStore s = store(root);
        s.apply(Arrays.asList(create("room-lights", LIGHTS)));
        List<Op> bad = Arrays.asList(
            create("morning", "---\nname: morning\ndescription: Use when the user asks for a summary. Gives it.\n---\nСводка\n"),
            patch("room-lights", "input keyevent 224", "changed", false),
            patch("missing-skill", "a", "b", false));
        try {
            s.apply(bad);
            fail();
        } catch (SkillException e) {
            assertEquals(3, e.op);
        }
        assertFalse(new File(root, "skills/morning").exists());
        assertTrue(s.read("room-lights", "").contains("input keyevent 224"));
        assertFalse(new File(root, "skills/.tx").exists());
    }

    @Test public void deleteIsSoleOperationAndRemovesDirectory() throws Exception {
        File root = tmp();
        SkillStore s = store(root);
        s.apply(Arrays.asList(create("room-lights", LIGHTS)));
        expectFail(s, Arrays.asList(simple("delete", "room-lights"), create("room-lights", LIGHTS)), "единственной");
        assertTrue(s.read("room-lights", "").contains("input keyevent 224"));
        s.apply(Arrays.asList(simple("delete", "room-lights")));
        assertFalse(new File(root, "skills/room-lights").exists());
        expectFail(s, Arrays.asList(create("room-lights", LIGHTS), create("room-lights", LIGHTS)), "уже существует");
    }

    @Test public void supportingFilesOnlyInAllowedFolders() throws Exception {
        SkillStore s = store(tmp());
        s.apply(Arrays.asList(create("room-lights", LIGHTS), file("write_file", "room-lights", "references/api.md", "# API\n")));
        assertEquals(Arrays.asList("references/api.md"), s.supportingFiles("room-lights"));
        assertEquals("# API\n", s.read("room-lights", "references/api.md"));
        String[] bad = {"../evil.md", "references/../../evil.md", "/etc/passwd", "evil.md", "other/a.md", "references/.hidden",
            "references/a b.md", "references/sub/../../x", "SKILL.md/x"};
        for (String p : bad) {
            try {
                s.apply(Arrays.asList(file("write_file", "room-lights", p, "x")));
                fail("путь должен быть отвергнут: " + p);
            } catch (SkillException e) {
                assertTrue(p + ": " + e.getMessage(), e.getMessage().contains("Файл навыка") || e.getMessage().contains("Недопустимое"));
            }
        }
        s.apply(Arrays.asList(file("remove_file", "room-lights", "references/api.md", null)));
        assertTrue(s.supportingFiles("room-lights").isEmpty());
    }

    @Test public void sizeLimitsAreEnforced() throws Exception {
        SkillStore s = store(tmp());
        expectFail(s, Arrays.asList(create("room-lights", LIGHTS + rep("x", SkillStore.MAX_SKILL_MD))), "больше 20 КБ");
        s.apply(Arrays.asList(create("room-lights", LIGHTS)));
        expectFail(s, Arrays.asList(file("write_file", "room-lights", "assets/big.bin", rep("x", SkillStore.MAX_FILE + 1))), "больше 64 КБ");
        for (int i = 0; i < 3; i++) {
            s.apply(Arrays.asList(file("write_file", "room-lights", "assets/f" + i + ".txt", rep("y", 60 * 1024))));
        }
        expectFail(s, Arrays.asList(file("write_file", "room-lights", "assets/f9.txt", rep("y", 60 * 1024))), "больше 200 КБ");
    }

    @Test public void countLimitIsEnforced() throws Exception {
        SkillStore s = store(tmp());
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < SkillStore.MAX_SKILLS; i++) ops.add(create("s" + i, "---\nname: s" + i + "\ndescription: Use when x. y.\n---\nT\n"));
        s.apply(ops);
        expectFail(s, Arrays.asList(create("extra", "---\nname: extra\ndescription: Use when x. y.\n---\nT\n")), "не больше 100");
        assertEquals(SkillStore.MAX_SKILLS, s.listForUi().size());
    }

    @Test public void interruptedCommitIsRolledBackOnStart() throws Exception {
        File root = tmp();
        SkillStore s = store(root);
        s.apply(Arrays.asList(create("room-lights", LIGHTS)));
        File tx = new File(root, "skills/.tx");
        assertTrue(tx.mkdirs());
        File live = new File(root, "skills/room-lights");
        assertTrue(live.renameTo(new File(tx, "room-lights.old")));
        assertTrue(live.mkdirs());
        Files.write(new File(live, "SKILL.md").toPath(), "мусор после сбоя".getBytes(StandardCharsets.UTF_8));
        SkillStore restarted = store(root);
        assertFalse(tx.exists());
        assertTrue(restarted.read("room-lights", "").contains("input keyevent 224"));
    }

    @Test public void zipRoundTripAndHostileArchives() throws Exception {
        File root = tmp();
        SkillStore s = store(root);
        s.apply(Arrays.asList(create("room-lights", LIGHTS), file("write_file", "room-lights", "scripts/lights.sh", "#!/bin/sh\necho ok\n")));
        File zip = new File(root, "room-lights.zip");
        s.exportZip("room-lights", zip);
        SkillStore other = store(tmp());
        other.importZip(zip);
        assertEquals(Arrays.asList("scripts/lights.sh"), other.supportingFiles("room-lights"));
        assertTrue(other.read("room-lights", "").contains("input keyevent 224"));
        try {
            other.importZip(zip);
            fail("повторный импорт не должен перезаписать навык");
        } catch (SkillException expected) {
            assertTrue(expected.getMessage().contains("уже существует"));
        }
        File evil = new File(root, "evil.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(evil))) {
            z.putNextEntry(new ZipEntry("room-lights/../../escaped.txt"));
            z.write("pwn".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("room-lights/SKILL.md"));
            z.write(LIGHTS.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        try {
            store(tmp()).importZip(evil);
            fail("zip-slip должен быть отвергнут");
        } catch (IOException expected) {
            assertFalse(new File(root, "escaped.txt").exists());
        }
    }

    @Test public void seedInstallsOnceAndNeverResurrectsDeleted() throws Exception {
        File root = tmp();
        SkillStore s = store(root);
        assertTrue(s.installSeed("room-lights", LIGHTS));
        assertFalse(s.installSeed("room-lights", LIGHTS));
        s.apply(Arrays.asList(patch("room-lights", "## Шаги", "## Шаги (мои)", false)));
        assertTrue(store(root).read("room-lights", "").contains("## Шаги (мои)"));
        s.apply(Arrays.asList(simple("delete", "room-lights")));
        assertFalse(s.installSeed("room-lights", LIGHTS));
        assertFalse(new File(root, "skills/room-lights").exists());
        SkillStore mine = store(tmp());
        mine.apply(Arrays.asList(create("room-lights", LIGHTS.replace("input keyevent 224", "mine"))));
        assertFalse(mine.installSeed("room-lights", LIGHTS));
        assertTrue(mine.read("room-lights", "").contains("mine"));
    }
}
