package app.jarvis.core.agent;

import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.LlmConfig;
import app.jarvis.core.log.Journal;
import java.util.ArrayList;
import java.util.List;

public final class AgentRunSpec {
    public static final class Hedge {
        public final LlmConfig config;
        public final String model;
        public final long afterMs;
        public Hedge(LlmConfig config, String model, long afterMs) { this.config = config; this.model = model; this.afterMs = afterMs; }
    }

    public interface LatencySink { void onStep(boolean hedgeWon, long firstTokenMs, long headersMs); }

    public String runId = "run-" + Long.toString(System.currentTimeMillis(), 36);
    public LlmConfig config;
    public String model = "";
    public String systemPrompt = "";
    public List<ChatMessage> history = new ArrayList<>();
    public ChatMessage user;
    /** Messages placed right AFTER the user message (e.g. a tool call the app already executed and its result), so the model only has to phrase the answer. Not reported to the listener. */
    public List<ChatMessage> prefill = new ArrayList<>();
    /** Volatile per-request block (time, player, one-off hints) appended to the CURRENT user message for the request only; history keeps the raw text. */
    public String userContext = "";
    /**
     * Fast mode: when every tool call of a step succeeded and every result carries a {@code spoken} hint, the run ends locally with the
     * joined hints and the model is not asked again.
     */
    public boolean localFinish;
    /** Optional second route raced against the primary when the primary gives no first token in time (see HedgedChat). */
    public Hedge hedge;
    /** Called with the first-token latency (ms) of every finished LLM step; feeds the rolling latency statistics. */
    public LatencySink latency;
    public ToolRegistry tools;
    /**
     * Announce only core tools + switched-on groups instead of every registered tool (see {@link ToolGroups}); null = announce all, as before.
     * The loop asks it for the list before every model step and answers the meta tool {@link ToolGroups#META_TOOL} itself.
     */
    public ToolSelection toolSelection;
    public int maxSteps = 10;
    public Double temperature;
    public Integer maxTokens;
    /** "" | off | instant | low | medium | high */
    public String reasoning = "";
    public boolean realtime;
    /** Approximate budget for the whole prompt, in characters (~3 chars per token for Russian). */
    public int contextChars = 60_000;
    public int maxToolOutputChars = 8_000;
    public Journal journal = Journal.NOP;
}
