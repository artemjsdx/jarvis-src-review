package app.jarvis.core.chat;

/**
 * Query state of the sessions drawer search. Each change gets a new token; results computed for an older token must be
 * dropped, so a slow query can never overwrite the list the user is looking at.
 */
public final class SessionSearch {
    private String query = "";
    private int token;

    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    }

    /** @return true when the normalized query changed (and a new token was issued). */
    public boolean update(String raw) {
        String next = normalize(raw);
        if (next.equals(query)) return false;
        query = next;
        token++;
        return true;
    }

    public String query() { return query; }

    public boolean filtering() { return !query.isEmpty(); }

    public int token() { return token; }

    public boolean isLatest(int candidate) { return candidate == token; }
}
