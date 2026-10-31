package app.jarvis.core.web;

import java.util.Locale;

/** Canonical form of result URLs for de-duplication: lower-case host, no "www.", no fragment, no tracking parameters. */
public final class UrlNormalizer {
    private UrlNormalizer() { }

    public static String normalize(String url) {
        String u = url.trim();
        int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash);
        int scheme = u.indexOf("://");
        if (scheme < 0) return u;
        String prefix = u.substring(0, scheme + 3).toLowerCase(Locale.ROOT);
        String rest = u.substring(scheme + 3);
        int slash = rest.indexOf('/');
        int q = rest.indexOf('?');
        int end = slash < 0 ? (q < 0 ? rest.length() : q) : (q >= 0 && q < slash ? q : slash);
        String hostPort = rest.substring(0, end).toLowerCase(Locale.ROOT);
        if (hostPort.startsWith("www.")) hostPort = hostPort.substring(4);
        String path = rest.substring(end);
        int qi = path.indexOf('?');
        String query = "";
        if (qi >= 0) {
            query = stripTracking(path.substring(qi + 1));
            path = path.substring(0, qi);
        }
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isEmpty() && !query.isEmpty()) path = "/";
        return prefix + hostPort + path + (query.isEmpty() ? "" : "?" + query);
    }

    public static String host(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) return "";
        String rest = url.substring(scheme + 3);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == ':') { end = i; break; }
        }
        String h = rest.substring(0, end).toLowerCase(Locale.ROOT);
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    private static String stripTracking(String query) {
        StringBuilder sb = new StringBuilder();
        for (String kv : query.split("&")) {
            if (kv.isEmpty()) continue;
            String key = kv.indexOf('=') >= 0 ? kv.substring(0, kv.indexOf('=')) : kv;
            String k = key.toLowerCase(Locale.ROOT);
            if (k.startsWith("utm_") || k.equals("fbclid") || k.equals("gclid") || k.equals("yclid") || k.equals("mc_cid")
                || k.equals("mc_eid") || k.equals("ref") || k.equals("ref_src") || k.equals("_ga")) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(kv);
        }
        return sb.toString();
    }
}
