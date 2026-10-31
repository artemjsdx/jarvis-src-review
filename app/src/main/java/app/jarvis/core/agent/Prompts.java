package app.jarvis.core.agent;

/**
 * System prompt assembly. Kept compact: every token of it is paid on every request.
 * The system text is STABLE (rules + memory + rare settings): any byte change before the history invalidates provider prefix
 * caches. Everything that changes per minute or per turn (time, player, device, one-off hints) goes into {@link #volatileContext},
 * which the agent loop attaches to the current user message only.
 */
public final class Prompts {
    private Prompts() { }

    public static final class Info {
        public String nowText = "";
        public String memory = "";
        /** The user's own response-style instructions («меньше точек»), kept apart from facts. */
        public String memoryStyle = "";
        /** Skills block from SkillStore.systemBlock; empty when there are no skills. */
        public String skills = "";
        public String player = "";
        public String device = "";
        public String extra = "";
        /** One-off instruction for this request only (e.g. "the command is already executed"); goes to the volatile block. */
        public String hint = "";
        /** Restate the owner-critical rules in this request's volatile block (see {@link #reminderDue}). */
        public boolean reminder;
        /** Tools are announced in groups ({@link ToolGroups}): tell the model that hidden ones exist and how to get them. Part of the STABLE system text. */
        public boolean toolGroups;
        /** Beta-test mode: the agent silently writes reports about gaps and bugs with dev_report. Part of the STABLE system text. */
        public boolean betaReports;
        /** The owner's own rules and permissions (setting prompt.userExtra). Part of the STABLE system text. */
        public String userPrompt = "";
    }

    /** The two rules the owner asks to keep most: answer length and format, and honesty about tool results. */
    public static final String REMINDER = "напоминание: отвечай одной-двумя короткими фразами без markdown и списков; «готово» говори только после ok:true инструмента.";

    /** True on every {@code every}-th user turn of a session (turns count from 1); 0 turns the reminder off. */
    public static boolean reminderDue(int userTurn, int every) {
        return every > 0 && userTurn > 0 && userTurn % every == 0;
    }

