package app.jarvis.core.web;

import app.jarvis.core.util.CancelToken;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Runs several queries at once. Each query walks the backend chain in order, skipping backends on cooldown; a backend that
 * fails with a cooldown-worthy error is skipped for {@link #COOLDOWN_MS}. Every query has its own timeout and failures are
 * reported per query. Hits already listed for an earlier query are dropped from later ones.
 */
public final class SearchRouter {
    public static final long COOLDOWN_MS = 10 * 60_000L;
    public static final long QUERY_TIMEOUT_MS = 12_000L, OVERALL_TIMEOUT_MS = 20_000L;

    public static final class QueryOutcome {
        public final String query, backend, error;
        public final List<SearchHit> hits;

        QueryOutcome(String query, String backend, List<SearchHit> hits, String error) {
            this.query = query;
            this.backend = backend;
            this.hits = hits;
            this.error = error;
        }

        public boolean ok() { return error.isEmpty(); }
    }

    public static final class Report {
        public final List<QueryOutcome> results;
        /** Ids of the backends that answered, in the order they were used; empty when nothing answered. */
        public final List<String> backends;
        public final long ms;

        Report(List<QueryOutcome> results, List<String> backends, long ms) {
            this.results = results;
            this.backends = backends;
            this.ms = ms;
        }

        public boolean anyOk() {
            for (QueryOutcome q : results) if (q.ok()) return true;
            return false;
        }
    }

    private final LongSupplier clock;
    private final long queryTimeoutMs, overallTimeoutMs;
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(new ThreadFactory() {
        private int n;
        @Override public synchronized Thread newThread(Runnable r) {
            Thread t = new Thread(r, "jv-web-search-" + (++n));
            t.setDaemon(true);
            return t;
        }
    });

    public SearchRouter(LongSupplier clockMs) {
        this(clockMs, QUERY_TIMEOUT_MS, OVERALL_TIMEOUT_MS);
    }

    SearchRouter(LongSupplier clockMs, long queryTimeoutMs, long overallTimeoutMs) {
        this.clock = clockMs;
        this.queryTimeoutMs = queryTimeoutMs;
        this.overallTimeoutMs = overallTimeoutMs;
    }

    public Report search(final List<SearchBackend> chain, List<String> queries, final int limit, final String recency, final String site,
                         CancelToken cancel) {
        final long t0 = clock.getAsLong();
        final long overall = t0 + overallTimeoutMs;
        List<Future<QueryOutcome>> futures = new ArrayList<>();
        List<CancelToken> tokens = new ArrayList<>();
        for (final String q : queries) {
            final CancelToken qt = cancel.child();
            tokens.add(qt);
            futures.add(pool.submit(new Callable<QueryOutcome>() {
                @Override public QueryOutcome call() {
                    return runChain(chain, new SearchRequest(q, limit, recency, site), qt, clock.getAsLong() + queryTimeoutMs);
                }
            }));
        }
        List<QueryOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            Future<QueryOutcome> f = futures.get(i);
            String q = queries.get(i);
            try {
                long left = Math.max(1, overall - clock.getAsLong());
                outcomes.add(f.get(left, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                tokens.get(i).cancel("overall timeout");
                f.cancel(true);
                outcomes.add(new QueryOutcome(q, "", Collections.<SearchHit>emptyList(), "общий тайм-аут поиска"));
            } catch (ExecutionException e) {
                outcomes.add(new QueryOutcome(q, "", Collections.<SearchHit>emptyList(), String.valueOf(e.getCause())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancel.cancel("interrupted");
                outcomes.add(new QueryOutcome(q, "", Collections.<SearchHit>emptyList(), "поиск прерван"));
            }
        }
        return new Report(dedupe(outcomes), answering(outcomes), clock.getAsLong() - t0);
    }

    private QueryOutcome runChain(List<SearchBackend> chain, SearchRequest req, CancelToken qt, long deadline) {
        if (chain.isEmpty()) return new QueryOutcome(req.query, "", Collections.<SearchHit>emptyList(), "не настроен ни один поисковый сервис");
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            final SearchBackend b = chain.get(i);
            if (qt.isCancelled()) return new QueryOutcome(req.query, "", Collections.<SearchHit>emptyList(), "отменено");
            if (inCooldown(b.id())) { errors.add(b.label() + ": временно пропущен"); continue; }
            long left = deadline - clock.getAsLong();
            if (left <= 0) { errors.add("тайм-аут запроса"); break; }
            long attempt = i < chain.size() - 1 ? left * 6 / 10 : left;
            final CancelToken child = qt.child();
            Future<List<SearchHit>> f = pool.submit(new Callable<List<SearchHit>>() {
                @Override public List<SearchHit> call() throws WebException { return b.search(req, child); }
            });
            try {
                return new QueryOutcome(req.query, b.id(), f.get(attempt, TimeUnit.MILLISECONDS), "");
            } catch (TimeoutException e) {
                child.cancel("timeout");
                f.cancel(true);
                cool(b.id());
                errors.add(WebException.timeout(b.label()).userMessage);
            } catch (ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof CancelToken.CancelledException) return new QueryOutcome(req.query, "", Collections.<SearchHit>emptyList(), "отменено");
                if (c instanceof WebException) {
                    WebException w = (WebException) c;
                    if (w.cooldown) cool(b.id());
                    errors.add(w.userMessage);
                } else {
                    errors.add(b.label() + ": " + c);
                }
            } catch (InterruptedException e) {
                child.cancel("interrupted");
                Thread.currentThread().interrupt();
                return new QueryOutcome(req.query, "", Collections.<SearchHit>emptyList(), "отменено");
            }
        }
        return new QueryOutcome(req.query, "", Collections.<SearchHit>emptyList(), String.join("; ", errors));
    }

    private boolean inCooldown(String id) {
        Long until = cooldownUntil.get(id);
        return until != null && clock.getAsLong() < until;
    }

    private void cool(String id) { cooldownUntil.put(id, clock.getAsLong() + COOLDOWN_MS); }

    private static List<QueryOutcome> dedupe(List<QueryOutcome> in) {
        Set<String> seen = new HashSet<>();
        List<QueryOutcome> out = new ArrayList<>();
        for (QueryOutcome q : in) {
            List<SearchHit> kept = new ArrayList<>();
            for (SearchHit h : q.hits) if (seen.add(h.url)) kept.add(h);
            out.add(new QueryOutcome(q.query, q.backend, kept, q.error));
        }
        return out;
    }

    private static List<String> answering(List<QueryOutcome> outcomes) {
        Set<String> ids = new LinkedHashSet<>();
        for (QueryOutcome q : outcomes) if (q.ok() && !q.backend.isEmpty()) ids.add(q.backend);
        return new ArrayList<>(ids);
    }

    public void shutdown() { pool.shutdownNow(); }
}
