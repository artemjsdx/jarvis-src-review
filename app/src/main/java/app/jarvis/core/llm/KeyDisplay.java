package app.jarvis.core.llm;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Text for the API-key block: what is shown about a stored key, never the key itself beyond its last four characters. */
public final class KeyDisplay {
    private KeyDisplay() { }

    /** Settings key that holds the save time of a provider's key (epoch millis). The key itself is never stored there. */
    public static String savedAtKey(String providerId) { return "key." + providerId + ".savedAt"; }

    /** "••••1234" for keys of eight characters or more; shorter keys reveal nothing. */
    public static String mask(String key) {
        if (key == null || key.isEmpty()) return "не задан";
        if (key.length() < 8) return "••••";
        return "••••" + key.substring(key.length() - 4);
    }

    /** "сохранён: ••••1234 · 29.10 03:12", or "ключ не задан". A key saved before times were recorded has no time. */
    public static String statusLine(String key, String savedAtMillis, TimeZone zone) {
        if (key == null || key.isEmpty()) return "ключ не задан";
        String base = "сохранён: " + mask(key);
        long at = parseMillis(savedAtMillis);
        if (at <= 0) return base + " · время неизвестно";
        SimpleDateFormat f = new SimpleDateFormat("dd.MM HH:mm", Locale.US);
        f.setTimeZone(zone);
        return base + " · " + f.format(new Date(at));
    }

    /** Where the key lives, stated as it really is: encrypted with the Keystore key, or stored in clear when the Keystore is unusable. */
    public static String storageText(boolean encrypted) {
        if (encrypted) return "Хранится только на этом устройстве, зашифрован ключом Android Keystore";
        return "Хранится только на этом устройстве. Android Keystore недоступен, поэтому ключ сохранён без шифрования";
    }

    /** Short Russian name of a failure class for a status pill. */
    public static String label(LlmException.Kind kind) {
        if (kind == null) return "ключ принят";
        switch (kind) {
            case AUTH: return "неверный ключ";
            case PAYMENT: return "нет средств";
            case GEO_BLOCK: return "гео-блок";
            case NETWORK: return "нет сети";
            case TIMEOUT: return "таймаут";
            case TLS: return "ошибка TLS";
            case NOT_FOUND: return "адрес не найден";
            case RATE_LIMIT: return "лимит запросов";
            default: return "ошибка";
        }
    }

    static long parseMillis(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
