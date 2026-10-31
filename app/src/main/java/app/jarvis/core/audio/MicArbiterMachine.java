package app.jarvis.core.audio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Who holds the microphone: the assistant or another app. Pure state machine; the Android side reports events
 * (another app recording, a phone call, our own capture silenced by the system, timer fired, open result) and carries out
 * the returned actions.
 *
 * Yield: as soon as another app records, a call starts or our capture is silenced, the assistant stops its capture and says
 * so. Resume: once nothing blocks any more, a debounced reopen runs after {@link #RESUME_DELAY_MS}; a failed reopen is retried
 * the same way, and after {@link #MAX_ATTEMPTS} failures the assistant gives up and asks the user (GAVE_UP) until the user
 * switches listening off and on again.
 */
public final class MicArbiterMachine {
    public enum State { IDLE, ACTIVE, YIELDED, GAVE_UP }

    public enum Act { PAUSE_MIC, OPEN_MIC, SCHEDULE_RESUME, CANCEL_RESUME, NOTIFY_BUSY, NOTIFY_CALL, NOTIFY_GAVE_UP, RESUMED }

    public static final long RESUME_DELAY_MS = 1200;
    public static final int MAX_ATTEMPTS = 3;

    private static final List<Act> NONE = Collections.emptyList();

    private State state = State.IDLE;
    private boolean wanted, otherApp, call, timerPending;
    private int failedOpens, silentYields;

    public State state() { return state; }

    public boolean otherAppHolds() { return otherApp; }

    /** True while the assistant may not open the microphone (another app or a call holds it). */
    public boolean blocked() { return otherApp || call; }

    /** The user switched listening on or off. Switching on clears a give-up and starts without waiting. */
    public List<Act> want(boolean on) {
        if (on == wanted && !(on && state == State.GAVE_UP)) return NONE;
        wanted = on;
        failedOpens = 0;
        silentYields = 0;
        List<Act> out = new ArrayList<>();
        if (timerPending) { timerPending = false; out.add(Act.CANCEL_RESUME); }
        if (!on) { state = State.IDLE; return out; }
        if (blocked()) {
            state = State.YIELDED;
            out.add(notifyFor());
        } else {
            state = State.ACTIVE;
        }
        return out;
    }

    /** Another app's recording or a call changed. */
    public List<Act> blockers(boolean otherAppRecording, boolean inCall) {
        boolean wasBlocked = blocked();
        otherApp = otherAppRecording;
        call = inCall;
        if (!wanted || state == State.IDLE || state == State.GAVE_UP) return NONE;
        List<Act> out = new ArrayList<>();
        if (blocked()) {
            if (timerPending) { timerPending = false; out.add(Act.CANCEL_RESUME); }
            if (state == State.ACTIVE) {
                state = State.YIELDED;
                out.add(Act.PAUSE_MIC);
                out.add(notifyFor());
            } else if (state == State.YIELDED && !wasBlocked) {
                out.add(notifyFor());
            }
        } else if (state == State.YIELDED && !timerPending) {
            timerPending = true;
            out.add(Act.SCHEDULE_RESUME);
        }
        return out;
    }

    /**
     * Our own capture was silenced by the system. With another app recording that is the yield itself; without one the
     * system is withholding audio for some other reason, so we retry a bounded number of times before giving up.
     */
    public List<Act> silenced() {
        if (!wanted || state != State.ACTIVE) return NONE;
        List<Act> out = new ArrayList<>();
        out.add(Act.PAUSE_MIC);
        state = State.YIELDED;
        if (!otherApp && ++silentYields >= MAX_ATTEMPTS) {
            state = State.GAVE_UP;
            out.add(Act.NOTIFY_GAVE_UP);
            return out;
        }
        out.add(notifyFor());
        if (!blocked()) {
            timerPending = true;
            out.add(Act.SCHEDULE_RESUME);
        }
        return out;
    }

    /** The debounce timer fired. */
    public List<Act> timerFired() {
        if (!timerPending) return NONE;
        timerPending = false;
        if (blocked() || state != State.YIELDED) return NONE;
        return Collections.singletonList(Act.OPEN_MIC);
    }

    /** Result of an OPEN_MIC the machine asked for. */
    public List<Act> micOpened(boolean ok) {
        List<Act> out = new ArrayList<>();
        if (ok) {
            failedOpens = 0;
            if (blocked()) {
                state = State.YIELDED;
                out.add(Act.PAUSE_MIC);
            } else {
                state = State.ACTIVE;
                out.add(Act.RESUMED);
            }
            return out;
        }
        if (++failedOpens >= MAX_ATTEMPTS) {
            state = State.GAVE_UP;
            out.add(Act.NOTIFY_GAVE_UP);
            return out;
        }
        state = State.YIELDED;
        timerPending = true;
        out.add(Act.SCHEDULE_RESUME);
        return out;
    }

    private Act notifyFor() {
        return call ? Act.NOTIFY_CALL : Act.NOTIFY_BUSY;
    }
}
