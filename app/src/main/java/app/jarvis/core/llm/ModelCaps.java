package app.jarvis.core.llm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What a model can do, as reported by a catalog. Zero and {@link Cap#UNKNOWN} mean "not reported"; the UI shows them as unknown.
 * Fields are filled from the most authoritative source first, see {@link #fill(ModelCaps)} and docs/MODELS.md.
 */
public final class ModelCaps {
    /** Where the value came from. {@link #key} is the name shown in the UI and stored in files. */
    public enum Source {
        PROVIDER("provider"), OPENROUTER("openrouter-catalog"), BUILTIN("builtin"), LEARNED("learned"), UNKNOWN("unknown");

        public final String key;
        Source(String key) { this.key = key; }
    }

    public static final String LEVEL_OFF = "off";
    /** Levels the app can send; the provider decides which of them it accepts (see {@link #reasoningLevels}). */
    public static final List<String> LEVELS = Collections.unmodifiableList(Arrays.asList("off", "instant", "low", "medium", "high", "max"));

    public final String id;
    /** Context window in tokens; 0 = not reported. */
    public int contextTokens;
    /** Largest completion the provider accepts; 0 = not reported. */
    public int maxOutputTokens;
    public Cap tools = Cap.UNKNOWN, temperature = Cap.UNKNOWN, reasoning = Cap.UNKNOWN;
    public Cap vision = Cap.UNKNOWN, audioIn = Cap.UNKNOWN, audioOut = Cap.UNKNOWN;
    /** Reasoning levels the provider lists for this model; empty = not listed. */
    public final List<String> reasoningLevels = new ArrayList<>();
    /** USD per one million tokens; null = not reported. */
    public Double priceInPerM, priceOutPerM;
    public Source source = Source.UNKNOWN;
    public long fetchedAt;
    /** Request parameters the provider refused for this model (see {@link ParamMemory}); filled at lookup time, not stored here. */
    public final java.util.Set<String> rejectedParams = new java.util.TreeSet<>();

    public ModelCaps(String id) { this.id = id == null ? "" : id; }

    public static ModelCaps unknown(String id) { return new ModelCaps(id); }

    public ModelCaps copy() {
        ModelCaps c = new ModelCaps(id);
        c.contextTokens = contextTokens; c.maxOutputTokens = maxOutputTokens;
        c.tools = tools; c.temperature = temperature; c.reasoning = reasoning;
        c.vision = vision; c.audioIn = audioIn; c.audioOut = audioOut;
        c.reasoningLevels.addAll(reasoningLevels);
        c.priceInPerM = priceInPerM; c.priceOutPerM = priceOutPerM;
        c.source = source; c.fetchedAt = fetchedAt;
        c.rejectedParams.addAll(rejectedParams);
        return c;
    }

    /** A copy of this record where every unknown field is taken from {@code lower}; the source stays that of the first known field. */
    public ModelCaps fill(ModelCaps lower) {
        if (lower == null) return copy();
        ModelCaps c = copy();
        if (c.contextTokens <= 0) c.contextTokens = lower.contextTokens;
        if (c.maxOutputTokens <= 0) c.maxOutputTokens = lower.maxOutputTokens;
        c.tools = c.tools.or(lower.tools);
        c.temperature = c.temperature.or(lower.temperature);
        c.reasoning = c.reasoning.or(lower.reasoning);
        c.vision = c.vision.or(lower.vision);
        c.audioIn = c.audioIn.or(lower.audioIn);
        c.audioOut = c.audioOut.or(lower.audioOut);
        if (c.reasoningLevels.isEmpty()) c.reasoningLevels.addAll(lower.reasoningLevels);
        if (c.priceInPerM == null) c.priceInPerM = lower.priceInPerM;
        if (c.priceOutPerM == null) c.priceOutPerM = lower.priceOutPerM;
        if (!hasData()) c.source = lower.source;
        c.fetchedAt = Math.max(fetchedAt, lower.fetchedAt);
        return c;
    }

    /** True when at least one field carries a value; such a record is labelled with its source. */
    public boolean hasData() {
        return contextTokens > 0 || maxOutputTokens > 0 || tools != Cap.UNKNOWN || temperature != Cap.UNKNOWN || reasoning != Cap.UNKNOWN
            || vision != Cap.UNKNOWN || audioIn != Cap.UNKNOWN || audioOut != Cap.UNKNOWN || !reasoningLevels.isEmpty()
            || priceInPerM != null || priceOutPerM != null;
    }

    /**
     * Whether the model accepts this reasoning level. Empty string = provider default ("auto"), always allowed.
     * Levels the provider lists are exact; for a model without a list only "off" on a non-reasoning model is certain.
     */
    public Cap reasoningLevel(String level) {
        if (level == null || level.isEmpty()) return Cap.YES;
        if (reasoning == Cap.NO) return level.equals(LEVEL_OFF) ? Cap.YES : Cap.NO;
        if (!reasoningLevels.isEmpty()) {
            if (reasoningLevels.contains(level)) return Cap.YES;
            return level.equals(LEVEL_OFF) ? Cap.UNKNOWN : Cap.NO;
        }
        return Cap.UNKNOWN;
    }

    /** Models whose only interface is realtime, audio or speech: they are not chat models for this app. */
    public boolean realtimeOnly() { return isRealtimeId(id); }

    public static boolean isRealtimeId(String id) {
        String l = id == null ? "" : id.toLowerCase(Locale.ROOT);
        return l.contains("realtime") || l.contains("audio") || l.contains("voice") || l.contains("tts") || l.contains("stt");
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        put(o, "id", id);
        put(o, "ctx", contextTokens);
        put(o, "out", maxOutputTokens);
        put(o, "tools", tools.name());
        put(o, "temp", temperature.name());
        put(o, "reasoning", reasoning.name());
        put(o, "vision", vision.name());
        put(o, "audioIn", audioIn.name());
        put(o, "audioOut", audioOut.name());
        JSONArray lv = new JSONArray();
        for (String s : reasoningLevels) lv.put(s);
        put(o, "levels", lv);
        if (priceInPerM != null) put(o, "priceIn", priceInPerM);
        if (priceOutPerM != null) put(o, "priceOut", priceOutPerM);
        put(o, "source", source.name());
        put(o, "fetchedAt", fetchedAt);
        return o;
    }

    public static ModelCaps fromJson(JSONObject o) {
        ModelCaps c = new ModelCaps(o.optString("id", ""));
        c.contextTokens = o.optInt("ctx", 0);
        c.maxOutputTokens = o.optInt("out", 0);
        c.tools = cap(o, "tools");
        c.temperature = cap(o, "temp");
        c.reasoning = cap(o, "reasoning");
        c.vision = cap(o, "vision");
        c.audioIn = cap(o, "audioIn");
        c.audioOut = cap(o, "audioOut");
        JSONArray lv = o.optJSONArray("levels");
        if (lv != null) for (int i = 0; i < lv.length(); i++) c.reasoningLevels.add(lv.optString(i));
        if (o.has("priceIn")) c.priceInPerM = o.optDouble("priceIn");
        if (o.has("priceOut")) c.priceOutPerM = o.optDouble("priceOut");
        c.source = source(o.optString("source", "UNKNOWN"));
        c.fetchedAt = o.optLong("fetchedAt", 0);
        return c;
    }

    private static Cap cap(JSONObject o, String k) {
        try { return Cap.valueOf(o.optString(k, "UNKNOWN")); } catch (IllegalArgumentException e) { return Cap.UNKNOWN; }
    }

    private static Source source(String name) {
        try { return Source.valueOf(name); } catch (IllegalArgumentException e) { return Source.UNKNOWN; }
    }

    private static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (org.json.JSONException ignored) { }
    }
}
