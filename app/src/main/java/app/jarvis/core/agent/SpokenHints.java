package app.jarvis.core.agent;

import org.json.JSONObject;

/**
 * Ready, truthful one-line answers for tools whose success is fully described by "it worked" (player control, opening a screen,
 * creating a file). Used by fast mode to finish a turn without a second model call. Returns {@code null} when the tool has no hint
 * (the model must phrase the answer), {@code ""} when the right answer is silence (the tool itself already acted audibly).
 * Failed results never get a hint: the model must explain the real error.
 */
public final class SpokenHints {
    private SpokenHints() { }

    public static String forTool(String name, JSONObject args, ToolResult r, FastPhrases ph) {
        if (r == null || !r.ok) return null;
        if (!r.spoken.isEmpty()) return r.spoken;
        switch (name) {
            case "music_play": case "music_play_latest_received": return ph.pick(FastPhrases.Cat.PLAY);
            case "music_pause": return ph.pick(FastPhrases.Cat.PAUSE);
            case "music_resume": return ph.pick(FastPhrases.Cat.RESUME);
            case "music_stop": return ph.pick(FastPhrases.Cat.STOP);
            case "music_next": return ph.pick(FastPhrases.Cat.NEXT);
            case "music_prev": return ph.pick(FastPhrases.Cat.PREV);
            case "app_open": return args != null && "files".equals(args.optString("screen")) ? ph.pick(FastPhrases.Cat.FILES) : ph.pick(FastPhrases.Cat.OPEN);
            case "app_setting": return args != null && args.has("value") ? ph.pick(FastPhrases.Cat.DONE) : null;   // a read needs the real value spoken
            case "speech_stop": return "";
            case "fs_mkdir": return ph.pick(FastPhrases.Cat.FOLDER);
            case "fs_write": return ph.pick(FastPhrases.Cat.SAVED);
            case "note_create": return ph.pick(FastPhrases.Cat.NOTE);
            default: return null;
        }
    }
}
