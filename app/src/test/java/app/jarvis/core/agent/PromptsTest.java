package app.jarvis.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PromptsTest {
    private static Prompts.Info info(String now, String player) {
        Prompts.Info i = new Prompts.Info();
        i.nowText = now; i.player = player; i.device = "vivo I2407, Android 16"; i.memory = "Любит кофе."; i.extra = "Любимая песня: не выбрана.\n";
        return i;
    }

    @Test public void systemPromptIsByteIdenticalWhenOnlyVolatileFieldsChange() {
        String a = Prompts.system(info("среда, 29 октября 2026, 10:41", "не играет"));
        String b = Prompts.system(info("среда, 29 октября 2026, 10:42", "играет «Песня»"));
        Prompts.Info c = info("четверг, 30 октября 2026, 03:00", "пауза");
        c.hint = "Команда уже выполнена.";
        assertEquals(a, b);
        assertEquals(a, Prompts.system(c));
        assertFalse(a.contains("10:41"));
        assertFalse(a.contains("vivo"));
    }

    @Test public void systemPromptChangesWhenMemoryChanges() {
        Prompts.Info i = info("x", "y");
        String a = Prompts.system(i);
        i.memory = "Любит чай.";
        assertTrue(!a.equals(Prompts.system(i)));
        assertTrue(Prompts.system(i).contains("Любит чай."));
    }

    @Test public void styleNotesGetTheirOwnSectionAndSkillsAreAppended() {
        Prompts.Info i = info("x", "y");
        i.memory = "- любит чай: Пользователь предпочитает чай";
        i.memoryStyle = "- точки: меньше точек в ответах";
        i.skills = "НАВЫКИ:\n<available_skills>\n- room-lights — Используй, когда свет.\n</available_skills>";
        String s = Prompts.system(i);
        assertTrue(s.contains("СТИЛЬ ОТВЕТА ПОЛЬЗОВАТЕЛЯ:\n- точки: меньше точек в ответах"));
        assertTrue(s.indexOf("СТИЛЬ ОТВЕТА ПОЛЬЗОВАТЕЛЯ:") < s.indexOf("ПАМЯТЬ ПОЛЬЗОВАТЕЛЯ:"));
        assertTrue(s.indexOf("ПАМЯТЬ ПОЛЬЗОВАТЕЛЯ:") < s.indexOf("<available_skills>"));
        i.memoryStyle = "";
        i.skills = "";
        assertFalse(Prompts.system(i).contains("СТИЛЬ ОТВЕТА ПОЛЬЗОВАТЕЛЯ"));
        assertFalse(Prompts.system(i).contains("available_skills"));
    }

    @Test public void volatileContextCarriesTimePlayerDeviceAndHint() {
        Prompts.Info i = info("среда, 10:41", "играет «Песня»");
        i.hint = "Команда уже выполнена инструментами.";
        String v = Prompts.volatileContext(i);
        assertTrue(v.startsWith("[контекст: ") && v.endsWith("]"));
        assertTrue(v.contains("сейчас: среда, 10:41"));
        assertTrue(v.contains("плеер: играет «Песня»"));
        assertTrue(v.contains("устройство: vivo"));
        assertTrue(v.contains("Команда уже выполнена"));
        assertEquals("", Prompts.volatileContext(new Prompts.Info()));
    }
}
