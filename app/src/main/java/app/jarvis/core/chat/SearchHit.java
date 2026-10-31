package app.jarvis.core.chat;

/** One matched message: its chat, position in the chat, time, author and the excerpt around the match. */
public final class SearchHit {
    public final long convId, seq, ts;
    public final String title, role, snippet;

    public SearchHit(long convId, String title, long seq, long ts, String role, String snippet) {
        this.convId = convId;
        this.title = title == null ? "" : title;
        this.seq = seq;
        this.ts = ts;
        this.role = role == null ? "" : role;
        this.snippet = snippet == null ? "" : snippet;
    }
}
