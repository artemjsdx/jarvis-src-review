package app.jarvis.core.mcp;

import app.jarvis.core.util.J;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Registry of product features and the MCP hooks that make each one testable by an agent (rule "no hook, no readiness", M-06).
 * A feature is either covered (its tools exist and tests/mcp/&lt;id&gt;.json exercises it) or explicitly planned. CapabilitiesTest enforces this.
 * Every new feature from the backlog adds one line here in the same commit as its code.
 */
public final class Capabilities {
    private Capabilities() { }

    public static final class Feature {
        public final String id, title, note;
        public final List<String> tools;
        public final boolean planned;

        Feature(String id, String title, boolean planned, String note, String... tools) {
            this.id = id; this.title = title; this.planned = planned; this.note = note; this.tools = Collections.unmodifiableList(Arrays.asList(tools));
        }

        /** Scenario file relative to the repository root. */
        public String scenarioPath() { return "tests/mcp/" + id + ".json"; }
    }

    private static Feature cov(String id, String title, String... tools) { return new Feature(id, title, false, "", tools); }

    private static Feature plan(String id, String title, String note, String... tools) { return new Feature(id, title, true, note, tools); }

    private static final List<Feature> ALL = Collections.unmodifiableList(Arrays.asList(
        cov("mcp_bridge", "Мост MCP: состояние, реестр функций, аудит", "app_info", "app_capabilities", "app_quit", "journal_tail"),
        cov("service_lifecycle", "Процесс, службы, память, батарея, сеть", "app_info", "app_health", "perf_snapshot", "logcat_tail"),
        cov("chat", "Чат: сообщение, ответ, история, отмена", "chat_new", "chat_send", "chat_history", "chat_state", "chat_wait_idle", "chat_cancel", "chat_delete"),
        cov("ci_chat_screens", "Экраны чата для скриншотов: засев состояний, меню сессий, вложения, светлая тема", "chat_seed", "ui_screenshot", "ui_tap", "ui_theme", "ui_key"),
        cov("sessions", "Сессии чата: список, поиск, создание, выбор, переименование, закрепление, архив, удаление, очистка контекста", "chat_sessions", "chat_new", "chat_clear_context", "sessions_list", "sessions_search", "sessions_create", "sessions_select", "sessions_rename", "sessions_pin", "sessions_archive", "sessions_delete", "sessions_clear_context"),
        cov("agent_loop", "Цикл агента: промпт, инструменты, трасса хода", "chat_send", "agent_prompt_dump", "agent_tools_list", "trace_recent", "perf_turns", "perf_prompt_size"),
        cov("perf_trace", "Трассировка хода (P-01): водопад, медианы и p90 по голосу и тексту", "trace_recent", "perf_turns", "perf_summary", "trace_current"),
        cov("tools_fs", "Файловые инструменты агента", "agent_tool_call", "files_list", "files_read", "files_write"),
        cov("tools_shell", "Терминал агента", "agent_tool_call", "shell_exec"),
        cov("tools_http", "Загрузка страниц агентом", "agent_tool_call"),
        cov("tools_download", "Скачивание файлов агентом", "agent_tool_call"),
        cov("tools_memory", "Память ассистента", "agent_tool_call"),
        cov("tools_music", "Музыка (состояние плеера)", "agent_tool_call"),
        cov("tools_apps", "Состояние приложения и настройки голосом", "agent_tool_call"),
        plan("tools_notes", "Заметки", "note_create пишет в пользовательское хранилище; сценарий появится вместе с песочницей заметок", "agent_tool_call"),
        cov("tools_adb", "ADB по Wi-Fi внутри приложения", "adb_status", "adb_shell", "adb_connect", "adb_pair"),
        cov("llm_providers", "Провайдеры LLM: настройка, список моделей, пинг", "llm_config", "llm_models", "llm_ping", "llm_set"),
        cov("tts_edge", "Озвучка Edge (сценарий только на телефоне: нужен интернет)", "voice_speak"),
        cov("tts_elevenlabs", "Озвучка ElevenLabs (сценарий только на телефоне: нужен ключ)", "voice_speak"),
        cov("tts_gtts", "Озвучка Google (сценарий только на телефоне: нужен интернет)", "voice_speak"),
        cov("tts_cache", "Кэш озвучки: статистика, список, очистка", "tts_cache", "voice_speak"),
        cov("stt_chain", "Цепочка распознавания речи и файлы WAV/MP3/OGG", "voice_stt_file"),
        cov("wake", "Обращение «Джарвис»: состояние, включение, инъекция фразы", "wake_state", "wake_arm", "voice_inject"),
        cov("voice_pipeline", "Голосовой конвейер целиком: инъекция, состояние, уровень микрофона", "voice_inject", "voice_state", "mic_level"),
        cov("net_conditions", "Имитация плохой сети и замеры сети", "net_probe", "net_throttle", "net_state"),
        cov("perf_frames", "Плавность кадров окна (jank, FrameMetrics)", "perf_frames"),
        cov("ui_host", "Окно для агента: хосты, жесты, клавиши, ожидание", "ui_hosts", "ui_host_open", "ui_screens", "ui_swipe", "ui_long_press", "ui_key", "ui_wait", "ui_tap"),
        cov("ui_home", "Главный экран", "ui_navigate", "ui_tree", "ui_screenshot"),
        cov("ui_agent", "Экран чата", "ui_navigate", "ui_tree", "ui_screenshot", "ui_type", "ui_tap"),
        cov("ui_files", "Экран файлов", "ui_navigate", "ui_tree"),
        cov("ui_terminal", "Экран терминала", "ui_navigate", "ui_tree"),
        cov("ui_voice", "Голосовой экран и студия", "ui_navigate", "ui_tree"),
        cov("ui_journal", "Экран журнала", "ui_navigate", "ui_tree"),
        cov("ui_settings", "Настройки и страницы настроек", "ui_navigate", "ui_tree", "ui_tap", "ui_screenshot"),
        cov("ui_motion", "Анимации интерфейса", "ui_anim_probe", "ui_theme"),
        cov("ui_headless", "Невидимый экран Jarvis: кадры на GPU, касания, снимки", "ui_host_open", "ui_hosts", "ui_navigate", "ui_tree",
            "ui_screenshot", "ui_tap", "ui_type", "ui_key", "ui_anim_probe"),
        cov("settings", "Настройки приложения", "settings_list", "settings_get", "settings_set", "settings_reset"),
        cov("secrets", "Хранилище ключей", "secrets_list", "secrets_set", "secrets_get"),
        cov("journal", "Журнал событий", "journal_tail", "journal_mark", "journal_clear"),
        cov("db_recovery", "База данных: схема, целостность, восстановление", "db_schema", "db_query", "db_exec")
    ));

