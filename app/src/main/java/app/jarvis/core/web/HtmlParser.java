package app.jarvis.core.web;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tolerant HTML tree builder: never throws, closes unclosed elements, applies the implied-end-tag rules that matter for
 * real pages (p, li, dt/dd, tr, td/th, option) and caps nesting depth so hostile markup cannot exhaust the stack.
 */
public final class HtmlParser {
    private HtmlParser() { }

    static final int MAX_DEPTH = 400;

    private static final Set<String> VOID = set("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
        "param", "source", "track", "wbr", "keygen");
    private static final Set<String> RAW_TEXT = set("script", "style", "title", "textarea", "xmp");
    private static final Set<String> RAW_NO_ENTITIES = set("script", "style", "xmp");
    private static final Set<String> CLOSES_P = set("address", "article", "aside", "blockquote", "details", "dialog", "div", "dl",
        "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "main", "menu",
        "nav", "ol", "p", "pre", "section", "table", "ul", "li", "dd", "dt");
    private static final Set<String> SCOPE_BOUNDARY = set("applet", "caption", "html", "table", "td", "th", "marquee", "object",
        "template", "button");

    private static Set<String> set(String... s) { return new HashSet<>(Arrays.asList(s)); }

    public static HtmlNode parse(String src) {
        HtmlNode doc = new HtmlNode(HtmlNode.DOCUMENT, new LinkedHashMap<String, String>());
        Deque<HtmlNode> stack = new ArrayDeque<>();
        stack.push(doc);
        int i = 0, n = src.length();
        Tag tag = new Tag();
        while (i < n) {
            int lt = src.indexOf('<', i);
            if (lt < 0) { addText(stack.peek(), src.substring(i)); break; }
            if (lt > i) addText(stack.peek(), src.substring(i, lt));
            i = lt;
            if (src.startsWith("<!--", i)) {
                int e = src.indexOf("-->", i + 4);
                i = e < 0 ? n : e + 3;
                continue;
            }
            char next = i + 1 < n ? src.charAt(i + 1) : '\0';
            if (next == '!' || next == '?') {
                int e = src.indexOf('>', i);
                i = e < 0 ? n : e + 1;
                continue;
            }
            if (next == '/') {
                int e = src.indexOf('>', i);
                if (e < 0 || i + 2 >= n || !isAsciiLetter(src.charAt(i + 2))) { addText(stack.peek(), "<"); i++; continue; }
                closeElement(stack, tagName(src.substring(i + 2, e)));
                i = e + 1;
                continue;
            }
            if (!isAsciiLetter(next)) {
                addText(stack.peek(), "<");
                i++;
                continue;
            }
            int after = scanStartTag(src, i, tag);
            if (after < 0) { addText(stack.peek(), "<"); i++; continue; }
            i = after;
            HtmlNode el = openElement(stack, tag);
            if (RAW_TEXT.contains(tag.name) && !tag.selfClosing) {
                int end = indexOfIgnoreCase(src, "</" + tag.name, i);
                int stop = end < 0 ? n : end;
                if (stop > i) addRaw(el, RAW_NO_ENTITIES.contains(tag.name) ? src.substring(i, stop) : Entities.decode(src.substring(i, stop)));
                int gt = end < 0 ? -1 : src.indexOf('>', end);
                i = gt < 0 ? n : gt + 1;
                if (stack.peek() == el) stack.pop();
            }
        }
        return doc;
    }

    /** Opens an element applying implied end tags; it is attached to the current parent, pushed unless void or self-closed. */
    private static HtmlNode openElement(Deque<HtmlNode> stack, Tag tag) {
        impliedEnd(stack, tag.name);
        HtmlNode parent = stack.peek();
        HtmlNode el = new HtmlNode(tag.name, tag.attrs);
        el.parent = parent;
        parent.children.add(el);
        boolean selfClosedHtml = tag.selfClosing && !RAW_TEXT.contains(tag.name);
        if (stack.size() < MAX_DEPTH && !VOID.contains(tag.name) && !selfClosedHtml) stack.push(el);
        return el;
    }

    private static void impliedEnd(Deque<HtmlNode> stack, String name) {
        if (CLOSES_P.contains(name)) closeOpenWithin(stack, "p", SCOPE_BOUNDARY);
        if ("li".equals(name)) closeOpenWithin(stack, "li", set("ul", "ol", "menu"));
        if ("dt".equals(name) || "dd".equals(name)) {
            closeOpenWithin(stack, "dt", set("dl"));
            closeOpenWithin(stack, "dd", set("dl"));
        }
        if ("tr".equals(name)) closeOpenWithin(stack, "tr", set("table", "thead", "tbody", "tfoot"));
        if ("td".equals(name) || "th".equals(name)) {
            closeOpenWithin(stack, "td", set("tr", "table"));
            closeOpenWithin(stack, "th", set("tr", "table"));
        }
        if ("thead".equals(name) || "tbody".equals(name) || "tfoot".equals(name)) {
            closeOpenWithin(stack, "tr", set("table"));
            closeOpenWithin(stack, "thead", set("table"));
            closeOpenWithin(stack, "tbody", set("table"));
            closeOpenWithin(stack, "tfoot", set("table"));
        }
        if ("option".equals(name)) closeOpenWithin(stack, "option", set("select", "datalist"));
    }

