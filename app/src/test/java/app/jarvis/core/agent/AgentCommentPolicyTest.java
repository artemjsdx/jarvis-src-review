package app.jarvis.core.agent;

import static org.junit.Assert.*;
import org.junit.Test;

public class AgentCommentPolicyTest {
    @Test public void beforeFirstToolOnly() {
        assertTrue(AgentCommentPolicy.preAction(true, 1, 0, false, false));
        assertFalse(AgentCommentPolicy.preAction(true, 2, 0, false, false));
        assertFalse(AgentCommentPolicy.preAction(true, 1, 1, false, false));
        assertFalse(AgentCommentPolicy.preAction(true, 1, 0, false, true));
        assertFalse(AgentCommentPolicy.preAction(true, 1, 0, true, false));
    }
    @Test public void commentsSkipFinalAndCancellation() {
        assertFalse(AgentCommentPolicy.due(1, 3, false, false));
        assertTrue(AgentCommentPolicy.due(3, 3, false, false));
        assertFalse(AgentCommentPolicy.due(3, 3, false, true));
        assertFalse(AgentCommentPolicy.due(3, 3, true, false));
        assertFalse(AgentCommentPolicy.due(3, 0, false, false));
    }
    @Test public void phrasesAreBounded() {
        assertEquals("Сейчас поищу и проверю информацию.", AgentCommentPolicy.preActionPhrase("web_search", ""));
        assertEquals("Свой текст", AgentCommentPolicy.preActionPhrase("adb_connect", "Свой текст|Ещё один"));
    }
}
