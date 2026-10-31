package app.jarvis.core.audio;

import static org.junit.Assert.assertEquals;

import app.jarvis.core.audio.MicArbiterMachine.Act;
import app.jarvis.core.audio.MicArbiterMachine.State;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class MicArbiterMachineTest {
    private static void acts(List<Act> got, Act... want) {
        assertEquals(Arrays.asList(want), got);
    }

    @Test public void wantWithoutBlockersActivates() {
        MicArbiterMachine m = new MicArbiterMachine();
        acts(m.want(true), new Act[0]);
        assertEquals(State.ACTIVE, m.state());
        acts(m.want(true));
    }

    @Test public void eventsWhileIdleAreIgnored() {
        MicArbiterMachine m = new MicArbiterMachine();
        acts(m.blockers(true, false));
        acts(m.silenced());
        acts(m.timerFired());
        assertEquals(State.IDLE, m.state());
    }

    @Test public void otherAppYieldsAndResumesAfterDebounce() {
        MicArbiterMachine m = new MicArbiterMachine();
        m.want(true);
        acts(m.blockers(true, false), Act.PAUSE_MIC, Act.NOTIFY_BUSY);
        assertEquals(State.YIELDED, m.state());
        acts(m.blockers(false, false), Act.SCHEDULE_RESUME);
        acts(m.timerFired(), Act.OPEN_MIC);
        acts(m.micOpened(true), Act.RESUMED);
        assertEquals(State.ACTIVE, m.state());
    }

    @Test public void otherAppStartingAgainInsideDebounceCancelsResume() {
        MicArbiterMachine m = new MicArbiterMachine();
        m.want(true);
        m.blockers(true, false);
        m.blockers(false, false);
        acts(m.blockers(true, false), Act.CANCEL_RESUME, Act.NOTIFY_BUSY);
        acts(m.timerFired());
        assertEquals(State.YIELDED, m.state());
    }

    @Test public void callYieldsWithCallNoticeAndResumesWhenItEnds() {
        MicArbiterMachine m = new MicArbiterMachine();
        m.want(true);
        acts(m.blockers(false, true), Act.PAUSE_MIC, Act.NOTIFY_CALL);
        acts(m.blockers(false, false), Act.SCHEDULE_RESUME);
        acts(m.timerFired(), Act.OPEN_MIC);
        acts(m.micOpened(true), Act.RESUMED);
    }

    @Test public void callAndOtherAppOverlapResumesOnlyWhenBothEnd() {
        MicArbiterMachine m = new MicArbiterMachine();
        m.want(true);
        m.blockers(true, true);
        acts(m.blockers(true, false));
        acts(m.blockers(false, false), Act.SCHEDULE_RESUME);
    }

    @Test public void wantingWhileBlockedDoesNotOpen() {
        MicArbiterMachine m = new MicArbiterMachine();
        m.blockers(true, false);
        acts(m.want(true), Act.NOTIFY_BUSY);
        assertEquals(State.YIELDED, m.state());
        acts(m.timerFired());
    }

    @Test public void silenceThenOtherAppThenItsStopResumesOnce() {
        MicArbiterMachine m = new MicArbiterMachine();
        m.want(true);
        acts(m.silenced(), Act.PAUSE_MIC, Act.NOTIFY_BUSY, Act.SCHEDULE_RESUME);
        acts(m.blockers(true, false), Act.CANCEL_RESUME, Act.NOTIFY_BUSY);
        acts(m.blockers(false, false), Act.SCHEDULE_RESUME);
        acts(m.timerFired(), Act.OPEN_MIC);
    }

    @Test public void silencedWithoutOtherAppRetriesThenGivesUp() {
        MicArbiterMachine m = new MicArbiterMachine();
        m.want(true);
        acts(m.silenced(), Act.PAUSE_MIC, Act.NOTIFY_BUSY, Act.SCHEDULE_RESUME);
        acts(m.timerFired(), Act.OPEN_MIC);
        acts(m.micOpened(true), Act.RESUMED);
        acts(m.silenced(), Act.PAUSE_MIC, Act.NOTIFY_BUSY, Act.SCHEDULE_RESUME);
        acts(m.timerFired(), Act.OPEN_MIC);
        acts(m.micOpened(true), Act.RESUMED);
        acts(m.silenced(), Act.PAUSE_MIC, Act.NOTIFY_GAVE_UP);
        assertEquals(State.GAVE_UP, m.state());
        acts(m.blockers(false, false));
        acts(m.want(false));
        acts(m.want(true));
        assertEquals(State.ACTIVE, m.state());
    }

    @Test public void failedReopensAreRetriedThenGiveUp() {
        MicArbiterMachine m = new MicArbiterMachine();
        m.want(true);
        m.blockers(true, false);
        m.blockers(false, false);
        acts(m.timerFired(), Act.OPEN_MIC);
        acts(m.micOpened(false), Act.SCHEDULE_RESUME);
        acts(m.timerFired(), Act.OPEN_MIC);
        acts(m.micOpened(false), Act.SCHEDULE_RESUME);
        acts(m.timerFired(), Act.OPEN_MIC);
        acts(m.micOpened(false), Act.NOTIFY_GAVE_UP);
        assertEquals(State.GAVE_UP, m.state());
        acts(m.timerFired());
    }

    @Test public void successfulReopenClearsFailureCount() {
        MicArbiterMachine m = new MicArbiterMachine();
        m.want(true);
        m.blockers(true, false);
        m.blockers(false, false);
        m.timerFired();
        m.micOpened(false);
        m.timerFired();
        acts(m.micOpened(true), Act.RESUMED);
        m.blockers(true, false);
        m.blockers(false, false);
        m.timerFired();
        acts(m.micOpened(false), Act.SCHEDULE_RESUME);
    }

    @Test public void otherAppStartingWhileReopenIsInFlightPausesAgain() {
        MicArbiterMachine m = new MicArbiterMachine();
        m.want(true);
        m.blockers(true, false);
        m.blockers(false, false);
        m.timerFired();
        acts(m.blockers(true, false), Act.NOTIFY_BUSY);
        acts(m.micOpened(true), Act.PAUSE_MIC);
        assertEquals(State.YIELDED, m.state());
    }

    @Test public void switchingOffCancelsAPendingResume() {
        MicArbiterMachine m = new MicArbiterMachine();
        m.want(true);
        m.blockers(true, false);
        m.blockers(false, false);
        acts(m.want(false), Act.CANCEL_RESUME);
        assertEquals(State.IDLE, m.state());
        acts(m.timerFired());
    }

    /** Table of (event sequence) -> final state: every transition the Android side can produce. */
    @Test public void transitionTable() {
        Object[][] table = {
            {"want-on", State.ACTIVE},
            {"want-on,other-start", State.YIELDED},
            {"want-on,other-start,other-stop", State.YIELDED},
            {"want-on,other-start,other-stop,fire", State.YIELDED},
            {"want-on,other-start,other-stop,fire,opened", State.ACTIVE},
            {"want-on,call-start,call-end,fire,opened", State.ACTIVE},
            {"want-on,silenced", State.YIELDED},
            {"want-on,silenced,fire,opened-fail", State.YIELDED},
            {"want-on,fire-fail-x3", State.GAVE_UP},
            {"want-on,want-off", State.IDLE},
            {"want-on,other-start,want-off,want-on", State.YIELDED},
        };
        for (Object[] row : table) {
            MicArbiterMachine m = new MicArbiterMachine();
            List<String> seq = new ArrayList<>(Arrays.asList(((String) row[0]).split(",")));
            for (String ev : seq) apply(m, ev);
            assertEquals((String) row[0], row[1], m.state());
        }
    }

    private static void apply(MicArbiterMachine m, String ev) {
        switch (ev) {
            case "want-on": m.want(true); break;
            case "want-off": m.want(false); break;
            case "other-start": m.blockers(true, false); break;
            case "other-stop": m.blockers(false, false); break;
            case "call-start": m.blockers(false, true); break;
            case "call-end": m.blockers(false, false); break;
            case "silenced": m.silenced(); break;
            case "fire": m.timerFired(); break;
            case "opened": m.micOpened(true); break;
            case "opened-fail": m.micOpened(false); break;
            case "fire-fail-x3":
                m.blockers(true, false); m.blockers(false, false);
                for (int i = 0; i < 3; i++) { m.timerFired(); m.micOpened(false); }
                break;
            default: throw new IllegalArgumentException(ev);
        }
    }
}
