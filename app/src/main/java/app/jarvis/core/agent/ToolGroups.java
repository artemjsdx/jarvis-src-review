package app.jarvis.core.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which tools the model is shown on every request ("core") and which only when their group is switched on.
 * <p>
 * Every advertised schema is paid for on EVERY request, and a long prompt is the biggest part of the wait for the first token:
 * all 47 built-in schemas are about 7 thousand tokens (measured, docs/SPEED.md 8a), the core below about 1.4 thousand.
 * A group is switched on by {@link ToolRouter} (words of the request, tools used in the last turns) or by the model itself with
 * the meta tool {@link #META_TOOL}, so no function is removed, it is only not announced until it is likely to be needed.
 * <p>
 * Rules that keep this safe:
 * <ul>
 * <li>a tool that is in no group is core: a new built-in tool and every external MCP tool stay visible until someone decides otherwise
 * ({@code ToolGroupsTest} fails when a built-in tool is neither in a group nor in {@link #CORE_BUILTIN});</li>
 * <li>a call to a tool of a switched-off group still runs (the registry knows every tool) and switches its group on;</li>
 * <li>nothing here changes which tools exist, only what is announced.</li>
 * </ul>
 */
public final class ToolGroups {
    private ToolGroups() { }

    /** The meta tool: the model asks for hidden groups by id. It is not in the registry; the agent loop answers it itself. */
    public static final String META_TOOL = "tools_enable";
    /** Pseudo group id for {@link #META_TOOL}: every group at once (the safe escape hatch). */
    public static final String ALL = "all";

    public static final String MUSIC = "music", FILES = "files", WEB = "web", SHELL = "shell", ADB = "adb",
        MEMORY = "memory", SKILLS = "skills", SYSTEM = "system", HISTORY = "history";

    private static final Map<String, String> TITLE = new LinkedHashMap<>();        // group id -> what is inside (Russian, for the meta tool)
    private static final Map<String, List<String>> TOOLS = new LinkedHashMap<>();  // group id -> tool names
    private static final Map<String, String> GROUP_OF = new HashMap<>();           // tool name -> group id

    private static void def(String id, String title, String... tools) {
        TITLE.put(id, title);
        TOOLS.put(id, Collections.unmodifiableList(Arrays.asList(tools)));
        for (String t : tools) GROUP_OF.put(t, id);
    }

    static {
        def(MUSIC, "плеер",
            "music_list", "music_play", "music_play_latest_received", "music_pause", "music_resume", "music_stop", "music_next", "music_prev",
            "music_status", "music_set_favorite");
        def(FILES, "файлы и папки", "fs_list", "fs_read", "fs_write", "fs_mkdir", "fs_move", "fs_delete", "fs_stat");
        def(WEB, "страницы по ссылке, документация, скачивание", "web_fetch", "docs_search", "http_fetch", "download");
        def(SHELL, "терминал", "shell_exec", "termux_exec");
        def(ADB, "ADB: другие приложения, экран, звук, звонки", "adb_status", "adb_pair", "adb_connect", "adb_shell", "adb_disconnect");
        def(MEMORY, "правка и удаление памяти", "memory_replace", "memory_pin", "memory_delete");
        def(SKILLS, "навыки и триггеры", "skill_manage", "trigger_save");
        def(SYSTEM, "микрофон, режим обращения, настройки", "app_listen", "app_wake", "app_setting");
        def(HISTORY, "поиск по прошлым чатам", "session_search");
    }

    /**
     * Built-in tools that are announced on every request ON PURPOSE: the need for them cannot be read from the words of a request
     * (memory and skills are used on the model's own initiative, a web search is the answer to any fresh-facts question), or they
     * are tiny and constantly needed (stop talking, open a screen, app state, a quick note).
     */
    public static final List<String> CORE_BUILTIN = Collections.unmodifiableList(Arrays.asList(
        "app_open", "app_state", "speech_stop", "note_create", "dev_report", "memory_save", "memory_get", "memory_search", "skill_view", "skills_list", "web_search"));

    /** Using a core tool can imply a group: after a web search the model usually wants to read one of the pages. */
    private static final Map<String, String> FOLLOW_UP = new HashMap<>();
    static { FOLLOW_UP.put("web_search", WEB); }

    /** Group ids in the fixed order used everywhere (descriptions, logs): the order keeps the announced tool list stable. */
    public static List<String> ids() { return Collections.unmodifiableList(new ArrayList<>(TOOLS.keySet())); }

    public static boolean isGroup(String id) { return id != null && TOOLS.containsKey(id); }

    /** Group of a tool, or null when the tool is core (always announced), unknown, or external. */
    public static String groupOf(String tool) { return tool == null ? null : GROUP_OF.get(tool); }

    public static List<String> toolsOf(String group) {
        List<String> l = TOOLS.get(group);
        return l == null ? Collections.<String>emptyList() : l;
    }

    public static String title(String group) {
        String t = TITLE.get(group);
        return t == null ? group : t;
    }

    /** Groups that a call of this tool makes useful: its own group plus the follow-up group, if any. */
    public static List<String> groupsForUse(String tool) {
        List<String> r = new ArrayList<>(2);
        String g = groupOf(tool);
        if (g != null) r.add(g);
        String f = FOLLOW_UP.get(tool);
        if (f != null && !r.contains(f)) r.add(f);
        return r;
    }

    /** True when somebody has decided about this built-in tool: it is in a group or listed as core on purpose. */
    public static boolean isDecided(String tool) { return GROUP_OF.containsKey(tool) || CORE_BUILTIN.contains(tool); }
}
