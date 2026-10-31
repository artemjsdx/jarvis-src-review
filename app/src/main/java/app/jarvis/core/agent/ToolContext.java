package app.jarvis.core.agent;

import app.jarvis.core.log.Journal;
import app.jarvis.core.util.CancelToken;

public final class ToolContext {
    public interface Progress { void progress(String text); }

    public final String runId, callId;
    public final CancelToken cancel;
    public final Journal journal;
    public final Progress progress;

    public ToolContext(String runId, String callId, CancelToken cancel, Journal journal, Progress progress) {
        this.runId = runId;
        this.callId = callId;
        this.cancel = cancel;
        this.journal = journal == null ? Journal.NOP : journal;
        this.progress = progress == null ? t -> { } : progress;
    }
}
