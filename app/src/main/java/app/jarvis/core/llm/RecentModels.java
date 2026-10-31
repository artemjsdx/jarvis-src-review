package app.jarvis.core.llm;

import java.util.ArrayList;
import java.util.List;

/** Most recently selected (provider, model) pairs, newest first, stored as "provider|model" strings. */
public final class RecentModels {
    public static final int MAX = 8;

    private RecentModels() { }

    public static String key(String provider, String model) { return provider + "|" + model; }

    /** The list with this pair moved to the front; ids that would break the comma-separated settings storage are ignored. */
    public static List<String> push(List<String> current, String provider, String model) {
        List<String> out = new ArrayList<>();
        if (provider == null || model == null || provider.isEmpty() || model.isEmpty()
            || model.contains(",") || model.contains("|") || provider.contains(",") || provider.contains("|")) {
            if (current != null) for (String s : current) if (valid(s) && !out.contains(s) && out.size() < MAX) out.add(s);
            return out;
        }
        out.add(key(provider, model));
        if (current != null) for (String s : current) if (valid(s) && !out.contains(s) && out.size() < MAX) out.add(s);
        return out;
    }

    /** Provider and model of a stored entry, or null when the entry is malformed. */
    public static String[] split(String entry) {
        if (!valid(entry)) return null;
        int i = entry.indexOf('|');
        return new String[]{entry.substring(0, i), entry.substring(i + 1)};
    }

    private static boolean valid(String s) {
        if (s == null) return false;
        int i = s.indexOf('|');
        return i > 0 && i < s.length() - 1;
    }
}
