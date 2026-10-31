package app.jarvis.core.util;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Removes secrets from anything that is about to be logged or exported. */
public final class Redactor {
    private Redactor() { }

    private static final CopyOnWriteArrayList<String> SECRETS = new CopyOnWriteArrayList<>();

    private static final Pattern SK = Pattern.compile("\\b(sk|pk|rk|xai|gsk|hf|ghp|AIza)[-_][A-Za-z0-9_\\-]{12,}");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer|basic)\\s+[A-Za-z0-9._~+/=\\-]{8,}");
    private static final Pattern KV = Pattern.compile(
        "(?i)(api[_-]?key|x-api-key|access[_-]?token|auth[_-]?token|token|secret|password|passwd|authorization|xi-api-key)(\"?\\s*[:=]\\s*\"?)([^\\s\"',}&]{4,})");
    private static final Pattern PAIR = Pattern.compile("(?i)(pair(?:ing)?[\\w ]{0,16}?(?:code|код)?\\s*[:=]?\\s*)(\\d{6})\\b");
    private static final Pattern LONG = Pattern.compile("(?<![A-Za-z0-9_\\-])[A-Za-z0-9_\\-]{40,}(?![A-Za-z0-9_\\-])");
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]{32,128}");

    /** Registers an exact secret value (an API key the app knows) so it is masked wherever it appears. */
    public static void addSecret(String s) {
        if (s == null) return;
        s = s.trim();
        if (s.length() >= 6 && !SECRETS.contains(s)) SECRETS.add(s);
    }

    public static void clearSecrets() { SECRETS.clear(); }

    public static String redact(String in) {
        if (in == null || in.isEmpty()) return in;
        String s = in;
        for (String sec : SECRETS) {
            if (s.contains(sec)) s = s.replace(sec, "***");
        }
        s = SK.matcher(s).replaceAll("$1-***");
        s = BEARER.matcher(s).replaceAll("$1 ***");
        s = KV.matcher(s).replaceAll("$1$2***");
        s = PAIR.matcher(s).replaceAll("$1******");
        Matcher m = LONG.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String g = m.group();
            m.appendReplacement(sb, HEX.matcher(g).matches() ? Matcher.quoteReplacement(g) : "[redacted]");
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
