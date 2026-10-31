package app.jarvis.ui.chat;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Presentation helpers shared by the chat rows: human tool names, durations, day separators, JSON pretty-printing. */
public final class ChatFormat {
    private ChatFormat() { }

    /** "fs_read" → "Чтение файла"-style short label: prefix gives the category, the rest is spaced. */
    public static String toolTitle(String name) {
        if (name == null || name.isEmpty()) return "Инструмент";
        int us = name.indexOf('_');
        String head = us > 0 ? name.substring(0, us) : "";
        String rest = us > 0 ? name.substring(us + 1) : name;
        String restHuman = rest.replace('_', ' ');
        if (restHuman.length() == 1) restHuman = restHuman.toUpperCase(Locale.ROOT);
        else if (!restHuman.isEmpty()) restHuman = Character.toUpperCase(restHuman.charAt(0)) + restHuman.substring(1);
        String cat = category(head);
        String verb = VERBS.containsKey(rest) ? VERBS.get(rest) : restHuman;
        return cat.isEmpty() ? verb : cat + ": " + verb;
    }

    private static final java.util.Map<String, String> VERBS = new java.util.HashMap<>();
    static {
        VERBS.put("list", "список");
        VERBS.put("read", "чтение");
        VERBS.put("get", "чтение");
        VERBS.put("write", "запись");
        VERBS.put("save", "сохранение");
        VERBS.put("stat", "сведения");
        VERBS.put("mkdir", "папка");
        VERBS.put("delete", "удаление");
        VERBS.put("move", "перемещение");
        VERBS.put("search", "поиск");
        VERBS.put("fetch", "загрузка");
        VERBS.put("exec", "команда");
        VERBS.put("create", "создание");
        VERBS.put("play", "воспроизведение");
    }

    private static String category(String head) {
        switch (head) {
            case "fs": return "Файлы";
            case "shell": return "Терминал";
            case "http": return "Сеть";
            case "download": return "Загрузки";
            case "memory": return "Память";
            case "music": return "Музыка";
            case "note": return "Заметки";
            case "sys": return "Система";
            case "adb": return "ADB";
            case "voice": return "Голос";
            case "web": return "Веб";
            default: return "";
        }
    }

    /** "812 мс", "1,4 с", "2 мин 05 с". */
    public static String duration(long ms) {
        if (ms < 1000) return ms + " мс";
        if (ms < 60_000) return trimZero(Math.round(ms / 100.0) / 10.0) + " с";
        long m = ms / 60_000, s = (ms % 60_000) / 1000;
        return m + " мин " + String.format(Locale.US, "%02d", s) + " с";
    }

    private static String trimZero(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v).replace('.', ',');
    }

    public static String time(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /** "Сегодня", "Вчера", "3 октября", "3 октября 2025". */    public static String dayLabel(long ts) {
        Calendar now = Calendar.getInstance();
        Calendar that = Calendar.getInstance();
        that.setTimeInMillis(ts);
        zero(now); zero(that);
        long days = Math.round((now.getTimeInMillis() - that.getTimeInMillis()) / 86400000.0);
        if (days == 0) return "Сегодня";
        if (days == 1) return "Вчера";
        String month = new java.text.SimpleDateFormat("d MMMM", new Locale("ru")).format(that.getTime());
        return that.get(Calendar.YEAR) == now.get(Calendar.YEAR) ? month : month + " " + that.get(Calendar.YEAR);
    }

    private static void zero(Calendar c) { c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); }

    /** 128000 → "128K", 1000000 → "1M". */
    public static String k(int n) {
        if (n >= 1_000_000 && n % 1_000_000 == 0) return (n / 1_000_000) + "M";
        if (n >= 1000) return (n % 1000 == 0 ? String.valueOf(n / 1000) : String.format(Locale.US, "%.1f", n / 1000.0)) + "K";
        return String.valueOf(n);
    }

    /** Pretty JSON (2 spaces) for tool args/results; falls back to the raw string. */
    public static String pretty(String json) {
        String s = json == null ? "" : json.trim();
        if (s.isEmpty()) return "";
        try {
            if (s.startsWith("[")) return new JSONArray(s).toString(2).replace("\\/", "/");
            if (s.startsWith("{")) return new JSONObject(s).toString(2).replace("\\/", "/");
        } catch (Throwable ignored) { }
        return s;
    }
}
