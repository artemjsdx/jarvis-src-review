package app.jarvis.core.web;

import org.json.JSONObject;

/** One search result in the common shape returned by every backend. */
public final class SearchHit {
    public final String title, url, snippet, source, published;

    public SearchHit(String title, String url, String snippet, String published) {
        this.title = title == null ? "" : title;
        this.url = UrlNormalizer.normalize(url == null ? "" : url);
        this.snippet = snippet == null ? "" : snippet;
        this.source = UrlNormalizer.host(this.url);
        this.published = published == null ? "" : published;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        put(o, "title", title);
        put(o, "url", url);
        put(o, "snippet", snippet);
        put(o, "source", source);
        if (!published.isEmpty()) put(o, "published", published);
        return o;
    }

    private static void put(JSONObject o, String k, String v) {
        try { o.put(k, v); } catch (org.json.JSONException ignored) { }
    }
}
