package app.jarvis.rt;

import app.jarvis.data.ChatItem;

public final class ChatEvent {
    public enum Type { ADDED, UPDATED, STREAM, STREAM_END, CLEARED, SWITCHED }

    public final Type type;
    /** For ADDED/UPDATED. */
    public final ChatItem item;
    /** For STREAM: the assistant text produced so far in the current model step. */
    public final String streamText;
    public final String runId;

    ChatEvent(Type type, ChatItem item, String streamText, String runId) { this.type = type; this.item = item; this.streamText = streamText; this.runId = runId; }
}
