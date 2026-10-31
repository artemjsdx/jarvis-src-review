package app.jarvis.ui.home;

import app.jarvis.rt.AState;

/** Pure text and tone rules for the Home status block (no Android types, so the JVM tests cover them). */
public final class HomeStatus {
    public enum Tone { MUTED, ACCENT, OK, ERROR }

    private HomeStatus() { }

    public static Tone tone(AState s) {
        switch (s) {
            case ERROR: return Tone.ERROR;
            case DONE: return Tone.OK;
            case IDLE: case WAITING: return Tone.MUTED;
            default: return Tone.ACCENT;
        }
    }

    /**
     * Line under the state label. Listening and idle states always show what the user can do now: a detail left over from
     * an earlier event (for example the word "речь" after a phrase ended) must not stay visible under a different state.
     * Tool and error states show their own detail.
     */
    public static String subtitle(AState s, String detail) {
        switch (s) {
            case LISTENING: return "Говорите, я слушаю";
            case WAITING: return "Скажите «Джарвис», чтобы начать";
            case IDLE: return "Нажмите микрофон и скажите команду";
            case TOOL: case ERROR: return detail == null ? "" : detail;
            default: return "";
        }
    }
}
