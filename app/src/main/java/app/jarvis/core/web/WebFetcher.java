package app.jarvis.core.web;

import app.jarvis.core.tools.FsTools;
import app.jarvis.core.util.CancelToken;
import java.net.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fetches one page and turns it into text the agent can read: markdown, plain text or raw, with offset pagination. */
public final class WebFetcher {
    public static final long MAX_SOURCE_BYTES = 3L << 20;
    public static final int MAX_REDIRECTS = 5, MAX_LINKS = 30;
    static final int CONNECT_MS = 15_000, READ_MS = 20_000;
    static final String ACCEPT = "text/html,application/xhtml+xml,application/json,text/plain;q=0.9,*/*;q=0.5";
    static final String BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";
    static final String BINARY_HINT = "Бинарный файл (PDF, офис, изображение или архив) не разбирается. Используйте download, затем откройте файл в разделе Файлы.";

    public static final class Page {
        public String url = "", finalUrl = "", title = "", contentType = "", format = "", text = "", hint = "", error = "";
        public int status;
        public boolean ok = true, binary, truncated, sourceTruncated;
        /** Character offset to continue from, or -1 when the text is complete. */
        public int nextOffset = -1;
        public int totalChars;
        public final List<String[]> links = new ArrayList<>();
    }

    private final Proxy proxy;

    public WebFetcher(Proxy proxy) {
        this.proxy = proxy;
    }

    public Page fetch(String url, String format, int maxChars, int offset, CancelToken cancel) throws WebException {
        String start = url.trim();
        if (!isHttp(start)) throw new WebException("Допустимы только http:// и https:// адреса", "scheme: " + start, 0, false);
        Page p = new Page();
        p.url = start;
        String current = start;
        WebHttp.Reply rep = null;
        for (int hop = 0; ; hop++) {
            rep = WebHttp.send("страница", "GET", current, headers(), null, proxy, CONNECT_MS, READ_MS, false, MAX_SOURCE_BYTES, cancel);
            if (!isRedirect(rep.status) || rep.location == null || rep.location.trim().isEmpty()) break;
            if (hop >= MAX_REDIRECTS) throw new WebException("Слишком много перенаправлений (больше " + MAX_REDIRECTS + ")", "redirects", rep.status, false);
            String next = resolve(current, rep.location.trim());
            if (next == null) throw new WebException("Перенаправление на неподдерживаемый адрес", "redirect: " + rep.location, rep.status, false);
            if (isDowngrade(current, next)) {
                throw new WebException("Переход с https на http запрещён", "https downgrade refused: " + next, rep.status, false);
            }
            current = next;
        }
        p.finalUrl = current;
        p.status = rep.status;
        p.contentType = rep.contentType == null ? "" : rep.contentType;
        p.sourceTruncated = rep.truncated;
        if (rep.status >= 400) {
            p.ok = false;
            p.error = "HTTP " + rep.status;
            return p;
        }
        String mime = mimeOf(p.contentType);
        if (isBinary(mime, rep.body)) {
            p.binary = true;
            p.format = "binary";
            p.hint = BINARY_HINT;
            return p;
        }
        String decoded = TextDecoder.decode(rep.body, p.contentType);
        String fmt = format == null || format.isEmpty() ? "markdown" : format;
        String body;
        if (mime.contains("html") || (mime.isEmpty() && looksLikeHtml(decoded))) {
            if ("raw".equals(fmt)) {
                body = decoded;
            } else {
                HtmlToText.Result r = HtmlToText.convert(decoded, p.finalUrl, !"text".equals(fmt));
                body = r.text;
                p.title = r.title;
                for (String[] l : r.links) {
                    if (p.links.size() >= MAX_LINKS) break;
                    if (!containsUrl(p.links, l[1])) p.links.add(l);
                }
            }
            p.format = "raw".equals(fmt) ? "raw" : "text".equals(fmt) ? "text" : "markdown";
        } else if (mime.contains("json")) {
            body = prettyJson(decoded);
            p.format = "json";
        } else {
            body = decoded;
            p.format = "text";
        }
        p.totalChars = body.length();
        slice(p, body, maxChars, offset);
        return p;
    }

    /** Sets text to the window [offset, offset+maxChars) of body, never splitting a surrogate pair. */
    static void slice(Page p, String body, int maxChars, int offset) {
        int total = body.length();
        int from = Math.max(0, Math.min(offset, total));
        if (from > 0 && from < total && Character.isLowSurrogate(body.charAt(from))) from--;
        int to = (int) Math.min((long) total, (long) from + Math.max(1, maxChars));
        if (to < total && to > from && Character.isHighSurrogate(body.charAt(to - 1))) to--;
        p.text = body.substring(from, to);
        p.nextOffset = to < total ? to : -1;
        p.truncated = p.nextOffset >= 0 || p.sourceTruncated;
        if (p.sourceTruncated && p.hint.isEmpty()) {
            p.hint = "Страница больше 3 МБ: показана только начальная часть.";
        }
    }

    static boolean isHttp(String u) {
        String low = u.toLowerCase(Locale.ROOT);
        return low.startsWith("http://") || low.startsWith("https://");
    }

    static boolean isDowngrade(String from, String to) {
        return from.startsWith("https://") && to.startsWith("http://");
    }

    static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    static String resolve(String base, String location) {
        try {
            URI next = new URI(location.replace(" ", "%20"));
            if (!next.isAbsolute()) next = new URI(base).resolve(next);
            String s = next.toString();
            return isHttp(s) ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String mimeOf(String contentType) {
        int semi = contentType.indexOf(';');
        String m = semi >= 0 ? contentType.substring(0, semi) : contentType;
        return m.trim().toLowerCase(Locale.ROOT);
    }

    static boolean isBinary(String mime, byte[] body) {
        if (mime.isEmpty() || mime.equals("application/octet-stream")) return FsTools.looksBinary(body);
        return !(mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") || mime.contains("javascript")
            || mime.contains("yaml") || mime.equals("application/x-sh"));
    }

    static boolean looksLikeHtml(String s) {
        String t = s.trim();
        return t.regionMatches(true, 0, "<!doctype html", 0, 14) || t.regionMatches(true, 0, "<html", 0, 5);
    }

    private static String prettyJson(String s) {
        String t = s.trim();
        try {
            if (t.startsWith("[")) return new JSONArray(t).toString(2);
            return new JSONObject(t).toString(2);
        } catch (Exception e) {
            return s;
        }
    }

    private static boolean containsUrl(List<String[]> links, String url) {
        for (String[] l : links) if (l[1].equals(url)) return true;
        return false;
    }

    private static Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", BROWSER_UA);
        h.put("Accept", ACCEPT);
        h.put("Accept-Language", "ru,en;q=0.8");
        h.put("Accept-Encoding", "gzip");
        return h;
    }
}
