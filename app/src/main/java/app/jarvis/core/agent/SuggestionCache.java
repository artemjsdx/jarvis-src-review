package app.jarvis.core.agent;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The last generated chips with the time and context fingerprint they were made for (suggestions.json). */
public final class SuggestionCache {
    public static final class Entry {
        public final List<String> items;
        public final long at;
        public final String hash;

        Entry(List<String> items, long at, String hash) { this.items = items; this.at = at; this.hash = hash; }
    }

    private SuggestionCache() { }

    public static String encode(List<String> items, long at, String hash) {
        try {
            JSONArray arr = new JSONArray();
            for (String s : items) arr.put(s);
            return new JSONObject().put("items", arr).put("at", at).put("hash", hash).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Null when the text is not a valid cache file or holds no chips. */
    public static Entry decode(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray arr = o.getJSONArray("items");
            List<String> items = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (!s.isEmpty()) items.add(s);
            }
            if (items.isEmpty()) return null;
            return new Entry(items, o.optLong("at", 0L), o.optString("hash", ""));
        } catch (Exception e) {
            return null;
        }
    }
}
