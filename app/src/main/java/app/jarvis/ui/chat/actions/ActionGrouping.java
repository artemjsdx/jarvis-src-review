package app.jarvis.ui.chat.actions;

import app.jarvis.data.ChatItem;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure layout rules for the chat list. Consecutive TOOL items of one day form one action run. A blank assistant turn
 * (a protocol record that only carried tool calls) is neither shown nor a separator. A day change starts a new day row
 * and ends the current run; any other visible item ends it too.
 */
public final class ActionGrouping {
    private static final long DAY_MS = 86_400_000L;

    private ActionGrouping() { }

    public enum Kind { DAY, ITEM, RUN }

    public static final class Row {
        public final Kind kind;
        public final long ts;
        public final ChatItem item;
        public final List<ChatItem> run;

        private Row(Kind kind, long ts, ChatItem item, List<ChatItem> run) {
            this.kind = kind; this.ts = ts; this.item = item; this.run = run;
        }
    }

    /** True for an assistant turn with no text: it is not rendered and does not separate tool calls. */
    public static boolean isSkippedTurn(ChatItem it) {
        return it.kind == ChatItem.Kind.ASSISTANT && it.text.trim().isEmpty();
    }

    public static List<Row> layout(List<ChatItem> items) {
        List<Row> out = new ArrayList<>();
        long lastDay = Long.MIN_VALUE;
        List<ChatItem> run = null;
        for (ChatItem it : items) {
            if (isSkippedTurn(it)) continue;
            long day = it.ts / DAY_MS;
            if (day != lastDay) {
                out.add(new Row(Kind.DAY, it.ts, null, null));
                lastDay = day;
                run = null;
            }
            if (it.kind == ChatItem.Kind.TOOL) {
                if (run == null) {
                    run = new ArrayList<>();
                    out.add(new Row(Kind.RUN, it.ts, null, run));
                }
                run.add(it);
            } else {
                run = null;
                out.add(new Row(Kind.ITEM, it.ts, it, null));
            }
        }
        return out;
    }

    /** Header summary: number of actions and the total time (finished durations plus the elapsed time of running ones). */
    public static final class Summary {
        public final int count, running, failed;
        public final long totalMs;

        Summary(int count, int running, int failed, long totalMs) {
            this.count = count; this.running = running; this.failed = failed; this.totalMs = totalMs;
        }
    }

    public static Summary summarize(List<ChatItem> tools, long nowMs) {
        int running = 0, failed = 0;
        long total = 0;
        for (ChatItem t : tools) {
            if (t.toolStatus == ChatItem.ToolStatus.RUNNING) {
                running++;
                total += Math.max(0, nowMs - t.ts);
            } else {
                total += Math.max(0, t.durationMs);
                if (t.toolStatus == ChatItem.ToolStatus.FAILED) failed++;
            }
        }
        return new Summary(tools.size(), running, failed, total);
    }

    /** Russian plural for "действие": 1 действие, 2 действия, 5 действий, 21 действие. */
    public static String actionsWord(int n) {
        int m100 = n % 100, m10 = n % 10;
        if (m100 >= 11 && m100 <= 14) return "действий";
        if (m10 == 1) return "действие";
        if (m10 >= 2 && m10 <= 4) return "действия";
        return "действий";
    }
}
