package app.jarvis.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.agent.ToolResult;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class MemoryStoreTest {
    private static File newFile() throws IOException { return new File(Files.createTempDirectory("jv-mem").toFile(), "memory.json"); }

    @Test public void persistsAcrossInstancesAndSupportsUpdateDelete() throws Exception {
        File f = newFile();
        MemoryStore m = new MemoryStore(f);
        m.put("утренняя музыка", "спокойная");
        m.put("имя", "Папа");
        m.put("имя", "Папочка");
        MemoryStore again = new MemoryStore(f);
        assertEquals("спокойная", again.get("утренняя музыка"));
        assertEquals("Папочка", again.get("имя"));
        assertEquals(2, again.size());
        assertTrue(again.delete("имя"));
        assertNull(new MemoryStore(f).get("имя"));
        assertFalse(again.delete("нет такого"));
        assertFalse(new File(f.getParentFile(), "memory.json.tmp").exists());
    }

    @Test public void limitsAreEnforced() throws Exception {
        MemoryStore m = new MemoryStore(newFile());
        try { m.put("", "x"); fail(); } catch (IOException expected) { }
        try { m.put("k", new String(new char[MemoryStore.MAX_VALUE + 1]).replace('\0', 'x')); fail(); } catch (IOException expected) { }
    }

    @Test public void searchAndPromptParts() throws Exception {
        MemoryStore m = new MemoryStore(newFile());
        m.put("утро", "тихая музыка");
        Thread.sleep(5);
        m.put("вечер", "джаз");
        assertEquals(1, m.search("ДЖАЗ").size());
        String s = m.promptParts(1000).facts;
        assertTrue(s.startsWith("- вечер: джаз"));       // most recent first
        assertTrue(m.promptParts(20).facts.length() <= 20);
    }

    @Test public void budgetRefusesWritesThatWouldOverflow() throws Exception {
        MemoryStore m = new MemoryStore(newFile());
        m.setBudget(() -> 60);
        m.put("имя", "Даня");                             // "- имя: Даня\n" is 12 characters
        String big = new String(new char[40]).replace('\0', 'я');
        try {
            m.put("любимое", big);
            fail("запись сверх бюджета должна быть отклонена");
        } catch (MemoryStore.FullException e) {
            assertEquals(12, e.used);
            assertEquals(60, e.limit);
        }
        assertNull(m.get("любимое"));
        m.put("имя", "Даня Петрович");                    // an update that fits is still accepted
        assertTrue(m.used() <= 60);
        assertTrue(m.promptParts(1000).facts.length() <= m.used());
    }

    @Test public void budgetReadsSettingOnEveryWrite() throws Exception {
        MemoryStore m = new MemoryStore(newFile());
        final int[] limit = {30};
        m.setBudget(() -> limit[0]);
        m.put("а", "б");
        limit[0] = 10;
        try {
            m.put("в", "г");
            fail();
        } catch (MemoryStore.FullException expected) { }
    }

    @Test public void rankingPinnedFirstThenRecentAndBudgetOnPrompt() throws Exception {
        MemoryStore m = new MemoryStore(newFile());
        m.put("старое", "очень давняя запись");
        Thread.sleep(5);
        m.put("новое", "свежая");
        Thread.sleep(5);
        m.put("закреплено", "важно всегда");
        m.pin("закреплено", true);
        String all = m.promptParts(1000).facts;
        assertTrue(all.startsWith("- закреплено: важно всегда"));
        assertTrue(all.indexOf("новое") < all.indexOf("старое"));
        String small = m.promptParts("- закреплено: важно всегда\n".length()).facts;
        assertEquals("- закреплено: важно всегда", small);
        assertTrue(m.entry("закреплено").pinned);
        m.pin("закреплено", false);
        assertFalse(m.entry("закреплено").pinned);
    }

    @Test public void styleNotesAreSplitFromFacts() throws Exception {
        File f = newFile();
        MemoryStore m = new MemoryStore(f);
        m.put("точки", "меньше точек в ответах", MemoryStore.STYLE);
        m.put("любит чай", "Пользователь предпочитает чай", MemoryStore.PREFERENCE);
        MemoryStore.PromptParts p = m.promptParts(1000);
        assertEquals("- точки: меньше точек в ответах", p.style);
        assertEquals("- любит чай: Пользователь предпочитает чай", p.facts);
        assertEquals(MemoryStore.STYLE, new MemoryStore(f).entry("точки").kind);
    }

    @Test public void newRecordKindIsInferredFromText() throws Exception {
        MemoryStore m = new MemoryStore(newFile());
        m.put("ответы", "пользователь просит меньше точек в ответах");
        m.put("любимое", "чай с бергамотом");
        assertEquals(MemoryStore.STYLE, m.entry("ответы").kind);
        assertEquals(MemoryStore.FACT, m.entry("любимое").kind);
        m.put("ответы", "пользователь просит меньше точек");
        assertEquals(MemoryStore.STYLE, m.entry("ответы").kind);
    }

    @Test public void replaceKeepsKindAndPinButNeedsExistingKey() throws Exception {
        MemoryStore m = new MemoryStore(newFile());
        m.put("точки", "меньше точек", MemoryStore.STYLE);
        m.pin("точки", true);
        m.replace("точки", "без точек вообще");
        assertEquals(MemoryStore.STYLE, m.entry("точки").kind);
        assertTrue(m.entry("точки").pinned);
        assertEquals("без точек вообще", m.get("точки"));
        try {
            m.replace("новая", "x");
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("memory_save"));
        }
    }

    @Test public void migratesOldFormatOnLoad() throws Exception {
        File f = newFile();
        String old = "{\"entries\":["
            + "{\"key\":\"утро\",\"value\":\"тихая музыка\",\"updated\":1000},"
            + "{\"key\":\"ответы\",\"value\":\"пользователь просит меньше точек в ответах\",\"updated\":2000},"
            + "{\"key\":\"kind-bad\",\"value\":\"x\",\"updated\":3000,\"kind\":\"weird\"}]}";
        Files.write(f.toPath(), old.getBytes(StandardCharsets.UTF_8));
        MemoryStore m = new MemoryStore(f);
        assertEquals(MemoryStore.FACT, m.entry("утро").kind);
        assertEquals(MemoryStore.STYLE, m.entry("ответы").kind);
        assertEquals(MemoryStore.FACT, m.entry("kind-bad").kind);
        assertFalse(m.entry("утро").pinned);
        JSONObject written = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        assertEquals(2, written.getInt("version"));
        assertEquals(3, new MemoryStore(f).size());
        assertEquals("- ответы: пользователь просит меньше точек в ответах", new MemoryStore(f).promptParts(500).style);
    }

    @Test public void toolsReturnStructuredResultsAndBudgetError() throws Exception {
        MemoryStore m = new MemoryStore(newFile());
        m.setBudget(() -> 50);
        ToolRegistry reg = new ToolRegistry();
        MemoryStore.registerTools(reg, m);
        JSONObject args = new JSONObject().put("key", "имя").put("value", "Даня");
        ToolResult ok = reg.get("memory_save").run(args, null);
        assertTrue(ok.ok);
        JSONObject overflow = new JSONObject().put("key", "длинно").put("value", new String(new char[60]).replace('\0', 'я'));
        ToolResult full = reg.get("memory_save").run(overflow, null);
        assertFalse(full.ok);
        assertEquals("память заполнена: объедините или удалите записи", full.error);
        JSONObject j = new JSONObject(full.toModelText(2000));
        assertFalse(j.getBoolean("ok"));
        assertEquals(50, j.getJSONObject("usage").getInt("limit"));
        assertEquals(m.used(), j.getJSONObject("usage").getInt("used"));
        ToolResult list = reg.get("memory_get").run(new JSONObject(), null);
        JSONArray entries = list.data.getJSONArray("entries");
        assertEquals(1, entries.length());
        assertEquals("fact", entries.getJSONObject(0).getString("kind"));
        assertTrue(reg.get("memory_save").run(new JSONObject().put("key", "точки").put("value", "меньше точек").put("kind", "style"), null).ok);
        assertEquals(MemoryStore.STYLE, m.entry("точки").kind);
        assertTrue(reg.get("memory_pin").run(new JSONObject().put("key", "имя").put("on", true), null).ok);
        assertTrue(m.entry("имя").pinned);
        assertFalse(reg.get("memory_replace").run(new JSONObject().put("key", "нет").put("value", "x"), null).ok);
    }
}
