package app.jarvis.core.chat;

/**
 * One undoable deletion at a time. The row is hidden at once; the real delete runs when the window closes, so an undo
 * before that needs no restore of stored data. Starting a new window closes the previous one immediately.
 */
public final class UndoWindow {
    private final long windowMs;
    private long id;
    private long deadline;

    public UndoWindow(long windowMs) { this.windowMs = windowMs; }

    /** @return the id of the window that was still open and must be committed now, or 0. */
    public long start(long id, long now) {
        long previous = this.id;
        this.id = id;
        deadline = now + windowMs;
        return previous == id ? 0 : previous;
    }

    public boolean isPending(long id) { return this.id != 0 && this.id == id; }

    public boolean hasPending() { return id != 0; }

    /** The pending id, or 0 when nothing is pending. */
    public long pendingId() { return id; }

    /** Cancels the pending deletion. @return false if {@code id} is not the pending one. */
    public boolean undo(long id) {
        if (!isPending(id)) return false;
        this.id = 0;
        return true;
    }

    /** @return the pending id once its window has closed (the window is cleared), otherwise 0. */
    public long takeExpired(long now) {
        if (id == 0 || now < deadline) return 0;
        long expired = id;
        id = 0;
        return expired;
    }

    /** Epoch ms when the pending window closes, or -1 when nothing is pending. */
    public long deadline() { return id == 0 ? -1 : deadline; }
}
