package app.jarvis.core.skills;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SKILL.md format (agentskills.io / Hermes): YAML front matter between two "---" lines, then a markdown body.
 * The YAML reader is deliberately tiny: scalars, inline lists "[a, b]", block lists "- a" and ignored nested mappings.
 */
public final class SkillFormat {
    private SkillFormat() { }

    public static final int MAX_NAME = 64, MAX_DESCRIPTION = 300;
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final String[] TRIGGERS = {"use when ", "используй, когда ", "используй когда "};

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** Throws a Russian message for a name that cannot be a skill directory. */
    public static void checkName(String name) throws SkillException {
        if (!validName(name)) {
            throw new SkillException("Имя навыка «" + name + "»: только строчные латинские буквы, цифры, «-» и «_», до " + MAX_NAME + " символов, с буквы или цифры");
        }
    }

    /** "Use when &lt;trigger&gt;. &lt;behaviour&gt;." (or the Russian «Используй, когда …»), at most 300 characters. */
    public static void checkDescription(String d) throws SkillException {
        if (d == null || d.trim().isEmpty()) throw new SkillException("В шапке нет description");
        if (d.length() > MAX_DESCRIPTION) throw new SkillException("description длиннее " + MAX_DESCRIPTION + " символов (" + d.length() + ")");
        String low = d.toLowerCase(Locale.ROOT);
        for (String t : TRIGGERS) if (low.startsWith(t)) return;
        throw new SkillException("description должен начинаться с «Используй, когда …» (или «Use when …»): триггер, затем поведение");
    }

    /**
     * Parses a whole SKILL.md. {@code dirName} is the skill directory; a {@code name} in the front matter must equal it.
     */
    public static SkillDoc parse(String raw, String dirName) throws SkillException {
        String text = raw.startsWith("﻿") ? raw.substring(1) : raw;
        text = text.replace("\r\n", "\n");
        if (!text.startsWith("---\n")) throw new SkillException("SKILL.md должен начинаться с шапки «---»");
        int end = text.indexOf("\n---", 3);
        if (end < 0) throw new SkillException("В SKILL.md не закрыта шапка «---»");
        int bodyStart = end + 4;
        if (bodyStart < text.length() && text.charAt(bodyStart) != '\n') throw new SkillException("После шапки «---» должна идти новая строка");
        Map<String, Object> fm = frontMatter(text.substring(4, end));
        String body = (bodyStart >= text.length() ? "" : text.substring(bodyStart)).trim();
        if (body.isEmpty()) throw new SkillException("В SKILL.md нет инструкции после шапки");
        String name = scalar(fm, "name", dirName);
        if (!name.equals(dirName)) throw new SkillException("name «" + name + "» не совпадает с папкой «" + dirName + "»");
        checkName(name);
        String description = scalar(fm, "description", "");
        checkDescription(description);
        return new SkillDoc(name, description, scalar(fm, "version", ""), list(fm, "tags"), list(fm, "requires_tools"), body);
    }

    private static String scalar(Map<String, Object> fm, String key, String def) {
        Object v = fm.get(key);
        return v instanceof String ? (String) v : def;
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Map<String, Object> fm, String key) {
        Object v = fm.get(key);
        return v instanceof List ? (List<String>) v : new ArrayList<String>();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> frontMatter(String fm) throws SkillException {
        Map<String, Object> out = new LinkedHashMap<>();
        String listKey = null;
        for (String line : fm.split("\n")) {
            if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;
            boolean indented = line.startsWith(" ") || line.startsWith("\t");
            String t = line.trim();
            if (indented) {
                if (listKey != null && t.startsWith("- ")) ((List<String>) out.get(listKey)).add(unquote(t.substring(2).trim()));
                continue;           // nested mappings (metadata:, hermes:) are not used
            }
            int colon = t.indexOf(':');
            if (colon <= 0) throw new SkillException("Непонятная строка в шапке SKILL.md: «" + (t.length() > 60 ? t.substring(0, 60) + "…" : t) + "»");
            String key = t.substring(0, colon).trim();
            String value = t.substring(colon + 1).trim();
            listKey = null;
            if (value.isEmpty()) {
                listKey = key;
                out.put(key, new ArrayList<String>());
            } else if (value.startsWith("[") && value.endsWith("]")) {
                List<String> items = new ArrayList<>();
                String inner = value.substring(1, value.length() - 1).trim();
                if (!inner.isEmpty()) for (String p : inner.split(",")) items.add(unquote(p.trim()));
                out.put(key, items);
            } else {
                out.put(key, unquote(value));
            }
        }
        return out;
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && (v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"' || v.charAt(0) == '\'' && v.charAt(v.length() - 1) == '\'')) {
            return v.substring(1, v.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return v;
    }
}
