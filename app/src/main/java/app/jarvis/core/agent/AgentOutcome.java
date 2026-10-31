package app.jarvis.core.agent;

import app.jarvis.core.llm.LlmException;

public final class AgentOutcome {
    public enum Status { DONE, ERROR, CANCELLED, MAX_STEPS }

    public Status status = Status.DONE;
    public String finalText = "";
    public LlmException error;
    public String errorText = "";
    public int steps, toolCalls;
    public long totalMs, firstTextMs = -1;

    public boolean ok() { return status == Status.DONE; }
}
