package app.jarvis.core.audio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The list of built-in cue sets (assets/sounds/sounds.json). Malformed entries are skipped, never fatal. */
public final class CueManifest {
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");
    private static final Pattern FILE = Pattern.compile("[a-z0-9_]{1,40}\\.ogg");
    static final int DEFAULT_MS = 300, MAX_MS = 1000;

    public static final class Set {
        public final String id, title, start, stop, error;
        /** Clip lengths: the cue is silent for the mic gate after this many milliseconds. */
        public final int startMs, stopMs, errorMs;

        Set(String id, String title, String start, String stop, String error, int startMs, int stopMs, int errorMs) {
            this.id = id;
            this.title = title;
            this.start = start;
            this.stop = stop;
            this.error = error;
            this.startMs = startMs;
            this.stopMs = stopMs;
            this.errorMs = errorMs;
        }

        public int durationMs(CuePolicy.Kind kind) {
            return kind == CuePolicy.Kind.START ? startMs : kind == CuePolicy.Kind.STOP ? stopMs : errorMs;
        }

        public String fileName(CuePolicy.Kind kind) {
            return kind == CuePolicy.Kind.START ? start : kind == CuePolicy.Kind.STOP ? stop : error;
        }
    }

    private CueManifest() { }

    public static List<Set> parse(String json) throws JSONException {
        JSONArray arr = new JSONArray(json);
        List<Set> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            String start = o.optString("start", ""), stop = o.optString("stop", ""), error = o.optString("error", "");
            if (!ID.matcher(id).matches() || !FILE.matcher(start).matches() || !FILE.matcher(stop).matches() || !FILE.matcher(error).matches()) continue;
            JSONObject d = o.optJSONObject("durationMs");
            out.add(new Set(id, o.optString("title_ru", id), start, stop, error,
                ms(d, "start"), ms(d, "stop"), ms(d, "error")));
        }
        return Collections.unmodifiableList(out);
    }

    /** The set with this id, else the default set, else the first one; null only for an empty manifest. */
    public static Set find(List<Set> sets, String id) {
        Set first = null;
        for (Set s : sets) {
            if (s.id.equals(id)) return s;
            if (s.id.equals(CueSettings.DEFAULT_SET)) first = s;
            if (first == null) first = s;
        }
        return first;
    }

    private static int ms(JSONObject d, String key) {
        int v = d == null ? DEFAULT_MS : d.optInt(key, DEFAULT_MS);
        return Math.max(1, Math.min(MAX_MS, v));
    }
}
