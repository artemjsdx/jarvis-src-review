package app.jarvis.core.log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Optional button of a journal entry's detail window ("Что делать"). Stored in the entry details as
 * {@code "action": {"id": ..., "label": ...}}; the UI maps the id to a screen.
 */
public final class JournalAction {
    public static final String BACKGROUND_SETTINGS = "background";

    public final String id;
    public final String label;

    public JournalAction(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public void putInto(JSONObject details) {
        try {
            details.put("action", new JSONObject().put("id", id).put("label", label));
        } catch (JSONException ignored) { /* plain strings: cannot fail */ }
    }

    /** The action stored in a details JSON string, or null when there is none or the JSON is not an object. */
    public static JournalAction from(String detailsJson) {
        if (detailsJson == null || !detailsJson.startsWith("{")) return null;
        try {
            JSONObject a = new JSONObject(detailsJson).optJSONObject("action");
            return a == null ? null : new JournalAction(a.optString("id"), a.optString("label"));
        } catch (JSONException e) {
            return null;
        }
    }
}
