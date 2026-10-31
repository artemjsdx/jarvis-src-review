package app.jarvis.core.audio;

/** Decides whether a listening cue sounds. Pure: the Android side gathers the environment and plays what this allows. */
public final class CuePolicy {
    public enum Kind { START, STOP, ERROR }

    /** Snapshot of the phone at the moment a cue would play. */
    public static final class Env {
        /** Ringer set to vibrate or silent, or the interruption filter (do-not-disturb) blocks sounds. */
        public final boolean soundAllowed;
        /** A phone call or VoIP call holds the audio mode. */
        public final boolean inCall;
        /** The assistant is speaking right now (a start cue is still allowed: it is the barge-in). */
        public final boolean speaking;

        public Env(boolean soundAllowed, boolean inCall, boolean speaking) {
            this.soundAllowed = soundAllowed;
            this.inCall = inCall;
            this.speaking = speaking;
        }
    }

    public static final class Decision {
        public final boolean play;
        /** Why it is silent (or "" when it plays): written to the journal at DEBUG level. */
        public final String reason;

        Decision(boolean play, String reason) {
            this.play = play;
            this.reason = reason;
        }
    }

    private CuePolicy() { }

    public static Decision decide(Kind kind, CueSettings s, Env e) {
        if (!s.enabled) return new Decision(false, "сигналы выключены");
        boolean kindOn = kind == Kind.START ? s.startOn : kind == Kind.STOP ? s.stopOn : s.errorOn;
        if (!kindOn) return new Decision(false, "сигнал «" + kindTitle(kind) + "» выключен");
        if (e.inCall) return new Decision(false, "идёт звонок");
        if (!e.soundAllowed) return new Decision(false, "тихий режим или «Не беспокоить»");
        if (e.speaking && kind != Kind.START) return new Decision(false, "ассистент говорит");
        return new Decision(true, "");
    }

    static String kindTitle(Kind kind) {
        switch (kind) {
            case START: return "начало";
            case STOP: return "конец";
            default: return "ошибка";
        }
    }
}
