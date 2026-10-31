package app.jarvis.core.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Latency record of one turn (voice or text): named marks on a monotonic clock, attributes per stage and tool spans.
 * Thread-safe (hooks fire from the audio, STT, agent and player threads). Marks are "first wins" unless stated
 * otherwise, so a multi-step agent run reports the time of the FIRST request/token/sound; {@link #markLast} keeps the
 * latest occurrence (llm_done, play_end). Times in JSON are milliseconds relative to the earliest mark.
 */
public final class TurnTrace {
    /** Time source; the monotonic clock in production, a fake in tests. */
    public interface Clock {
        long nanos();
        long wallMs();
    }

    public static final Clock SYSTEM = new Clock() {
        @Override public long nanos() { return System.nanoTime(); }
        @Override public long wallMs() { return System.currentTimeMillis(); }
    };

    public static final String WAKE_DETECTED = "wake_detected";
    /** Estimated end of the user's voice: utterance delivery minus the endpoint silence. */
    public static final String SPEECH_END = "speech_end";
    /** The endpointer decided the utterance is over (SPEECH_END + endpoint silence). */
    public static final String SPEECH_END_DETECTED = "speech_end_detected";
    public static final String STT_START = "stt_start";
    public static final String STT_FINAL = "stt_final";
    public static final String SUBMIT = "submit";
    public static final String LLM_REQUEST = "llm_request";
    public static final String LLM_FIRST_TOKEN = "llm_first_token";
    public static final String LLM_FIRST_TEXT = "llm_first_text";
    public static final String LLM_DONE = "llm_done";
    /** First piece of text handed to the speech queue. */
    public static final String TTS_REQUEST = "tts_request";
    /** The queue worker started synthesizing that piece (differs from TTS_REQUEST when earlier audio still plays). */
    public static final String TTS_SYNTH_START = "tts_synth_start";
    public static final String TTS_FIRST_AUDIO = "tts_first_audio";
    public static final String PLAY_START = "play_start";
    public static final String PLAY_END = "play_end";
    public static final String AGENT_DONE = "agent_done";
    /** The quick acknowledgement ("Секунду.") became audible. */
    public static final String ACK_START = "ack_start";

    private static final class ToolSpan {
        String name;
        long startNanos, endNanos = -1;
        boolean ok;
    }

    public final long id;
    public final String source;
    public final long wallStartMs;
    private final Clock clock;
    private final Map<String, Long> marks = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
    private final List<ToolSpan> tools = new ArrayList<>();
    private boolean agentDone;
    private boolean finished;
    private boolean complete = true;

    public TurnTrace(long id, String source, Clock clock) {
        this.id = id;
        this.source = source == null ? "" : source;
        this.clock = clock == null ? SYSTEM : clock;
        this.wallStartMs = this.clock.wallMs();
    }

    // ------------------------------------------------------------------ recording

    public synchronized void mark(String name) { markAt(name, clock.nanos()); }

    /** First occurrence wins. */
    public synchronized void markAt(String name, long nanos) {
        if (!marks.containsKey(name)) marks.put(name, nanos);
    }

    /** Latest occurrence wins. */
    public synchronized void markLast(String name) { marks.put(name, clock.nanos()); }

    public synchronized void markLastAt(String name, long nanos) { marks.put(name, nanos); }

    public long now() { return clock.nanos(); }

    public synchronized boolean has(String name) { return marks.containsKey(name); }

    /** Sets a stage attribute, e.g. put("stt", "provider", "google"). */
    public synchronized void put(String group, String key, Object value) {
        Map<String, Object> g = groups.get(group);
        if (g == null) { g = new LinkedHashMap<>(); groups.put(group, g); }
        g.put(key, value);
    }

    /** Sets the attribute only when it is not set yet. */
    public synchronized void putIfAbsent(String group, String key, Object value) {
        Map<String, Object> g = groups.get(group);
        if (g == null || !g.containsKey(key)) put(group, key, value);
    }

    public synchronized void max(String group, String key, long value) {
        Map<String, Object> g = groups.get(group);
        Object old = g == null ? null : g.get(key);
        if (!(old instanceof Number) || ((Number) old).longValue() < value) put(group, key, value);
    }

    public synchronized void add(String group, String key, long delta) {
        Map<String, Object> g = groups.get(group);
        Object old = g == null ? null : g.get(key);
        put(group, key, (old instanceof Number ? ((Number) old).longValue() : 0L) + delta);
    }

    /** Returns a handle for {@link #toolEnd}. */
    public synchronized int toolStart(String name) {
        ToolSpan s = new ToolSpan();
        s.name = name;
        s.startNanos = clock.nanos();
        tools.add(s);
        return tools.size() - 1;
    }

    public synchronized void toolEnd(int handle, boolean ok) {
        if (handle < 0 || handle >= tools.size()) return;
        ToolSpan s = tools.get(handle);
        if (s.endNanos < 0) { s.endNanos = clock.nanos(); s.ok = ok; }
    }

    synchronized void setAgentDone() {
        agentDone = true;
        markLast(AGENT_DONE);
    }

    synchronized boolean isAgentDone() { return agentDone; }

    synchronized boolean markFinished(boolean complete) {
        if (finished) return false;
        finished = true;
        this.complete = complete;
        return true;
    }

    public synchronized boolean isFinished() { return finished; }

    // ------------------------------------------------------------------ reading

    /** Milliseconds from mark a to mark b, or -1 when either is missing. */
    public synchronized long span(String a, String b) {
        Long x = marks.get(a), y = marks.get(b);
        return x == null || y == null ? -1 : Math.round((y - x) / 1e6);
    }

    private long origin() {
        long o = Long.MAX_VALUE;
        for (long v : marks.values()) o = Math.min(o, v);
        for (ToolSpan t : tools) o = Math.min(o, t.startNanos);
        return o == Long.MAX_VALUE ? 0 : o;
    }

    /** The moment the user could first hear (or, with speech off, read) the answer. */
    private String endMark() {
        if (marks.containsKey(PLAY_START)) return PLAY_START;
        if (marks.containsKey(LLM_FIRST_TEXT)) return LLM_FIRST_TEXT;
        if (marks.containsKey(AGENT_DONE)) return AGENT_DONE;
        return null;
    }

    private String startMark() {
        if (marks.containsKey(SPEECH_END)) return SPEECH_END;
        if (marks.containsKey(SUBMIT)) return SUBMIT;
        if (marks.containsKey(LLM_REQUEST)) return LLM_REQUEST;
        return null;
    }

    /** Derived spans in ms (only the ones whose both marks exist). */
    public synchronized Map<String, Long> spans() {
        Map<String, Long> m = new LinkedHashMap<>();
        putSpan(m, "endpoint", SPEECH_END, SPEECH_END_DETECTED);
        putSpan(m, "stt", STT_START, STT_FINAL);
        putSpan(m, "stt_to_llm", STT_FINAL, LLM_REQUEST);
        putSpan(m, "llm_ttft", LLM_REQUEST, LLM_FIRST_TOKEN);
        putSpan(m, "llm_first_text", LLM_REQUEST, LLM_FIRST_TEXT);
        putSpan(m, "llm_total", LLM_REQUEST, LLM_DONE);
        putSpan(m, "tts_queue", TTS_REQUEST, TTS_SYNTH_START);
        putSpan(m, "tts_ttfa", TTS_SYNTH_START, TTS_FIRST_AUDIO);
        putSpan(m, "tts_total", TTS_REQUEST, TTS_FIRST_AUDIO);
        putSpan(m, "play_gap", TTS_FIRST_AUDIO, PLAY_START);
        putSpan(m, "text_to_tts", LLM_FIRST_TEXT, TTS_REQUEST);
        { String s0 = startMark(); if (s0 != null && marks.containsKey(ACK_START)) m.put("to_ack", span(s0, ACK_START)); }
        String s = startMark(), e = endMark();
        if (s != null && e != null && !s.equals(e)) m.put("to_sound", span(s, e));
        Long a = marks.get(s == null ? "" : s), z = marks.get(PLAY_END);
        if (a == null) a = marks.isEmpty() ? null : origin();
        if (a != null) {
            long last = z != null ? z : marks.containsKey(AGENT_DONE) ? marks.get(AGENT_DONE) : a;
            m.put("total", Math.round((last - a) / 1e6));
        }
        long toolsMs = 0;
        for (ToolSpan t : tools) if (t.endNanos >= 0) toolsMs += Math.round((t.endNanos - t.startNanos) / 1e6);
        if (!tools.isEmpty()) m.put("tools", toolsMs);
        return m;
    }

    private void putSpan(Map<String, Long> m, String name, String a, String b) {
        long v = span(a, b);
        if (v >= 0) m.put(name, v);
    }

    /** One-line Russian summary for the journal: "Ход 2,3 с до звука: STT 0,9 | LLM 0,7 | TTS 0,5". */
    public synchronized String summaryRu() {
        Map<String, Long> sp = spans();
        Long toSound = sp.get("to_sound");
        boolean sound = marks.containsKey(PLAY_START);
        StringBuilder sb = new StringBuilder("Ход ");
        if (toSound == null) {
            sb.append("без измеримого ответа");
            Long total = sp.get("total");
            if (total != null && total > 0) sb.append(" (").append(sec(total)).append(" с)");
            return sb.toString();
        }
        sb.append(sec(toSound)).append(sound ? " с до звука" : " с до ответа");
        List<String> parts = new ArrayList<>();
        long accounted = 0;
        Long stt = sp.get("stt");
        if (stt != null) { parts.add("STT " + sec(stt)); accounted += stt; }
        Long llm = sp.containsKey("llm_first_text") ? sp.get("llm_first_text") : sp.get("llm_ttft");
        if (llm != null) { parts.add("LLM " + sec(llm)); accounted += llm; }
        Long tts = sound ? sp.get("tts_total") : null;
        if (tts != null) { parts.add("TTS " + sec(tts)); accounted += tts; }
        Long gap = sound ? sp.get("play_gap") : null;
        if (gap != null && gap >= 150) { parts.add("плеер " + sec(gap)); accounted += gap; }
        Long ep = sp.get("endpoint");
        if (ep != null && ep > 0 && marks.containsKey(SPEECH_END)) { parts.add("пауза " + sec(ep)); accounted += ep; }
        long rest = toSound - accounted;
        if (rest >= 300 && !parts.isEmpty()) parts.add("прочее " + sec(rest));
        for (int i = 0; i < parts.size(); i++) sb.append(i == 0 ? ": " : " | ").append(parts.get(i));
        return sb.toString();
    }

    private static String sec(long ms) { return String.format(Locale.US, "%.1f", ms / 1000.0).replace('.', ','); }

    public synchronized JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("source", source);
            o.put("start_ms", wallStartMs);
            o.put("complete", complete);
            long org = origin();
            JSONObject mk = new JSONObject();
            for (Map.Entry<String, Long> e : marks.entrySet()) mk.put(e.getKey(), Math.round((e.getValue() - org) / 1e6));
            o.put("marks", mk);
            JSONObject sp = new JSONObject();
            for (Map.Entry<String, Long> e : spans().entrySet()) sp.put(e.getKey(), e.getValue());
            o.put("spans", sp);
            for (Map.Entry<String, Map<String, Object>> g : groups.entrySet()) {
                JSONObject go = new JSONObject();
                for (Map.Entry<String, Object> e : g.getValue().entrySet()) go.put(e.getKey(), e.getValue());
                o.put(g.getKey(), go);
            }
            JSONArray ta = new JSONArray();
            for (ToolSpan t : tools) {
                JSONObject to = new JSONObject();
                to.put("name", t.name);
                to.put("start", Math.round((t.startNanos - org) / 1e6));
                to.put("ms", t.endNanos < 0 ? -1 : Math.round((t.endNanos - t.startNanos) / 1e6));
                to.put("ok", t.ok);
                ta.put(to);
            }
            o.put("tools", ta);
            o.put("summary", summaryRu());
        } catch (org.json.JSONException ignored) { }
        return o;
    }
}