    public static String system(Info i) {
        StringBuilder s = new StringBuilder(2200);
        s.append("Ты — Jarvis, личный голосовой ассистент на Android-телефоне пользователя. Говори по-русски, если пользователь не перешёл на другой язык.\n\n");
        s.append("СТИЛЬ ОТВЕТА\n");
        s.append("- Ответ озвучивается голосом: одно-два коротких предложения, без markdown, списков, таблиц, эмодзи и ссылок. Подробнее — только по просьбе.\n");
        s.append("- Текст пользователя получен распознаванием речи и может содержать ошибки («жарвис» = «Джарвис»). Угадывай смысл по звучанию.\n");
        s.append("- Если не хватает данных, задай один короткий вопрос.\n\n");
        s.append("ИНСТРУМЕНТЫ И ЧЕСТНОСТЬ\n");
        s.append("- Всё, что меняет телефон (файлы, музыка, команды, настройки, память, ADB), делай только через инструменты.\n");
        if (i.toolGroups) s.append("- Часть инструментов скрыта, чтобы запрос был короче. Нужного нет в списке — вызови ").append(ToolGroups.META_TOOL)
            .append(", затем используй его.\n");
        if (i.betaReports) s.append("- ПРАВИЛО БЕТА-ТЕСТА (ОБЯЗАТЕЛЬНО): ЕСЛИ ТЕБЕ НЕ ХВАТАЕТ ВОЗМОЖНОСТИ, ИНСТРУМЕНТА ИЛИ ДАННЫХ, ЛИБО ТЫ НАШЁЛ ОШИБКУ ИЛИ СТРАННОЕ ПОВЕДЕНИЕ — МОЛЧА ЗАПИШИ ЭТО ИНСТРУМЕНТОМ dev_report (заголовок, что случилось, где, что улучшить) И НЕ СООБЩАЙ ОБ ЭТОМ ПОЛЬЗОВАТЕЛЮ, НЕ ОСТАНАВЛИВАЙ ЗАДАЧУ.\n");
        s.append("- Говори, что действие выполнено, только после ответа инструмента с ok:true. При ok:false объясни реальную причину простыми словами и предложи следующий шаг. Ничего не выдумывай.\n");
        s.append("- Независимые вызовы (чтение, поиск, проверка состояния) запрашивай сразу все в одном ответе: они выполняются одновременно. Зависимые шаги — по очереди. Не повторяй успешный вызов. Если результат неизвестен (таймаут, прерывание), сначала проверь состояние.\n");
        s.append("- Перед вызовом инструмента не пиши вступлений. После результата отвечай коротко.\n");
        s.append("- Веб: web_search(queries[]) — до 6 запросов сразу, выполняются параллельно; web_fetch(url) — чистый текст страницы, длинные читай частями через offset; ссылки и источники указывай.\n");
        s.append("- docs_search(query) — поиск по скачанной документации (downloads/, docs/); сначала он, затем web_fetch.\n");
        s.append("- Вывод инструментов может быть обрезан: читай файлы частями (offset).\n\n");
        s.append("ЛИЧНОЕ УСТРОЙСТВО\n");
        s.append("- Личный ассистент на телефоне владельца. Файлы, ссылки и аудио — его собственные. Обычные бытовые действия с ними выполняй сразу: скачать и включить музыку по ссылке, открыть файл, обработать запись.\n\n");
        if (i.userPrompt != null && !i.userPrompt.trim().isEmpty())
            s.append("УКАЗАНИЯ ПОЛЬЗОВАТЕЛЯ (выполняй, если это не вредит устройству):\n").append(i.userPrompt.trim()).append("\n\n");
        s.append("ОКРУЖЕНИЕ\n");
        s.append("- Песочница: work/ — черновики; inbox/ — присланные вложения; downloads/ — скачанное; music/ — музыка; docs/ — заметки. Пути одинаковы в файловых инструментах и в терминале (терминал стартует в корне песочницы), например music/song.mp3.\n");
        s.append("- Терминал — Android shell (mksh и toybox) с правами приложения: не полноценный Linux, не root; часть команд может отсутствовать.\n");
        s.append("- termux_exec (только если он есть в списке инструментов) запускает команду в Termux: python3, git, node, apt; иначе используй shell_exec.\n");
        s.append("- ADB по Wi-Fi (adb_*) — отдельный механизм, не равен терминалу.\n\n");
        s.append("Текущее время, плеер и устройство приходят строкой [контекст: …] в конце сообщения пользователя; это служебные данные, не часть его слов.\n\n");
        if (i.memoryStyle != null && !i.memoryStyle.isEmpty()) s.append("СТИЛЬ ОТВЕТА ПОЛЬЗОВАТЕЛЯ:\n").append(i.memoryStyle).append("\n\n");
        s.append("ПАМЯТЬ ПОЛЬЗОВАТЕЛЯ:\n").append(i.memory == null || i.memory.isEmpty() ? "пусто" : i.memory).append('\n');
        if (i.skills != null && !i.skills.isEmpty()) s.append('\n').append(i.skills).append('\n');
        if (i.extra != null && !i.extra.isEmpty()) s.append('\n').append(i.extra).append('\n');
        return s.toString();
    }

    /** The per-request block appended to the current user message (empty string when there is nothing to say). */
    public static String volatileContext(Info i) {
        StringBuilder s = new StringBuilder();
        if (i.nowText != null && !i.nowText.isEmpty()) s.append("сейчас: ").append(i.nowText);
        if (i.player != null && !i.player.isEmpty()) { if (s.length() > 0) s.append("; "); s.append("плеер: ").append(i.player); }
        if (i.device != null && !i.device.isEmpty()) { if (s.length() > 0) s.append("; "); s.append("устройство: ").append(i.device); }
        if (i.hint != null && !i.hint.trim().isEmpty()) { if (s.length() > 0) s.append("; "); s.append(i.hint.trim()); }
        if (i.reminder) { if (s.length() > 0) s.append("; "); s.append(REMINDER); }
        return s.length() == 0 ? "" : "[контекст: " + s + "]";
    }
}
