package app.jarvis.core.web;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Readable-content extraction: drops scripts, navigation, hidden and cookie-banner blocks, picks the main content block and
 * renders it as markdown (or plain text). Links are returned separately as {text, absolute url} pairs in document order.
 */
public final class HtmlToText {
    private HtmlToText() { }

    public static final class Result {
        public String title = "";
        public String text = "";
        public final List<String[]> links = new ArrayList<>();
    }

    private static final Set<String> DROP = set("script", "style", "noscript", "template", "svg", "iframe", "nav", "footer", "aside",
        "form", "head", "title", "object", "embed", "canvas", "button", "select", "textarea", "dialog", "input", "frameset", "frame");
    private static final Set<String> NOISE_WORDS = set("cookie", "cookies", "consent", "gdpr", "advert", "advertisement", "sidebar",
        "breadcrumb", "breadcrumbs", "newsletter", "popup", "share", "social");
    private static final Set<String> INLINE = set("a", "span", "font", "label", "small", "big", "sup", "sub", "abbr", "cite", "q",
        "u", "s", "del", "ins", "mark", "time", "data", "bdi", "bdo", "var", "strong", "b", "em", "i", "code", "kbd", "samp", "tt",
        "img", "br", "wbr", "td", "th");
    private static final Set<String> CONTAINERS = set("div", "section", "article", "main", "body", "html", "center", "td", "th", "tbody");
    private static final Pattern WS = Pattern.compile("[\\s\\u00A0]+");
    private static final Pattern NEWLINES = Pattern.compile("\\n{3,}");
    private static final int MAX_LINKS = 2000;

    private static Set<String> set(String... s) { return new HashSet<>(Arrays.asList(s)); }

    public static Result convert(String html, String baseUrl, boolean markdown) {
        return convert(HtmlParser.parse(html), baseUrl, markdown);
    }

    public static Result convert(HtmlNode doc, String baseUrl, boolean markdown) {
        Result r = new Result();
        r.title = titleOf(doc);
        prune(doc);
        HtmlNode main = mainContent(doc);
        Renderer rd = new Renderer(baseUrl, !markdown, r.links);
        rd.node(main);
        r.text = tidy(rd.out.toString());
        if (r.title.isEmpty()) r.title = firstHeading(main);
        return r;
    }

    // ---- pruning -----------------------------------------------------------------------------------------------------

    private static void prune(HtmlNode n) {
        Iterator<HtmlNode> it = n.children.iterator();
        while (it.hasNext()) {
            HtmlNode c = it.next();
            if (!c.isText() && (DROP.contains(c.name) || isHidden(c) || isNoise(c))) { it.remove(); continue; }
            if (!c.isText()) prune(c);
        }
    }

    private static boolean isHidden(HtmlNode n) {
        if (n.attrs.containsKey("hidden") || "true".equalsIgnoreCase(n.attr("aria-hidden"))) return true;
        String style = n.attr("style").replace(" ", "").toLowerCase(Locale.ROOT);
        return style.contains("display:none") || style.contains("visibility:hidden");
    }

    private static boolean isNoise(HtmlNode n) {
        for (String tok : n.idAndClass().split("[^a-z0-9]+")) if (NOISE_WORDS.contains(tok)) return true;
        return false;
    }

    // ---- main content ------------------------------------------------------------------------------------------------

    private static HtmlNode mainContent(HtmlNode doc) {
        HtmlNode best = null;
        int bestW = 0;
        for (HtmlNode c : candidates(doc)) {
            int w = weight(c);
            if (w > bestW) { best = c; bestW = w; }
        }
        HtmlNode cur = best != null && bestW >= 200 ? best : findFirst(doc, "body");
        if (cur == null) cur = doc;
        for (int depth = 0; depth < 40; depth++) {
            int total = weight(cur);
            if (total == 0) break;
            HtmlNode next = null;
            for (HtmlNode c : cur.children) {
                if (!c.isText() && CONTAINERS.contains(c.name) && weight(c) * 10 >= total * 8) { next = c; break; }
            }
            if (next == null) break;
            cur = next;
        }
        return cur;
    }