    /** Pops up to and including the nearest open element named {@code name}, unless a boundary element comes first. */
    private static void closeOpenWithin(Deque<HtmlNode> stack, String name, Set<String> boundaries) {
        int depth = 0;
        for (HtmlNode e : stack) {
            if (e.name.equals(name)) {
                for (int k = 0; k <= depth; k++) stack.pop();
                return;
            }
            if (boundaries.contains(e.name)) return;
            depth++;
        }
    }

    /** Explicit end tag: closes the nearest matching open element and everything opened inside it; ignored if none is open. */
    private static void closeElement(Deque<HtmlNode> stack, String name) {
        closeOpenWithin(stack, name, set(HtmlNode.DOCUMENT));
    }

    private static void addText(HtmlNode parent, String raw) {
        if (!raw.isEmpty()) addRaw(parent, Entities.decode(raw));
    }

    private static void addRaw(HtmlNode parent, String t) {
        if (t.isEmpty()) return;
        int last = parent.children.size() - 1;
        if (last >= 0 && parent.children.get(last).isText()) {
            HtmlNode prev = parent.children.get(last);
            prev.text = prev.text + t;
            return;
        }
        HtmlNode tn = new HtmlNode(HtmlNode.TEXT, new LinkedHashMap<String, String>());
        tn.text = t;
        tn.parent = parent;
        parent.children.add(tn);
    }

    private static final class Tag {
        String name;
        Map<String, String> attrs;
        boolean selfClosing;
    }

    /** Scans "<name attr=value ...>" starting at the '<'; returns the index after '>' or -1 when unterminated. */
    private static int scanStartTag(String src, int start, Tag out) {
        int n = src.length();
        int i = start + 1;
        int nameStart = i;
        while (i < n && !isSpace(src.charAt(i)) && src.charAt(i) != '>' && src.charAt(i) != '/') i++;
        out.name = src.substring(nameStart, i).toLowerCase(Locale.ROOT);
        out.attrs = new LinkedHashMap<>();
        out.selfClosing = false;
        while (i < n) {
            char c = src.charAt(i);
            if (isSpace(c)) { i++; continue; }
            if (c == '>') return i + 1;
            if (c == '/') {
                if (i + 1 < n && src.charAt(i + 1) == '>') { out.selfClosing = true; return i + 2; }
                i++;
                continue;
            }
            int as = i;
            while (i < n && !isSpace(src.charAt(i)) && src.charAt(i) != '>' && src.charAt(i) != '=' && !(src.charAt(i) == '/' && i + 1 < n && src.charAt(i + 1) == '>')) i++;
            String attr = src.substring(as, i).toLowerCase(Locale.ROOT);
            int k = i;
            while (k < n && isSpace(src.charAt(k))) k++;
            String value = "";
            if (k < n && src.charAt(k) == '=') {
                k++;
                while (k < n && isSpace(src.charAt(k))) k++;
                if (k < n && (src.charAt(k) == '"' || src.charAt(k) == '\'')) {
                    char q = src.charAt(k);
                    int e = src.indexOf(q, k + 1);
                    if (e < 0) return -1;
                    value = src.substring(k + 1, e);
                    i = e + 1;
                } else {
                    int vs = k;
                    while (k < n && !isSpace(src.charAt(k)) && src.charAt(k) != '>') k++;
                    value = src.substring(vs, k);
                    i = k;
                }
                value = Entities.decode(value);
            }
            if (!attr.isEmpty() && !out.attrs.containsKey(attr)) out.attrs.put(attr, value);
        }
        return -1;
    }

    private static String tagName(String s) {
        int i = 0;
        while (i < s.length() && !isSpace(s.charAt(i))) i++;
        return s.substring(0, i).trim().toLowerCase(Locale.ROOT);
    }

    private static int indexOfIgnoreCase(String src, String needle, int from) {
        int n = needle.length();
        for (int i = from; i + n <= src.length(); i++) {
            if (src.regionMatches(true, i, needle, 0, n)) return i;
        }
        return -1;
    }

    private static boolean isAsciiLetter(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }

    private static boolean isSpace(char c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f'; }
}
