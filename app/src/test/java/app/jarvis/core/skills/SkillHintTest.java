package app.jarvis.core.skills;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SkillHintTest {
    private static final long MIN = 60_000L;

    @Test public void offersOnlyAfterManyCallsOrRecovery() {
        SkillHint h = new SkillHint();
        h.noteRun(3, false, 0);
        assertEquals("", h.takeHint(0));
        h.noteRun(4, false, 0);
        String hint = h.takeHint(0);
        assertTrue(hint.startsWith("[подсказка] В прошлом запуске было 4 вызова инструментов"));
        assertEquals("вызов инструментов", SkillHint.callsWord(21));
        assertEquals("вызовов инструментов", SkillHint.callsWord(12));
        assertTrue(hint.contains("предложи пользователю сохранить её как навык"));
        assertEquals("", h.takeHint(10 * MIN));
        h.noteRun(1, true, 31 * MIN);
        assertTrue(h.takeHint(31 * MIN).contains("был восстановлен сбой"));
    }

    @Test public void rateLimitedToOncePerThirtyMinutes() {
        SkillHint h = new SkillHint();
        h.noteRun(5, false, 0);
        assertTrue(!h.takeHint(0).isEmpty());
        h.noteRun(6, false, 29 * MIN);
        assertEquals("", h.takeHint(29 * MIN));
        h.noteRun(7, false, 31 * MIN);
        assertTrue(h.takeHint(31 * MIN).contains("7 вызовов инструментов"));
    }

    @Test public void pendingHintIsNotRepeatedAndIsDroppedWhenLimited() {
        SkillHint h = new SkillHint();
        h.noteRun(4, false, 0);
        assertTrue(!h.takeHint(0).isEmpty());
        assertEquals("", h.takeHint(0));
        h.noteRun(4, false, MIN);
        assertEquals("", h.takeHint(MIN));
    }
}
