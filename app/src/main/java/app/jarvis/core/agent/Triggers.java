package app.jarvis.core.agent;

import app.jarvis.core.util.J;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Phrase triggers («когда говорю X — сделай Y»). A trigger is either DIRECT (a registered action, runs before the model
 * and costs no LLM call) or LLM (an instruction handed to the model as a hint for that request only). Stored in
 * triggers.json; read on every command, so the UI and the agent's trigger_save tool never need a cache refresh.
 */
public final class Triggers {
    public enum Mode {
        DIRECT, LLM;

        public static Mode parse(String s) { return "llm".equalsIgnoreCase(s) ? LLM : DIRECT; }
    }

    public static final String MORNING = "morning", PLAY_FAVORITE = "music.playFavorite";
    public static final int MAX_PHRASES = 10, MAX_WORDS = 9;
    private static final Pattern WAKE = Pattern.compile("^(?:джарвис|жарвис|джарвиз|джервис|джарвес|jarvis)\\s+");
    private static final Pattern APP_OPEN = Pattern.compile("^app\\.open:[a-z/]{2,30}$");
    private static final Pattern TOOL_CALL = Pattern.compile("^tools\\.call:([a-z_][a-z0-9_]*):(\\{.*\\})$", Pattern.DOTALL);

    public static final class Trigger {
        public final String id;
        public final List<String> phrases;
        public final Mode mode;
        /** DIRECT only: morning | music.playFavorite | app.open:&lt;screen&gt; | tools.call:&lt;name&gt;:&lt;json&gt;. */
        public final String action;
        /** LLM only: what the model should do when the phrase is heard. */
        public final String instruction;
        public final boolean enabled;

        public Trigger(String id, List<String> phrases, Mode mode, String action, String instruction, boolean enabled) {
            this.id = id;
            this.phrases = Collections.unmodifiableList(new ArrayList<>(phrases));
            this.mode = mode;
            this.action = action == null ? "" : action;
            this.instruction = instruction == null ? "" : instruction;
            this.enabled = enabled;
        }

        public boolean direct() { return mode == Mode.DIRECT; }

        public JSONObject toJson() {
            JSONArray p = new JSONArray();
            for (String s : phrases) p.put(s);
            JSONObject o = new JSONObject();
            J.put(o, "id", id);
            J.put(o, "phrases", p);
            J.put(o, "mode", mode == Mode.LLM ? "llm" : "direct");
            J.put(o, "action", action);
            J.put(o, "instruction", instruction);
            J.put(o, "enabled", enabled);
            return o;
        }

        static Trigger fromJson(JSONObject o) {
            List<String> ph = new ArrayList<>();
            JSONArray a = o.optJSONArray("phrases");
            if (a != null) for (int i = 0; i < a.length(); i++) { String s = a.optString(i, "").trim(); if (!s.isEmpty()) ph.add(s); }
            return new Trigger(o.optString("id", ""), ph, Mode.parse(o.optString("mode", "direct")), o.optString("action", ""),
                o.optString("instruction", ""), o.optBoolean("enabled", true));
        }
    }

    private final List<Trigger> items;

    public Triggers(List<Trigger> items) { this.items = new ArrayList<>(items); }

    public List<Trigger> all() { return Collections.unmodifiableList(items); }

    /** The first enabled trigger whose phrase is the whole command or its beginning; null when none. */
    public Trigger match(String raw) {
        String t = commandText(raw);
        if (t.isEmpty()) return null;
        for (Trigger tr : items) {
            if (!tr.enabled) continue;
            for (String p : tr.phrases) if (matches(t, p)) return tr;
        }
        return null;
    }

    /** Lower case, punctuation removed, leading wake word removed: the form phrases are compared in. */
    public static String commandText(String raw) {
        String t = DirectCommands.normalize(raw == null ? "" : raw);
        String w = WAKE.matcher(t).replaceFirst("");
        return w.split(" ").length > MAX_WORDS ? "" : w;
    }

    public static boolean matches(String commandText, String phrase) {
        String p = DirectCommands.normalize(phrase);
        if (p.isEmpty() || commandText == null || commandText.isEmpty()) return false;
        return commandText.equals(p) || commandText.startsWith(p + " ");
    }

    /** Null when the action can run; otherwise the reason in Russian (shown to the user or to the model). */
    public static String checkAction(String action, Set<String> toolNames) {
        if (MORNING.equals(action) || PLAY_FAVORITE.equals(action)) return null;
        if (APP_OPEN.matcher(action).matches()) return null;
        java.util.regex.Matcher m = TOOL_CALL.matcher(action);
        if (m.matches()) {
            if (toolNames != null && !toolNames.contains(m.group(1))) return "Нет инструмента «" + m.group(1) + "»";
            try { new JSONObject(m.group(2)); } catch (Exception e) { return "Аргументы инструмента — не JSON-объект"; }
            return null;
        }
        return "Неизвестное действие «" + action + "». Можно: morning, music.playFavorite, app.open:<раздел>, tools.call:<имя>:<json>";
    }

    /** «включи радио|радио» (or commas, semicolons, new lines) → trimmed, distinct, non-empty phrases. */
    public static List<String> splitPhrases(String raw) {
        List<String> out = new ArrayList<>();
        for (String p : raw.split("[|,;\\n]")) {
            String s = p.trim();
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    /** The morning greeting that used to be hard-coded: the first trigger that shipped with the app. */
    public static Trigger morningPreset() {
        return new Trigger("morning", java.util.Arrays.asList("я проснулся", "я проснулась", "я встал", "я встала", "доброе утро",
            "с добрым утром", "папочка проснулся", "папа проснулся"), Mode.DIRECT, MORNING, "", true);
    }

    /** Reads triggers.json; when the file does not exist yet, writes the preset set and returns it. */
    public static Triggers load(File file) {
        if (!file.isFile()) {
            Triggers d = new Triggers(Collections.singletonList(morningPreset()));
            try { d.save(file); } catch (IOException ignored) { }
            return d;
        }
        List<Trigger> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(read(file));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(Trigger.fromJson(o));
            }
        } catch (Exception broken) {
            return new Triggers(Collections.<Trigger>emptyList());
        }
        return new Triggers(out);
    }

    public void save(File file) throws IOException {
        JSONArray arr = new JSONArray();
        for (Trigger t : items) arr.put(t.toJson());
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(arr.toString());
        }
        if (!tmp.renameTo(file)) throw new IOException("Не удалось сохранить triggers.json");
    }

    /** Adds or replaces the trigger with the same id; a new id is generated when blank. Returns the saved trigger. */
    public Trigger upsert(String id, List<String> phrases, Mode mode, String action, String instruction, boolean enabled) {
        String use = id == null || id.isEmpty() ? "t" + Long.toString(System.currentTimeMillis(), 36) : id;
        Trigger t = new Trigger(use, phrases, mode, action, instruction, enabled);
        for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(use)) { items.set(i, t); return t; }
        items.add(t);
        return t;
    }

    public boolean remove(String id) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(id)) { items.remove(i); return true; }
        return false;
    }


    private static String read(File f) throws IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