    private static List<HtmlNode> candidates(HtmlNode doc) {
        List<HtmlNode> out = new ArrayList<>();
        Deque<HtmlNode> stack = new ArrayDeque<>();
        stack.push(doc);
        while (!stack.isEmpty()) {
            HtmlNode n = stack.pop();
            if (!n.isText() && ("article".equals(n.name) || "main".equals(n.name) || "main".equals(n.attr("role")))) out.add(n);
            for (HtmlNode c : n.children) if (!c.isText()) stack.push(c);
        }
        return out;
    }

    private static HtmlNode findFirst(HtmlNode n, String name) {
        Deque<HtmlNode> stack = new ArrayDeque<>();
        stack.push(n);
        while (!stack.isEmpty()) {
            HtmlNode x = stack.pop();
            if (name.equals(x.name)) return x;
            for (HtmlNode c : x.children) if (!c.isText()) stack.push(c);
        }
        return null;
    }

    private static String titleOf(HtmlNode doc) {
        HtmlNode t = findFirst(doc, "title");
        return t == null ? "" : collapse(textOf(t)).trim();
    }

    private static String firstHeading(HtmlNode n) {
        HtmlNode h = findFirst(n, "h1");
        return h == null ? "" : collapse(textOf(h)).trim();
    }

    /** Visible text length outside links; memoised on the node. */
    static int weight(HtmlNode n) {
        if (n.weight >= 0) return n.weight;
        int w;
        if (n.isText()) w = n.text.trim().length();
        else if ("a".equals(n.name) || "script".equals(n.name) || "style".equals(n.name)) w = 0;
        else {
            w = 0;
            for (HtmlNode c : n.children) w += weight(c);
        }
        n.weight = w;
        return w;
    }

    static String textOf(HtmlNode n) {
        StringBuilder sb = new StringBuilder();
        Deque<HtmlNode> stack = new ArrayDeque<>();
        stack.push(n);
        while (!stack.isEmpty()) {
            HtmlNode x = stack.pop();
            if (x.isText()) { sb.append(x.text).append(' '); continue; }
            for (int i = x.children.size() - 1; i >= 0; i--) stack.push(x.children.get(i));
        }
        return sb.toString();
    }

    // ---- rendering ---------------------------------------------------------------------------------------------------

    private static final class Renderer {
        StringBuilder out = new StringBuilder();
        final String base;
        final boolean plain;
        final List<String[]> links;
        boolean inPre;
        final Deque<int[]> lists = new ArrayDeque<>();

        Renderer(String base, boolean plain, List<String[]> links) {
            this.base = base;
            this.plain = plain;
            this.links = links;
        }

