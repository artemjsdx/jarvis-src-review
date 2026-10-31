package app.jarvis.core.mcp;

import java.util.Locale;
import java.util.regex.Pattern;

/** Accepts only read-only SQL for db_query: one SELECT/WITH statement or a read-only PRAGMA. Pure Java, tested on the JVM. */
public final class SqlGuard {
    private SqlGuard() { }

    private static final Pattern FORBIDDEN = Pattern.compile(
        "\\b(insert|update|delete|drop|alter|create|replace|attach|detach|vacuum|reindex|analyze|begin|commit|rollback|savepoint|release)\\b");
    private static final Pattern PRAGMA_OK = Pattern.compile(
        "pragma\\s+(table_info|table_xinfo|table_list|index_list|index_info|index_xinfo|foreign_key_list|database_list|user_version|page_count|page_size|freelist_count|journal_mode|compile_options|integrity_check|quick_check)\\b[^=;]*");

    /** @return null when the statement is allowed, otherwise a short English reason. */
    public static String check(String sql) {
        if (sql == null || sql.trim().isEmpty()) return "empty statement";
        String s = strip(sql).trim().toLowerCase(Locale.ROOT);
        while (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        if (s.isEmpty()) return "empty statement";
        if (s.indexOf(';') >= 0) return "only one statement is allowed";
        if (s.startsWith("pragma")) return PRAGMA_OK.matcher(s).matches() ? null : "this PRAGMA is not allowed (read-only list: table_info, index_list, user_version, ...)";
        if (!(s.startsWith("select") || s.startsWith("with"))) return "only SELECT / WITH ... SELECT / read-only PRAGMA are allowed";
        if (FORBIDDEN.matcher(s).find()) return "statement contains a write keyword";
        return null;
    }

    /** Replaces string literals and comments by spaces so that keywords inside text do not count (and ';' inside text is harmless). */
    static String strip(String in) {
        StringBuilder out = new StringBuilder(in.length());
        int i = 0, n = in.length();
        while (i < n) {
            char c = in.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int j = i + 1;
                while (j < n) {
                    if (in.charAt(j) == c) { if (j + 1 < n && in.charAt(j + 1) == c) { j += 2; continue; } break; }
                    j++;
                }
                out.append(' ');
                i = j + 1;
            } else if (c == '-' && i + 1 < n && in.charAt(i + 1) == '-') {
                while (i < n && in.charAt(i) != '\n') i++;
                out.append(' ');
            } else if (c == '/' && i + 1 < n && in.charAt(i + 1) == '*') {
                int e = in.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
                out.append(' ');
            } else { out.append(c); i++; }
        }
        return out.toString();
    }
}
