package app.jarvis.core.adb;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses user-pasted pairing text ("192.168.1.5:37123 код 123456", "pair 10.0.0.2:41234 654321"). */
public final class AdbInput {
    public final String host;
    public final int port;
    public final String code; // "" when absent

    private AdbInput(String host, int port, String code) {
        this.host = host;
        this.port = port;
        this.code = code;
    }

    private static final Pattern ADDR = Pattern.compile(
        "(\\d{1,3}(?:\\.\\d{1,3}){3}|\\[[0-9a-fA-F:]+\\]|[A-Za-z0-9._-]+):(\\d{1,5})");
    private static final Pattern CODE = Pattern.compile("(?:код|code)?\\s*[:=]?\\s*(\\d{6})\\b");
    private static final Pattern CODE_IP = Pattern.compile("\\b([1-9]\\d{5})\\b");

    /** Returns null when no address:port pair is found. */
    public static AdbInput parse(String text) {
        if (text == null) return null;
        String s = text.trim().replaceFirst("(?i)^adb\\s+(pair|connect)\\s+", "")
            .replaceFirst("(?i)^(pair|connect|сопряжение|подключение)\\s+", "");
        Matcher m = ADDR.matcher(s);
        if (!m.find()) return null;
        String host = m.group(1);
        int port = Integer.parseInt(m.group(2));
        if (port < 1 || port > 65535) return null;
        String rest = s.substring(m.end());
        String code = "";
        Matcher c = CODE.matcher(rest);
        if (c.find()) {
            code = c.group(1);
        } else {
            Matcher c2 = CODE_IP.matcher(rest);
            if (c2.find()) code = c2.group(1);
        }
        return new AdbInput(host, port, code);
    }

    @Override
    public String toString() {
        return host + ":" + port + (code.isEmpty() ? "" : " code=" + code);
    }
}
