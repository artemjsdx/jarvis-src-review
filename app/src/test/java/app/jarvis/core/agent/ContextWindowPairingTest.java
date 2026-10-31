package app.jarvis.core.agent;

import static org.junit.Assert.assertEquals;

import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.ToolCall;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** A session window cut by "clear context" may begin in the middle of a tool step; the model must never see the orphan result. */
public class ContextWindowPairingTest {
    @Test public void resultWhoseCallWasClearedAwayIsDropped() {
        List<ChatMessage> window = new ArrayList<>(Arrays.asList(
            ChatMessage.tool("c1", "note_create", "{\"ok\":true}"),
            ChatMessage.user("а что дальше?"),
            ChatMessage.assistant("Готово.", null)));
        List<ChatMessage> out = ContextBudget.repairPairing(window);
        assertEquals(2, out.size());
        assertEquals(ChatMessage.USER, out.get(0).role);
        assertEquals(ChatMessage.ASSISTANT, out.get(1).role);
    }

    @Test public void completeGroupAfterTheBoundaryIsKept() {
        ToolCall call = new ToolCall("c2", "time_now", "{}");
        List<ChatMessage> window = new ArrayList<>(Arrays.asList(
            ChatMessage.user("сколько времени"),
            ChatMessage.assistant("", Collections.singletonList(call)),
            ChatMessage.tool("c2", "time_now", "{\"ok\":true}")));
        List<ChatMessage> out = ContextBudget.repairPairing(window);
        assertEquals(3, out.size());
        assertEquals("c2", out.get(2).toolCallId);
    }
}
