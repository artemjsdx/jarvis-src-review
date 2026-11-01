package app.jarvis.core.agent;

import java.util.Locale;

/** Pure Java policy for brief deterministic progress phrases; never invents tool results. */
public final class AgentCommentPolicy {
    private AgentCommentPolicy() { }

    public static boolean preAction(boolean enabled, int toolNumber, long textChars, boolean cancelled, boolean ackAlreadySpoken) {
        return enabled && toolNumber == 1 && textChars == 0 && !cancelled && !ackAlreadySpoken;
    }

    public static boolean due(int completed, int every, boolean cancelled, boolean finalSeen) {
        return every > 0 && completed > 0 && completed % every == 0 && !cancelled && !finalSeen;
    }

    public static String preActionPhrase(String tool, String custom) {
        if (custom != null) for (String s : custom.split("\\|")) {
            String phrase = s.trim();
            if (!phrase.isEmpty()) return phrase.length() > 120 ? phrase.substring(0, 120) : phrase;
        }
        String name = tool == null ? "" : tool.toLowerCase(Locale.ROOT);
        if (name.contains("search") || name.contains("web") || name.contains("http")) return "Сейчас поищу и проверю информацию.";
        if (name.contains("file") || name.contains("fs_") || name.contains("folder")) return "Сейчас проверю файлы.";
        if (name.contains("music")) return "Сейчас выполню музыкальную команду.";
        if (name.contains("adb")) return "Проверю подключение к устройству.";
        if (name.contains("shell") || name.contains("term") || name.contains("exec")) return "Запущу команду и проверю результат.";
        if (name.contains("telegram")) return "Проверю Telegram.";
        return "Сейчас выполню действие и проверю результат.";
    }

    public static String progress(String tool, boolean ok) {
        String name = tool == null ? "" : tool.toLowerCase(Locale.ROOT);
        String subject = name.contains("search") || name.contains("web") ? "поиск"
            : name.contains("file") || name.contains("fs_") ? "работу с файлами"
            : name.contains("adb") ? "проверку устройства"
            : name.contains("term") || name.contains("shell") ? "команду"
            : name.contains("music") ? "музыкальное действие" : "действие";
        return ok ? "Завершил " + subject + ", продолжаю." : "При " + subject + " возникла ошибка. Проверю следующий шаг.";
    }
}
