package app.jarvis.core.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;

/** Prompt for the home-screen suggestion chips and the parser for the model's answer. Pure Java. */
public final class SuggestionPrompt {
    public static final int CHIP_CHARS = 28, LINE_CHARS = 120, MEMORY_CHARS = 400, MAX_LINES = 6, COUNT = 3, MIN_VALID = 2;

    private static final String[] WEEKDAYS = {"понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"};

    public static final String SYSTEM = "Ты придумываешь подсказки для кнопок на главном экране голосового ассистента Джарвис. "
        + "Ответь ТОЛЬКО JSON-массивом из ровно трёх строк на русском языке. Каждая строка не длиннее 28 символов: "
        + "просьба или вопрос к ассистенту, конкретный для этого пользователя и момента. "
        + "Не повторяй то, что пользователь только что сделал. Без эмодзи, нумерации и пояснений.";

    /** Context gathered by the runtime; the runtime reads the chats, this class only formats and hashes it. */
    public static final class Input {
        public int hour;
        /** 0 = Monday .. 6 = Sunday. */
        public int weekday;
        public final List<String> recentUserLines = new ArrayList<>();
        public String memory = "";
        public String nowPlaying = "";
    }

    private SuggestionPrompt() { }

    /** Part of the day used by the prompt and by the offline chips. */
    public static String timeOfDay(int hour) {
        if (hour >= 5 && hour < 11) return "утро";
        if (hour >= 11 && hour < 17) return "день";
        if (hour >= 17 && hour < 23) return "вечер";
        return "ночь";
    }

    public static String user(Input in) {
        StringBuilder b = new StringBuilder();
        int day = Math.max(0, Math.min(WEEKDAYS.length - 1, in.weekday));
        b.append("Сейчас: ").append(timeOfDay(in.hour)).append(", ").append(WEEKDAYS[day])
            .append(", ").append(String.format(Locale.ROOT, "%02d:00", in.hour)).append(".\n");
        if (!in.nowPlaying.trim().isEmpty()) b.append("Сейчас играет: ").append(clip(in.nowPlaying, LINE_CHARS)).append('\n');
        if (!in.memory.trim().isEmpty()) b.append("Что известно о пользователе: ").append(clip(in.memory, MEMORY_CHARS)).append('\n');
        List<String> lines = in.recentUserLines;
        if (lines.isEmpty()) {
            b.append("Последних реплик пользователя нет.\n");
        } else {
            b.append("Последние реплики пользователя:\n");
            for (String l : lines.subList(Math.max(0, lines.size() - MAX_LINES), lines.size())) {
                b.append("- ").append(clip(l, LINE_CHARS)).append('\n');
            }
        }
        b.append("Придумай три подсказки JSON-массивом.");
        return b.toString();
    }

    /** Stable fingerprint of everything the prompt depends on, at the granularity that matters (part of day, not the hour). */
    public static String contextHash(Input in) {
        StringBuilder b = new StringBuilder();
        b.append(timeOfDay(in.hour)).append('|').append(in.weekday).append('|').append(in.memory).append('|').append(in.nowPlaying);
        for (String l : in.recentUserLines) b.append('\n').append(l);
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format(Locale.ROOT, "%02x", d[i] & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(b.toString().hashCode());
        }
    }

    /** Accepts a JSON array (also inside a code fence) or plain lines. Returns an empty list when fewer than two valid chips remain. */
    public static List<String> parse(String raw) {
        if (raw == null) return new ArrayList<>();
        List<String> candidates = fromJsonArray(raw);
        if (candidates == null) candidates = fromLines(raw);
        return validate(candidates);
    }

    static List<String> fromJsonArray(String raw) {
        String s = raw.replace("```json", "").replace("```", "");
        int a = s.indexOf('['), z = s.lastIndexOf(']');
        if (a < 0 || z <= a) return null;
        try {
            JSONArray arr = new JSONArray(s.substring(a, z + 1));
            List<String> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                Object o = arr.opt(i);
                if (o instanceof String) out.add((String) o);
            }
            return out;
        } catch (JSONException e) {
            return null;
        }
    }

    static List<String> fromLines(String raw) {
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String t = line.trim().replaceFirst("^(?:[-*•]|\\d+[.)])\\s*", "");
            t = t.replaceFirst("^[\"“«'`]+", "").replaceFirst("[\"”»'`,]+$", "");
            if (t.equals("[") || t.equals("]") || t.isEmpty()) continue;
            out.add(t);
        }
        return out;
    }

    static List<String> validate(List<String> candidates) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String c : candidates) {
            if (c == null) continue;
            String t = c.replaceAll("\\s+", " ").trim();
            if (t.isEmpty() || t.codePointCount(0, t.length()) > CHIP_CHARS || hasEmoji(t)) continue;
            if (!seen.add(t.toLowerCase(Locale.ROOT))) continue;
            out.add(t);
            if (out.size() == COUNT) break;
        }
        return out.size() >= MIN_VALID ? out : new ArrayList<String>();
    }

    static boolean hasEmoji(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp >= 0x1F000 || (cp >= 0x2600 && cp <= 0x27BF) || cp == 0xFE0F || cp == 0x200D) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    public static String clip(String s, int max) {
        String t = s.replaceAll("\\s+", " ").trim();
        if (t.codePointCount(0, t.length()) <= max) return t;
        int end = t.offsetByCodePoints(0, max - 1);
        return t.substring(0, end) + "…";
    }
}
