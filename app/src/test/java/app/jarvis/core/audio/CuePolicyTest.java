package app.jarvis.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.audio.CuePolicy.Env;
import app.jarvis.core.audio.CuePolicy.Kind;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class CuePolicyTest {
    private static CueSettings settings(String... kv) {
        final Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return CueSettings.resolve(new CueSettings.Source() { @Override public String get(String key) { return m.get(key); } });
    }

    private static final Env QUIET_FREE = new Env(true, false, false);

    @Test public void playsByDefaultForEveryKind() {
        CueSettings s = settings();
        for (Kind k : Kind.values()) assertTrue(k.name(), CuePolicy.decide(k, s, QUIET_FREE).play);
    }

    @Test public void masterSwitchSilencesEverything() {
        CueSettings s = settings(CueSettings.ENABLED, "false");
        for (Kind k : Kind.values()) assertFalse(CuePolicy.decide(k, s, QUIET_FREE).play);
    }

    @Test public void startAndStopAreSwitchedSeparately() {
        CueSettings s = settings(CueSettings.START_ON, "false");
        assertFalse(CuePolicy.decide(Kind.START, s, QUIET_FREE).play);
        assertTrue(CuePolicy.decide(Kind.STOP, s, QUIET_FREE).play);
        assertTrue(CuePolicy.decide(Kind.ERROR, s, QUIET_FREE).play);
        CuePolicy.Decision d = CuePolicy.decide(Kind.START, s, QUIET_FREE);
        assertEquals("сигнал «начало» выключен", d.reason);
    }

    /** Table: environment rows against the three kinds (silent mode, call, speaking). */
    @Test public void environmentTable() {
        CueSettings s = settings();
        Object[][] rows = {
            // soundAllowed, inCall, speaking, kind, expectPlay
            {true,  false, false, Kind.START, true},
            {false, false, false, Kind.START, false},  // silent mode: the visual indicator stays
            {true,  true,  false, Kind.START, false},  // phone call
            {true,  false, true,  Kind.START, true},   // start cue is the barge-in: allowed while speaking
            {true,  false, true,  Kind.STOP,  false},  // never over the assistant's own speech
            {true,  false, true,  Kind.ERROR, false},
            {false, false, false, Kind.STOP,  false},
            {true,  false, false, Kind.ERROR, true},
            {true,  true,  false, Kind.STOP,  false},
        };
        for (Object[] r : rows) {
            Env e = new Env((Boolean) r[0], (Boolean) r[1], (Boolean) r[2]);
            CuePolicy.Decision d = CuePolicy.decide((Kind) r[3], s, e);
            assertEquals(java.util.Arrays.toString(r), (Boolean) r[4], d.play);
            if (!d.play) assertFalse(d.reason.isEmpty());
        }
    }

    @Test public void silentDecisionExplainsItself() {
        CuePolicy.Decision d = CuePolicy.decide(Kind.STOP, settings(), new Env(false, false, false));
        assertEquals("тихий режим или «Не беспокоить»", d.reason);
    }
}
