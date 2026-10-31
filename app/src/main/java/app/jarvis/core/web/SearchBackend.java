package app.jarvis.core.web;

import app.jarvis.core.util.CancelToken;
import java.util.List;

public interface SearchBackend {
    /** Settings value that selects this backend, e.g. "brave". */
    String id();

    /** Name shown to the user in results and errors. */
    String label();

    /** Returns up to request.limit hits, or throws WebException (cooldown-worthy failures are flagged inside it). */
    List<SearchHit> search(SearchRequest request, CancelToken cancel) throws WebException;
}
