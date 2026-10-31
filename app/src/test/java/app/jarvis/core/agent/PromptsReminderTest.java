package app.jarvis.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PromptsReminderTest {
    @Test public void reminderFallsOnEveryNthUserTurnOnly() {
        assertFalse(Prompts.reminderDue(1, 8));
        assertFalse(Prompts.reminderDue(7, 8));
        assertTrue(Prompts.reminderDue(8, 8));
        assertFalse(Prompts.reminderDue(9, 8));
        assertTrue(Prompts.reminderDue(16, 8));
        assertFalse(Prompts.reminderDue(8, 0));
        assertFalse(Prompts.reminderDue(0, 8));
    }

    @Test public void systemPromptNeverChangesWhenTheReminderIsDue() {
        Prompts.Info plain = info(false);
        Prompts.Info due = info(true);
        assertEquals(Prompts.system(plain), Prompts.system(due));
        assertFalse(Prompts.system(due).contains("напоминание"));
    }

    @Test public void volatileBlockCarriesTheRulesOnlyOnDueTurns() {
        int every = 8;
        for (int turn = 1; turn <= 16; turn++) {
            Prompts.Info i = info(Prompts.reminderDue(turn, every));
            String v = Prompts.volatileContext(i);
            boolean has = v.contains(Prompts.REMINDER);
            assertEquals("turn " + turn, turn % every == 0, has);
            assertTrue(v, v.startsWith("[контекст: ") && v.endsWith("]"));
            if (!has) assertEquals(Prompts.volatileContext(info(false)), v);
        }
    }

    @Test public void reminderNamesBothOwnerRules() {
        assertTrue(Prompts.REMINDER, Prompts.REMINDER.contains("короткими фразами"));
        assertTrue(Prompts.REMINDER, Prompts.REMINDER.contains("ok:true"));
    }

    @Test public void requestPrefixStaysByteIdenticalBeforeTheReminderTurn() {
        // the cache prefix is system + history (raw texts); the reminder only touches the current user message
        String system = Prompts.system(info(false));
        String history = "";
        for (int turn = 1; turn < 8; turn++) {
            String before = system + history;
            Prompts.Info i = info(Prompts.reminderDue(turn, 8));
            assertEquals("turn " + turn, before, Prompts.system(i) + history);
            history += "user " + turn + "\nassistant " + turn + "\n";
        }
    }

    private static Prompts.Info info(boolean reminder) {
        Prompts.Info i = new Prompts.Info();
        i.nowText = "пятница, 30 октября 2026, 10:00";
        i.memory = "пользователь предпочитает краткие ответы";
        i.reminder = reminder;
        return i;
    }
}