        void node(HtmlNode n) {
            if (n.isText()) { text(n.text); return; }
            switch (n.name) {
                case "br": out.append('\n'); return;
                case "wbr": return;
                case "img": {
                    String alt = collapse(n.attr("alt")).trim();
                    if (!alt.isEmpty()) text("[изображение: " + alt + "]");
                    return;
                }
                case "hr":
                    blockBreak();
                    if (!plain) out.append("---");
                    blockBreak();
                    return;
                case "h1": case "h2": case "h3": case "h4": case "h5": case "h6": {
                    blockBreak();
                    if (!plain) {
                        int level = n.name.charAt(1) - '0';
                        for (int i = 0; i < level; i++) out.append('#');
                        out.append(' ');
                    }
                    children(n);
                    blockBreak();
                    return;
                }
                case "pre": {
                    blockBreak();
                    boolean prev = inPre;
                    inPre = true;
                    if (!plain) out.append("```\n");
                    children(n);
                    if (!plain) out.append("\n```");
                    inPre = prev;
                    blockBreak();
                    return;
                }
                case "code": case "kbd": case "samp": case "tt":
                    if (inPre || plain) { children(n); return; }
                    wrap(n, "`");
                    return;
                case "b": case "strong":
                    wrap(n, "**");
                    return;
                case "em": case "i":
                    wrap(n, "*");
                    return;
                case "a": link(n); return;
                case "ul": case "ol": case "menu": {
                    blockBreak();
                    lists.push(new int[]{"ol".equals(n.name) ? 1 : 0, 0});
                    children(n);
                    lists.pop();
                    blockBreak();
                    return;
                }
                case "li": {
                    if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
                    int[] list = lists.isEmpty() ? new int[]{0, 0} : lists.peek();
                    for (int i = 1; i < lists.size(); i++) out.append("  ");
                    if (list[0] == 1) { list[1]++; out.append(list[1]).append(". "); }
                    else out.append(plain ? "• " : "- ");
                    children(n);
                    out.append('\n');
                    return;
                }
                case "blockquote": {
                    String inner = tidy(capture(n));
                    blockBreak();
                    for (String line : inner.split("\n", -1)) {
                        if (!plain) out.append("> ");
                        out.append(line).append('\n');
                    }
                    blockBreak();
                    return;
                }
                case "table":
                    table(n);
                    return;
                default:
                    if (INLINE.contains(n.name)) children(n);
                    else { blockBreak(); children(n); blockBreak(); }
            }
        }

        void children(HtmlNode n) {
            for (HtmlNode c : n.children) node(c);
        }

        /** Renders children into a detached buffer (used for table cells and quotes). Shared link list is kept. */
        String capture(HtmlNode n) {
            StringBuilder saved = out;
            out = new StringBuilder();
            try {
                children(n);
                return out.toString();
            } finally {
                out = saved;
            }
        }

        void text(String s) {
            if (inPre) { out.append(s); return; }
            String c = WS.matcher(s).replaceAll(" ");
            if (c.isEmpty()) return;
            int len = out.length();
            char last = len == 0 ? '\n' : out.charAt(len - 1);
            if (c.charAt(0) == ' ' && (last == ' ' || last == '\n')) c = c.substring(1);
            out.append(c);
        }

        void blockBreak() {
            int len = out.length();
            while (len > 0 && out.charAt(len - 1) == ' ') out.setLength(--len);
            if (len == 0) return;
            if (out.charAt(len - 1) != '\n') out.append("\n\n");
            else if (len < 2 || out.charAt(len - 2) != '\n') out.append('\n');
        }

        void wrap(HtmlNode n, String marker) {
            int start = out.length();
            children(n);
            String span = out.substring(start);
            int a = 0;
            while (a < span.length() && span.charAt(a) == ' ') a++;
            int b = span.length();
            while (b > a && span.charAt(b - 1) == ' ') b--;
            if (a == b) return;
            String lead = span.substring(0, a), core = span.substring(a, b), trail = span.substring(b);
            out.setLength(start);
            if (plain) out.append(lead).append(core).append(trail);
            else out.append(lead).append(marker).append(core).append(marker).append(trail);
        }

        void link(HtmlNode n) {
            String abs = resolve(base, n.attr("href"));
            int start = out.length();
            children(n);
            String text = collapse(out.substring(start)).trim();
            if (abs == null) return;
            if (links.size() < MAX_LINKS) links.add(new String[]{text.isEmpty() ? abs : text, abs});
            if (!plain && !text.isEmpty() && !inPre) {
                out.setLength(start);
                out.append('[').append(text).append("](").append(abs).append(')');
            }
        }

