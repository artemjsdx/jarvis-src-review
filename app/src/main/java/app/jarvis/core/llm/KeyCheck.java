package app.jarvis.core.llm;

import app.jarvis.core.log.Journal;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.J;
import org.json.JSONObject;

/**
 * Proves that a saved API key is accepted, with one cheap authenticated GET. OpenRouter's /models is public (a wrong key
 * gets 200 there), so OpenRouter is asked for its key information instead. Spends no model tokens.
 */
public final class KeyCheck {
    private KeyCheck() { }

    public static final class Result {
        public final boolean ok;
        public final long latencyMs;
        public final String path;
        /** Failure class (AUTH, PAYMENT, GEO_BLOCK, NETWORK, …); null on success. */
        public final LlmException.Kind kind;
        public final int status;
        public final String userMessage;
        public final String technical;

        private Result(boolean ok, long latencyMs, String path, LlmException.Kind kind, int status, String userMessage, String technical) {
            this.ok = ok; this.latencyMs = latencyMs; this.path = path; this.kind = kind;
            this.status = status; this.userMessage = userMessage; this.technical = technical;
        }

        public static Result passed(long latencyMs, String path) {
            return new Result(true, latencyMs, path, null, 200, "Ключ принят", "");
        }

        public static Result failed(LlmException e, String path) {
            return new Result(false, -1, path, e.kind, e.status, e.userMessage, e.technical);
        }
    }

    /** The endpoint that proves the key for a provider. */
    public static String probePath(String providerId) {
        return ModelCatalog.OPENROUTER.equals(providerId) ? "/key" : "/models";
    }

    /** One GET with the saved key, off the UI thread (the caller owns the thread). Writes exactly one journal line. */
    public static Result run(OpenAiClient client, LlmConfig cfg, String providerId, Journal journal, CancelToken cancel) {
        String path = probePath(providerId);
        long t0 = System.nanoTime();
        Result r;
        try {
            client.getJson(cfg, path, cancel);
            r = Result.passed((System.nanoTime() - t0) / 1_000_000L, path);
        } catch (LlmException e) {
            r = Result.failed(e, path);
        } catch (RuntimeException e) {
            r = Result.failed(new LlmException(LlmException.Kind.PROTOCOL, 0, "Неожиданная ошибка проверки",
                e.getClass().getSimpleName() + ": " + e.getMessage(), 0, null, e), path);
        }
        JSONObject d = new JSONObject();
        J.put(d, "provider", providerId);
        J.put(d, "path", path);
        J.put(d, "http", r.status);
        if (r.ok) {
            journal.log(Journal.Cat.LLM, Journal.Level.INFO, "Проверка ключа (" + providerId + "): ключ принят, " + r.latencyMs + " мс", null, d, r.latencyMs);
        } else {
            J.put(d, "kind", r.kind.name());
            J.put(d, "technical", r.technical);
            journal.log(Journal.Cat.LLM, Journal.Level.WARN, "Проверка ключа (" + providerId + "): " + r.userMessage
                + (r.status > 0 ? " [HTTP " + r.status + "]" : ""), null, d, -1);
        }
        return r;
    }
}
