package app.jarvis.data;

import java.util.ArrayList;
import java.util.List;

/** One row of the chat as the UI sees it (also the persistent form of the model protocol, see ChatStore.history). */
public final class ChatItem {
    public enum Kind { USER, ASSISTANT, TOOL, ERROR, SYSTEM }
    public enum ToolStatus { RUNNING, DONE, FAILED, INTERRUPTED }

    public static final class Attachment {
        public String path = "", name = "", mime = "";
        public long size;
        public String durationText = "";
    }

    public long seq;
    public long convId;
    public Kind kind = Kind.SYSTEM;
    public long ts;
    public String runId = "";
    /** USER/ASSISTANT/ERROR/SYSTEM: the text. TOOL: the JSON result exactly as sent to the model (empty while running). */
    public String text = "";
    // USER
    public final List<Attachment> attachments = new ArrayList<>();
    /** "text" or "voice". */
    public String source = "text";
    // ASSISTANT: tool calls the model issued in this message, as JSON array text (OpenAI shape); "" if none
    public String toolCallsJson = "";
    public String model = "";
    // TOOL
    public String callId = "", toolName = "", toolArgs = "", summary = "";
    public boolean ok;
    public long durationMs;
    public ToolStatus toolStatus = ToolStatus.DONE;
    /** UI-only: partial text of an assistant message that is still streaming. */
    public boolean streaming;
}
