package app.jarvis.ui.chat;

import static org.junit.Assert.assertEquals;

import app.jarvis.data.ChatItem;
import app.jarvis.ui.chat.actions.ActionGrouping;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ActionGroupingTest {
    private static final long DAY = 86_400_000L;

    private static ChatItem item(ChatItem.Kind k, String text, long ts) {
        ChatItem it = new ChatItem();
        it.kind = k;
        it.text = text;
        it.ts = ts;
        return it;
    }

    private static ChatItem tool(long ts, long durMs, ChatItem.ToolStatus st) {
        ChatItem it = item(ChatItem.Kind.TOOL, "", ts);
        it.toolName = "fs_read";
        it.durationMs = durMs;
        it.toolStatus = st;
        return it;
    }

    @Test
    public void blankTurnsDoNotBreakARunButVisibleTextDoes() {
        long t = 10 * DAY;
        List<ChatItem> items = new ArrayList<>();
        items.add(item(ChatItem.Kind.USER, "привет", t));
        items.add(item(ChatItem.Kind.ASSISTANT, "", t + 1));
        items.add(tool(t + 2, 5, ChatItem.ToolStatus.DONE));
        items.add(tool(t + 3, 5, ChatItem.ToolStatus.DONE));
        items.add(item(ChatItem.Kind.ASSISTANT, "", t + 4));
        items.add(tool(t + 5, 5, ChatItem.ToolStatus.RUNNING));
        items.add(item(ChatItem.Kind.ASSISTANT, "готово", t + 6));
        items.add(tool(t + 7, 5, ChatItem.ToolStatus.DONE));
        List<ActionGrouping.Row> rows = ActionGrouping.layout(items);
        assertEquals(5, rows.size());
        assertEquals(ActionGrouping.Kind.DAY, rows.get(0).kind);
        assertEquals(ActionGrouping.Kind.ITEM, rows.get(1).kind);
        assertEquals(ActionGrouping.Kind.RUN, rows.get(2).kind);
        assertEquals(3, rows.get(2).run.size());
        assertEquals(ActionGrouping.Kind.ITEM, rows.get(3).kind);
        assertEquals(ActionGrouping.Kind.RUN, rows.get(4).kind);
        assertEquals(1, rows.get(4).run.size());
    }

    @Test
    public void dayChangeStartsNewDayRowAndNewRun() {
        List<ChatItem> items = new ArrayList<>();
        items.add(tool(3 * DAY + 100, 1, ChatItem.ToolStatus.DONE));
        items.add(tool(4 * DAY + 100, 1, ChatItem.ToolStatus.DONE));
        List<ActionGrouping.Row> rows = ActionGrouping.layout(items);
        assertEquals(4, rows.size());
        assertEquals(ActionGrouping.Kind.DAY, rows.get(2).kind);
        assertEquals(ActionGrouping.Kind.RUN, rows.get(3).kind);
    }

    @Test
    public void summaryCountsRunningAndFailedAndAddsLiveTime() {
        List<ChatItem> tools = new ArrayList<>();
        tools.add(tool(1_000, 4_200, ChatItem.ToolStatus.DONE));
        tools.add(tool(2_000, 300, ChatItem.ToolStatus.FAILED));
        tools.add(tool(5_000, 0, ChatItem.ToolStatus.RUNNING));
        ActionGrouping.Summary s = ActionGrouping.summarize(tools, 7_000);
        assertEquals(3, s.count);
        assertEquals(1, s.running);
        assertEquals(1, s.failed);
        assertEquals(4_200 + 300 + 2_000, s.totalMs);
    }

    @Test
    public void russianPluralForms() {
        assertEquals("действие", ActionGrouping.actionsWord(1));
        assertEquals("действия", ActionGrouping.actionsWord(3));
        assertEquals("действий", ActionGrouping.actionsWord(5));
        assertEquals("действие", ActionGrouping.actionsWord(21));
        assertEquals("действий", ActionGrouping.actionsWord(12));
        assertEquals("действия", ActionGrouping.actionsWord(102));
    }
}
