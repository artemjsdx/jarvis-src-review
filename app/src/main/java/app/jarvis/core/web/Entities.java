package app.jarvis.core.web;

import java.util.HashMap;
import java.util.Map;

/** HTML character reference decoding: the common named entities plus decimal and hex numeric references. */
public final class Entities {
    private Entities() { }

    private static final Map<String, String> NAMED = new HashMap<>();

    static {
        String[] pairs = {
            "amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "nbsp", " ", "ensp", " ", "emsp", " ",
            "thinsp", " ", "ndash", "–", "mdash", "—", "hellip", "…", "laquo", "«", "raquo", "»",
            "lsquo", "‘", "rsquo", "’", "ldquo", "“", "rdquo", "”", "bdquo", "„", "copy", "©",
            "reg", "®", "trade", "™", "euro", "€", "pound", "£", "yen", "¥", "cent", "¢",
            "sect", "§", "para", "¶", "deg", "°", "plusmn", "±", "times", "×", "divide", "÷",
            "middot", "·", "bull", "•", "frac12", "½", "frac14", "¼", "frac34", "¾", "larr", "←",
            "uarr", "↑", "rarr", "→", "darr", "↓", "harr", "↔", "shy", "", "zwnj", "", "zwj", "",
            "iexcl", "¡", "iquest", "¿", "micro", "µ", "ordf", "ª", "ordm", "º", "sup2", "²", "sup3", "³"
        };
        for (int i = 0; i < pairs.length; i += 2) NAMED.put(pairs[i], pairs[i + 1]);
    }

    public static String decode(String s) {
        if (s.indexOf('&') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c != '&') { out.append(c); i++; continue; }
            int semi = s.indexOf(';', i + 1);
            if (semi < 0 || semi - i > 32) { out.append(c); i++; continue; }
            String name = s.substring(i + 1, semi);
            String rep = resolve(name);
            if (rep == null) { out.append(c); i++; continue; }
            out.append(rep);
            i = semi + 1;
        }
        return out.toString();
    }

    private static String resolve(String name) {
        if (name.isEmpty()) return null;
        if (name.charAt(0) == '#') {
            try {
                int cp = name.length() > 1 && (name.charAt(1) == 'x' || name.charAt(1) == 'X')
                    ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
                if (cp <= 0 || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return "�";
                return new String(Character.toChars(cp));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return NAMED.get(name);
    }
}
