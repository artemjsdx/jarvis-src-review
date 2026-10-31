package app.jarvis.core.agent;

import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.ToolCall;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Keeps the prompt inside a size budget without ever separating a tool call from its result. */
public final class ContextBudget {
    private ContextBudget() { }

    public static int size(List<ChatMessage> m) {
        int n = 0;
        for (ChatMessage x : m) n += x.sizeChars();
        return n;
    }

    /**
     * Guarantees protocol consistency: every tool message follows an assistant message that declared its id, and every
     * declared id has exactly one result. Used for history restored after a crash and after trimming.
     */
    public static List<ChatMessage> repairPairing(List<ChatMessage> in) {
        List<ChatMessage> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            ChatMessage m = in.get(i);
            if (ChatMessage.TOOL.equals(m.role)) continue; // orphans are re-added below only when attached to a declaring assistant
            if (ChatMessage.ASSISTANT.equals(m.role) && m.hasToolCalls()) {
                List<ChatMessage> results = new ArrayList<>();
                Set<String> declared = new HashSet<>();
                for (ToolCall c : m.toolCalls) declared.add(c.id);
                int j = i + 1;
                while (j < in.size() && ChatMessage.TOOL.equals(in.get(j).role)) {
                    ChatMessage r = in.get(j);
                    if (declared.remove(r.toolCallId)) results.add(r);
                    j++;
                }
                if (declared.isEmpty() && results.size() == m.toolCalls.size()) {
                    out.add(m);
                    out.addAll(results);
                } else {
                    // incomplete group (crash between call and result): keep the words, drop the dangling calls
                    if (!m.content.isEmpty()) out.add(ChatMessage.assistant(m.content, null));
                }
                i = j - 1;
            } else {
                out.add(m);
            }
        }
        return out;
    }

    /** Returns a copy of msgs (system first) that fits maxChars where possible. */
    public static List<ChatMessage> trim(List<ChatMessage> msgs, int maxChars) {
        if (size(msgs) <= maxChars) return msgs;
        List<ChatMessage> sys = new ArrayList<>();
        int i = 0;
        while (i < msgs.size() && ChatMessage.SYSTEM.equals(msgs.get(i).role)) sys.add(msgs.get(i++));
        List<List<ChatMessage>> turns = new ArrayList<>();
        for (; i < msgs.size(); i++) {
            ChatMessage m = msgs.get(i);
            if (ChatMessage.USER.equals(m.role) || turns.isEmpty()) turns.add(new ArrayList<ChatMessage>());
            turns.get(turns.size() - 1).add(m);
        }
        // 1) squeeze tool results of older turns
        for (int t = 0; t < turns.size() - 1; t++) squeezeTools(turns.get(t), 240);
        if (total(sys, turns) > maxChars) {
            // 2) drop the oldest whole turns
            while (turns.size() > 1 && total(sys, turns) > maxChars) turns.remove(0);
        }
        if (total(sys, turns) > maxChars) {
            // 3) the current turn alone is too big: squeeze its tool output, then drop its oldest step groups
            List<ChatMessage> cur = turns.get(turns.size() - 1);
            squeezeTools(cur, 1500);
            while (total(sys, turns) > maxChars && dropOldestStepGroup(cur)) { /* repeat */ }
        }
        List<ChatMessage> out = new ArrayList<>(sys);
        for (List<ChatMessage> t : turns) out.addAll(t);
        return repairPairing(out);
    }

    private static int total(List<ChatMessage> sys, List<List<ChatMessage>> turns) {
        int n = size(sys);
        for (List<ChatMessage> t : turns) n += size(t);
        return n;
    }

    private static void squeezeTools(List<ChatMessage> turn, int keep) {
        for (int k = 0; k < turn.size(); k++) {
            ChatMessage m = turn.get(k);
            if (ChatMessage.TOOL.equals(m.role) && m.content.length() > keep)
                turn.set(k, ChatMessage.tool(m.toolCallId, m.name, m.content.substring(0, keep) + "…[сокращено для экономии контекста]"));
        }
    }

    /** Removes the first assistant(tool_calls)+results group after the user message of this turn. */
    private static boolean dropOldestStepGroup(List<ChatMessage> turn) {
        for (int k = 1; k < turn.size(); k++) {
            if (ChatMessage.ASSISTANT.equals(turn.get(k).role) && turn.get(k).hasToolCalls() && k < turn.size() - 1) {
                int end = k + 1;
                while (end < turn.size() && ChatMessage.TOOL.equals(turn.get(end).role)) end++;
                if (end >= turn.size()) return false; // never drop the group the model is currently answering
                for (int r = end - 1; r >= k; r--) turn.remove(r);
                return true;
            }
        }
        return false;
    }
}
