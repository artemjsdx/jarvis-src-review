package app.jarvis.core.web;

/** Parameters of one query. {@code recency} is "" or day, week, month, year; {@code site} is "" or a host name. */
public final class SearchRequest {
    public final String query;
    public final int limit;
    public final String recency;
    public final String site;

    public SearchRequest(String query, int limit, String recency, String site) {
        this.query = query;
        this.limit = limit;
        this.recency = recency == null ? "" : recency;
        this.site = site == null ? "" : site.trim();
    }

    /** The query text sent to backends; a site restriction is expressed as the standard "site:" operator. */
    public String text() {
        return site.isEmpty() ? query : "site:" + site + " " + query;
    }
}
