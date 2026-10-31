package app.jarvis.core.agent;

import app.jarvis.core.llm.ToolSpec;
import app.jarvis.core.util.J;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The tools announced to the model during ONE agent run: core + the switched-on {@link ToolGroups groups} + the meta tool that
 * switches more on. The set only grows during a run (a group switched on stays on), and the agent loop asks for the list again
 * before every model step, so a group switched on in step 1 is announced from step 2.
 * <p>
 * Thread-safe: tool calls of one step run on several threads.
 */
public final class ToolSelection {
    private final ToolRegistry registry;
    private final Set<String> enabled = new LinkedHashSet<>();

    public ToolSelection(ToolRegistry registry, Collection<String> groups) {
        this.registry = registry;
        if (groups != null) for (String g : groups) if (ToolGroups.isGroup(g)) enabled.add(g);
    }

    /** Switches a group on; false when it already was (or the id is unknown). */
    public synchronized boolean enable(String group) { return ToolGroups.isGroup(group) && enabled.add(group); }

    /** Switched-on groups in the fixed group order. */
    public synchronized List<String> enabledGroups() {
        List<String> r = new ArrayList<>();
        for (String g : ToolGroups.ids()) if (enabled.contains(g)) r.add(g);
        return r;
    }

    /** The tools for the next model request: every core tool, every tool of a switched-on group, then the meta tool if anything is still hidden. */
    public List<ToolSpec> advertise() {
        Set<String> on;
        synchronized (this) { on = new HashSet<>(enabled); }
        List<ToolSpec> all = registry.specs();
        List<ToolSpec> out = new ArrayList<>(all.size());
        Set<String> hidden = new HashSet<>();
        for (ToolSpec s : all) {
            String g = ToolGroups.groupOf(s.name);
            if (g == null || on.contains(g)) out.add(s);
            else hidden.add(g);
        }
        if (!hidden.isEmpty()) out.add(metaSpec(ordered(hidden)));
        return out;
    }

    /** Number of tools announced by {@link #advertise()}, meta tool included. */
    public int advertisedCount() { return advertise().size(); }

    /** Groups that still have at least one registered tool hidden, in group order. */
    public List<String> hiddenGroups() {
        Set<String> on;
        synchronized (this) { on = new HashSet<>(enabled); }
        Set<String> hidden = new HashSet<>();
        for (String name : registry.names()) {
            String g = ToolGroups.groupOf(name);
            if (g != null && !on.contains(g)) hidden.add(g);
        }
        return ordered(hidden);
    }

    /** A tool was called (by the model, whether or not it was announced): its group, and the groups it implies, are useful from now on. */
    public void noteCalled(String tool) {
        if (!registry.has(tool)) return;
        for (String g : ToolGroups.groupsForUse(tool)) enable(g);
    }

    /** Answer to a call of the meta tool. Never throws; a wrong argument is a normal failed result the model can correct. */
    public ToolResult handleEnable(JSONObject args) {
        if (args == null) return ToolResult.fail("Аргументы не JSON. Пример: {\"groups\":[\"music\"]}");
        List<String> want = parseGroups(args);
        List<String> available = groupsWithTools();
        if (available.isEmpty()) return ToolResult.fail("Скрытых инструментов нет: всё уже в списке.");
        if (want.isEmpty()) return ToolResult.fail("Не указаны группы. Доступные: " + available + " или \"" + ToolGroups.ALL + "\".");
        List<String> added = new ArrayList<>(), already = new ArrayList<>(), unknown = new ArrayList<>();
        for (String w : want) {
            if (ToolGroups.ALL.equals(w)) {
                for (String g : available) (enable(g) ? added : already).add(g);
            } else if (!ToolGroups.isGroup(w) || !available.contains(w)) {
                unknown.add(w);
            } else {
                (enable(w) ? added : already).add(w);
            }
        }
        JSONObject d = new JSONObject();
        JSONArray tools = new JSONArray();
        for (String g : added) for (String t : ToolGroups.toolsOf(g)) if (registry.has(t)) tools.put(t);
        J.put(d, "enabled", new JSONArray(added));
        J.put(d, "already_enabled", new JSONArray(already));
        J.put(d, "tools_now_available", tools);
        if (!unknown.isEmpty()) J.put(d, "unknown_groups", new JSONArray(unknown));
        if (added.isEmpty() && already.isEmpty()) return ToolResult.fail("Нет таких групп: " + unknown + ". Доступные: " + available + " или \"" + ToolGroups.ALL + "\".", d);
        String summary = added.isEmpty() ? "уже подключено: " + already : "подключено: " + added + (unknown.isEmpty() ? "" : "; нет таких групп: " + unknown);
        J.put(d, "next", "Эти инструменты доступны со следующего шага: вызови нужный.");
        return ToolResult.ok(d, summary);
    }

    /** Groups that have at least one tool registered in this installation (Termux or ADB tools may be absent). */
    private List<String> groupsWithTools() {
        Set<String> have = new HashSet<>();
        for (String name : registry.names()) {
            String g = ToolGroups.groupOf(name);
            if (g != null) have.add(g);
        }
        return ordered(have);
    }

    private static List<String> ordered(Set<String> groups) {
        List<String> r = new ArrayList<>();
        for (String g : ToolGroups.ids()) if (groups.contains(g)) r.add(g);
        return r;
    }

    /** Accepts {@code groups} as an array or as a string ("music, files"); {@code group} is accepted as a synonym because models mix them up. */
    static List<String> parseGroups(JSONObject args) {
        Object o = args.opt("groups");
        if (o == null || o == JSONObject.NULL) o = args.opt("group");
        List<String> r = new ArrayList<>();
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) add(r, String.valueOf(a.opt(i)));
        } else if (o instanceof String) {
            for (String p : ((String) o).split("[,;\\s]+")) add(r, p);
        }
        return r;
    }

    private static void add(List<String> r, String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!s.isEmpty() && !r.contains(s)) r.add(s);
    }

    /** The schema of the meta tool; it names only the groups that are still hidden, so the text shrinks as groups are switched on. */
    static ToolSpec metaSpec(List<String> hidden) {
        StringBuilder d = new StringBuilder(260);
        d.append("Подключает скрытые инструменты. Если для просьбы нужного нет в списке, вызови это с нужной группой и сразу используй его. Группы: ");
        for (int i = 0; i < hidden.size(); i++) {
            if (i > 0) d.append("; ");
            d.append(hidden.get(i)).append(" — ").append(ToolGroups.title(hidden.get(i)));
        }
        if (hidden.size() > 1) d.append("; ").append(ToolGroups.ALL).append(" — все сразу");
        d.append('.');
        JSONObject schema = ToolSpec.schema();
        JSONObject props = J.obj(schema, "properties");
        JSONObject groups = new JSONObject();
        J.put(groups, "type", "array");
        JSONObject items = new JSONObject();
        J.put(items, "type", "string");
        JSONArray en = new JSONArray();
        for (String g : hidden) en.put(g);
        if (hidden.size() > 1) en.put(ToolGroups.ALL);
        J.put(items, "enum", en);
        J.put(groups, "items", items);
        J.put(props, "groups", groups);
        J.arr(schema, "required").put("groups");
        return new ToolSpec(ToolGroups.META_TOOL, d.toString(), schema);
    }
}