        void table(HtmlNode t) {
            blockBreak();
            List<List<String>> rows = new ArrayList<>();
            for (HtmlNode row : rowNodes(t)) {
                List<String> cells = new ArrayList<>();
                for (HtmlNode cell : row.children) {
                    if (cell.isText() || !("td".equals(cell.name) || "th".equals(cell.name))) continue;
                    cells.add(capture(cell));
                }
                if (!cells.isEmpty()) rows.add(cells);
            }
            if (rows.isEmpty()) {
                children(t);
                blockBreak();
                return;
            }
            int cols = 0;
            for (List<String> r : rows) cols = Math.max(cols, r.size());
            if (cols <= 1) {
                // one-column tables are layout wrappers: their cells become ordinary blocks
                for (List<String> r : rows) for (String s : r) {
                    String b = tidy(s);
                    if (!b.isEmpty()) { out.append(b); blockBreak(); }
                }
                return;
            }
            List<List<String>> grid = new ArrayList<>();
            for (List<String> r : rows) {
                List<String> flat = new ArrayList<>();
                for (String s : r) flat.add(tidy(s).replaceAll("\\s*\\n\\s*", " / ").replace('|', '/').trim());
                grid.add(flat);
            }
            int[] w = new int[cols];
            for (List<String> r : grid) for (int c = 0; c < r.size(); c++) w[c] = Math.max(w[c], r.get(c).length());
            for (int ri = 0; ri < grid.size(); ri++) {
                List<String> r = grid.get(ri);
                out.append('|');
                for (int c = 0; c < cols; c++) {
                    String cell = c < r.size() ? r.get(c) : "";
                    out.append(' ').append(cell);
                    for (int p = cell.length(); p < w[c]; p++) out.append(' ');
                    out.append(" |");
                }
                out.append('\n');
                if (ri == 0) {
                    out.append('|');
                    for (int c = 0; c < cols; c++) {
                        for (int p = 0; p < w[c] + 2; p++) out.append('-');
                        out.append('|');
                    }
                    out.append('\n');
                }
            }
            blockBreak();
        }

        private List<HtmlNode> rowNodes(HtmlNode t) {
            List<HtmlNode> rows = new ArrayList<>();
            for (HtmlNode c : t.children) {
                if (c.isText()) continue;
                if ("tr".equals(c.name)) rows.add(c);
                else if ("thead".equals(c.name) || "tbody".equals(c.name) || "tfoot".equals(c.name)) {
                    for (HtmlNode r : c.children) if (!r.isText() && "tr".equals(r.name)) rows.add(r);
                }
            }
            return rows;
        }
    }

    static String tidy(String s) {
        String[] lines = s.split("\n", -1);
        StringBuilder sb = new StringBuilder(s.length());
        for (String line : lines) {
            int e = line.length();
            while (e > 0 && line.charAt(e - 1) == ' ') e--;
            sb.append(line, 0, e).append('\n');
        }
        return NEWLINES.matcher(sb.toString()).replaceAll("\n\n").trim();
    }

    static String collapse(String s) { return WS.matcher(s).replaceAll(" "); }

    /** Text of an HTML fragment (tags removed, entities decoded, whitespace collapsed). */
    public static String stripTags(String html) {
        return collapse(Entities.decode(html.replaceAll("<[^>]*>", ""))).trim();
    }

    /** Absolute http(s) URL for an href, or null for anchors, script/mail links and unresolvable values. */
    public static String resolve(String base, String href) {
        String h = href == null ? "" : href.trim();
        if (h.isEmpty() || h.startsWith("#")) return null;
        String low = h.toLowerCase(Locale.ROOT);
        if (low.startsWith("javascript:") || low.startsWith("mailto:") || low.startsWith("tel:") || low.startsWith("data:")) return null;
        try {
            URI u = new URI(h.replace(" ", "%20"));
            if (!u.isAbsolute()) {
                if (base == null || base.isEmpty()) return null;
                u = new URI(base).resolve(u);
            }
            String s = u.toString();
            return s.startsWith("http://") || s.startsWith("https://") ? s : null;
        } catch (Exception e) {
            return null;
        }
    }
}
