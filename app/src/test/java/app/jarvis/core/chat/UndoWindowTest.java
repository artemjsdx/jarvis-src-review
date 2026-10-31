package app.jarvis.core.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UndoWindowTest {
    @Test
    public void undoInsideWindowCancelsDelete() {
        UndoWindow w = new UndoWindow(5000);
        assertEquals(0, w.start(7, 1000));
        assertTrue(w.isPending(7));
        assertEquals(6000, w.deadline());
        assertEquals(0, w.takeExpired(5999));
        assertTrue(w.undo(7));
        assertEquals(0, w.takeExpired(60_000));
        assertFalse(w.hasPending());
        assertEquals(-1, w.deadline());
    }

    @Test
    public void expiredWindowIsCommittedOnce() {
        UndoWindow w = new UndoWindow(5000);
        w.start(7, 1000);
        assertEquals(7, w.takeExpired(6000));
        assertEquals(0, w.takeExpired(7000));
        assertFalse(w.undo(7));
    }

    @Test
    public void newDeleteCommitsThePreviousOne() {
        UndoWindow w = new UndoWindow(5000);
        w.start(7, 0);
        assertEquals(7, w.start(9, 1000));
        assertFalse(w.isPending(7));
        assertTrue(w.isPending(9));
        assertEquals(6000, w.deadline());
    }

    @Test
    public void restartingSameIdDoesNotCommitIt() {
        UndoWindow w = new UndoWindow(5000);
        w.start(7, 0);
        assertEquals(0, w.start(7, 1000));
        assertEquals(6000, w.deadline());
    }

    @Test
    public void pendingIdReportsWhatUndoWouldCancel() {
        UndoWindow w = new UndoWindow(5000);
        assertEquals(0, w.pendingId());
        w.start(12, 0);
        assertEquals(12, w.pendingId());
        w.undo(12);
        assertEquals(0, w.pendingId());
    }

    @Test
    public void undoOfOtherIdIsRejected() {
        UndoWindow w = new UndoWindow(5000);
        w.start(7, 0);
        assertFalse(w.undo(8));
        assertTrue(w.isPending(7));
    }
}
