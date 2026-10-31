package app.jarvis.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.ToolCall;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class ContextBudgetTest {
    private static ChatMessage call(String id) { return ChatMessage.assistant("", Collections.singletonList(new ToolCall(id, "t", "{}"))); }

    private static void assertPaired(List<ChatMessage> l) {
        Set<String> declared = new HashSet<>();
        for (int i = 0; i < l.size(); i++) {
            ChatMessage m = l.get(i);
            if (m.hasToolCalls()) {
                declared.clear();
                for (ToolCall c : m.toolCalls) declared.add(c.id);
            } else if ("tool".equals(m.role)) {
                assertTrue("orphan tool message " + m.toolCallId, declared.remove(m.toolCallId));
            } else {
                assertTrue("assistant tool_calls without results before " + m.role, declared.isEmpty());
            }
        }
        assertTrue("dangling tool_calls at end", declared.isEmpty());
    }

    @Test public void repairDropsDanglingCallAfterCrash() {
        List<ChatMessage> h = Arrays.asList(ChatMessage.user("a"), call("x"));            // process died before the result was stored
        List<ChatMessage> r = ContextBudget.repairPairing(h);
        assertEquals(1, r.size());
        assertPaired(r);
    }

    @Test public void repairDropsOrphanToolMessage() {
        List<ChatMessage> h = Arrays.asList(ChatMessage.user("a"), ChatMessage.tool("zzz", "t", "{}"), ChatMessage.assistant("b", null));
        List<ChatMessage> r = ContextBudget.repairPairing(h);
        assertEquals(2, r.size());
        assertPaired(r);
    }

    @Test public void repairKeepsCompleteGroups() {
        List<ChatMessage> h = Arrays.asList(ChatMessage.user("a"), call("x"), ChatMessage.tool("x", "t", "{}"), ChatMessage.assistant("done", null));
        assertEquals(4, ContextBudget.repairPairing(h).size());
    }

    @Test public void trimDropsOldTurnsButNeverSplitsAToolGroup() {
        List<ChatMessage> m = new ArrayList<>();
        m.add(ChatMessage.system("sys"));
        for (int i = 0; i < 12; i++) {
            m.add(ChatMessage.user("вопрос " + i + new String(new char[400]).replace('\0', 'ж')));
            m.add(call("c" + i));
            m.add(ChatMessage.tool("c" + i, "t", new String(new char[2000]).replace('\0', 'я')));
            m.add(ChatMessage.assistant("ответ " + i, null));
        }
        m.add(ChatMessage.user("последний вопрос"));
        List<ChatMessage> t = ContextBudget.trim(m, 6000);
        assertTrue(ContextBudget.size(t) <= 6000);
        assertEquals("system", t.get(0).role);
        assertEquals("последний вопрос", t.get(t.size() - 1).content);
        assertPaired(t.subList(1, t.size()));
    }

    @Test public void hugeSingleTurnIsSqueezedNotCorrupted() {
        List<ChatMessage> m = new ArrayList<>();
        m.add(ChatMessage.system("sys"));
        m.add(ChatMessage.user("задача"));
        for (int i = 0; i < 6; i++) {
            m.add(call("c" + i));
            m.add(ChatMessage.tool("c" + i, "t", new String(new char[5000]).replace('\0', 'x')));
        }
        List<ChatMessage> t = ContextBudget.trim(m, 4000);
        assertFalse(t.isEmpty());
        assertPaired(t.subList(1, t.size()));
        assertTrue(ContextBudget.size(t) <= 4000 + 400);
    }
}