    public static List<Feature> all() { return ALL; }

    public static Feature byId(String id) {
        for (Feature f : ALL) if (f.id.equals(id)) return f;
        return null;
    }

    /** Coverage report; {@code registered} are the tool names actually present in the running bridge. */
    public static JSONObject report(Set<String> registered) {
        JSONArray arr = new JSONArray();
        int covered = 0, planned = 0, broken = 0;
        for (Feature f : ALL) {
            List<String> missing = new ArrayList<>();
            for (String t : f.tools) if (!registered.contains(t)) missing.add(t);
            boolean stubOnly = false;
            for (String t : f.tools) if (ToolCatalog.STUBS.contains(t)) stubOnly = true;
            JSONObject o = new JSONObject();
            J.put(o, "id", f.id);
            J.put(o, "title", f.title);
            J.put(o, "status", f.planned ? "planned" : missing.isEmpty() ? "covered" : "broken");
            J.put(o, "tools", new JSONArray(f.tools));
            J.put(o, "scenario", f.scenarioPath());
            if (!missing.isEmpty()) J.put(o, "missingTools", new JSONArray(missing));
            if (!f.note.isEmpty()) J.put(o, "note", f.note);
            if (stubOnly) J.put(o, "usesStubTools", true);
            arr.put(o);
            if (f.planned) planned++; else if (missing.isEmpty()) covered++; else broken++;
        }
        JSONObject r = new JSONObject();
        J.put(r, "features", arr);
        J.put(r, "covered", covered);
        J.put(r, "planned", planned);
        J.put(r, "broken", broken);
        return r;
    }
}
