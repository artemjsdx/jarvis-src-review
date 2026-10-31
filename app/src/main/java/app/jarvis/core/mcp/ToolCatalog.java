package app.jarvis.core.mcp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The names of every tool the Android bridge registers (docs/MCP.md section 3), kept as plain data so JVM tests can check
 * capabilities and scenarios against it. A source-scan test keeps this list in sync with app/mcp/*.java.
 */
public final class ToolCatalog {
    private ToolCatalog() { }

    /** Fully working tools. */
    public static final List<String> IMPLEMENTED = Collections.unmodifiableList(Arrays.asList(
        "app_info", "app_health", "app_capabilities", "app_quit",
        "journal_tail", "journal_mark", "journal_clear",
        "settings_list", "settings_get", "settings_set", "settings_reset",
        "secrets_list", "secrets_set", "secrets_get",
        "db_schema", "db_query", "db_exec",
        "chat_sessions", "chat_new", "chat_send", "chat_history", "chat_clear_context", "chat_cancel", "chat_wait_idle", "chat_state", "chat_delete", "chat_seed",
        "agent_tools_list", "agent_tool_call", "agent_prompt_dump", "trace_recent",
        "llm_config", "llm_models", "llm_ping", "llm_set",
        "files_list", "files_read", "files_write", "shell_exec", "logcat_tail",
        "perf_snapshot", "perf_turns", "perf_prompt_size", "perf_frames",
        "sessions_list", "sessions_search", "sessions_create", "sessions_select", "sessions_rename", "sessions_pin", "sessions_archive",
        "sessions_delete", "sessions_clear_context",
        "adb_status", "adb_pair", "adb_connect", "adb_shell",
        "net_probe", "net_throttle", "net_state", "trace_current", "perf_summary",
        "ui_hosts", "ui_host_open", "ui_screens", "ui_navigate", "ui_tree", "ui_screenshot", "ui_tap", "ui_long_press", "ui_swipe",
        "ui_type", "ui_key", "ui_wait", "ui_anim_probe", "ui_theme",
        "voice_state", "voice_speak", "voice_stt_file", "voice_inject", "tts_cache", "wake_state", "wake_arm", "mic_level"));

    /** Registered so the catalog is complete, but they answer NOT_IMPLEMENTED until the owning feature lands. */
    public static final List<String> STUBS = Collections.<String>emptyList();

    public static List<String> all() {
        List<String> l = new ArrayList<>(IMPLEMENTED);
        l.addAll(STUBS);
        return l;
    }

    public static Set<String> allSet() { return new LinkedHashSet<>(all()); }
}
