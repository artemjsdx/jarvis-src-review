package app.jarvis.core.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Element or text node of the tolerant HTML tree built by {@link HtmlParser}. Names are lower-case; text nodes are "#text". */
public final class HtmlNode {
    public static final String TEXT = "#text", DOCUMENT = "#document";

    public final String name;
    public final Map<String, String> attrs;
    public final List<HtmlNode> children = new ArrayList<>(2);
    public HtmlNode parent;
    public String text = "";
    /** Cached length of visible text outside links, filled by the converter. */
    int weight = -1;

    HtmlNode(String name, Map<String, String> attrs) {
        this.name = name;
        this.attrs = attrs;
    }

    public boolean isText() { return TEXT.equals(name); }

    public String attr(String key) {
        String v = attrs.get(key);
        return v == null ? "" : v;
    }

    /** Space-separated id and class values, lower-case, for keyword matching. */
    public String idAndClass() { return (attr("id") + " " + attr("class")).toLowerCase(java.util.Locale.ROOT); }
}
