package app.jarvis.core.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.log.Journal;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class ModelCatalogTest {
    private File dir;
    private long now;
    private final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> journal = new ArrayList<>();
    private final Map<String, List<ModelInfo>> data = new HashMap<>();
    private LlmException failWith;
    private final List<Runnable> pending = new ArrayList<>();
    private final Executor queued = new Executor() { @Override public void execute(Runnable r) { pending.add(r); } };
    private final Executor direct = new Executor() { @Override public void execute(Runnable r) { r.run(); } };
    private final ModelCatalog.Fetcher fetcher = new ModelCatalog.Fetcher() {
        @Override public List<ModelInfo> list(String providerId) throws LlmException {
            calls.add(providerId);
            if (failWith != null) throw failWith;
            List<ModelInfo> l = data.get(providerId);
            if (l == null) throw new LlmException(LlmException.Kind.NOT_FOUND, 404, "нет", "no data", 0, null, null);
            return l;
        }
    };
    private final Journal rec = new Journal() {
        @Override public void log(String category, Journal.Level level, String message, String runId, JSONObject details, long durationMs) {
            journal.add(level + " " + category + " " + message + " " + (details == null ? "" : details.toString()));
        }
    };

    @Before public void setUp() throws Exception {
        dir = Files.createTempDirectory("catalog-test").toFile();
        now = 1_000_000L;
        data.clear();
        calls.clear();
        journal.clear();
        pending.clear();
        failWith = null;
        data.put("openrouter", OpenAiClient.parseModels(ModelFixtures.read("openrouter_sample.json")));
        data.put("deepseek", OpenAiClient.parseModels(ModelFixtures.read("deepseek_models.json")));
    }

    private ModelCatalog catalog(Executor ex) {
        return new ModelCatalog(dir, fetcher, rec, new java.util.function.LongSupplier() { @Override public long getAsLong() { return now; } }, ex);
    }

    private static ModelInfo model(String id, int ctx) { return new ModelInfo(id, null, "test", ctx); }

    @Test public void providerOwnValuesWinOverOpenRouterAndOpenRouterFillsTheRest() {
        data.put("deepseek", Collections.singletonList(model("deepseek-v4-flash", 999)));
        ModelCatalog c = catalog(direct);
        c.models("deepseek");
        ModelCaps m = c.caps("deepseek", "deepseek-v4-flash");
        assertEquals(999, m.contextTokens);
        assertEquals(Cap.YES, m.tools);
        assertEquals(Cap.YES, m.temperature);
        assertEquals(ModelCaps.Source.PROVIDER, m.source);
    }

    @Test public void matchesByPartAfterVendorSlashAndLabelsOpenRouter() {
        data.put("inception", Collections.singletonList(model("mercury-3", 0)));
        ModelCatalog c = catalog(direct);
        c.models("inception");
        ModelCaps m = c.caps("inception", "mercury-3");
        assertEquals(1_000_000, m.contextTokens);
        assertEquals(131_072, m.maxOutputTokens);
        assertEquals(ModelCaps.Source.OPENROUTER, m.source);
    }

    @Test public void builtinTableFillsDeepSeekWhenNoCatalogIsLoaded() {
        ModelCatalog c = catalog(queued);
        ModelCaps m = c.caps("deepseek", "deepseek-flash");
        assertEquals(1_048_576, m.contextTokens);
        assertEquals(ModelCaps.Source.BUILTIN, m.source);
        assertEquals(Cap.UNKNOWN, m.temperature);
    }

    @Test public void unknownModelStaysUnknownWithoutInventedValues() {
        ModelCatalog c = catalog(direct);
        c.models("inception");
        ModelCaps m = c.caps("inception", "never-heard-of-it");
        assertEquals(0, m.contextTokens);
        assertEquals(Cap.UNKNOWN, m.tools);
        assertEquals(ModelCaps.Source.UNKNOWN, m.source);
    }

    @Test public void learnedRefusalOverridesCatalogAndIsMarkedLearned() throws Exception {
        ModelCatalog c = catalog(direct);
        c.models("openrouter");
        ParamMemory pm = new ParamMemory(new File(dir, "params.json"));
        pm.learn("openrouter", "deepseek/deepseek-chat", "temperature");
        c.setParamMemory(pm);
        ModelCaps m = c.caps("openrouter", "deepseek/deepseek-chat");
        assertEquals(Cap.NO, m.temperature);
        assertTrue(m.rejectedParams.contains("temperature"));
        assertEquals(ModelCaps.Source.LEARNED, m.source);
    }

    @Test public void staleCacheIsReturnedAtOnceAndRefreshedInBackground() {
        ModelCatalog c = catalog(queued);
        c.models("deepseek");
        assertEquals(1, pending.size());
        pending.remove(0).run();
        assertEquals(Collections.singletonList("deepseek"), calls);
        assertEquals(2, c.models("deepseek").size());
        now += ModelCatalog.TTL_MS + 1;
        assertEquals(2, c.models("deepseek").size());
        assertEquals(1, pending.size());
        assertEquals(1, calls.size());
        pending.remove(0).run();
        assertEquals(2, calls.size());
    }

    @Test public void failureIsJournaledWithHttpStatusAndRetriedOnlyAfterBackoff() {
        failWith = new LlmException(LlmException.Kind.AUTH, 401, "Неверный, просроченный или отозванный API-ключ", "HTTP 401: bad key", 0, null, null);
        ModelCatalog c = catalog(queued);
        c.models("deepseek");
        pending.remove(0).run();
        ModelCatalog.Status st = c.status("deepseek");
        assertEquals(401, st.lastHttp);
        assertEquals("Неверный, просроченный или отозванный API-ключ", st.lastError);
        assertFalse(st.loading);
        assertEquals(1, journal.size());
        assertTrue(journal.get(0), journal.get(0).startsWith("ERROR llm "));
        assertTrue(journal.get(0), journal.get(0).contains("HTTP 401"));
        assertTrue(journal.get(0), journal.get(0).contains("HTTP 401: bad key"));

        now += 5 * 60 * 1000L;
        c.models("deepseek");
        assertTrue(pending.isEmpty());
        now += ModelCatalog.RETRY_MS;
        c.models("deepseek");
        assertEquals(1, pending.size());
    }

    @Test public void diskCacheSurvivesRestartWithoutNetworkWithinTtl() {
        ModelCatalog first = catalog(direct);
        first.models("deepseek");
        assertEquals(2, first.models("deepseek").size());
        calls.clear();
        failWith = new LlmException(LlmException.Kind.NETWORK, 0, "Нет соединения с сервером", "x", 0, null, null);
        ModelCatalog second = catalog(direct);
        assertEquals(2, second.models("deepseek").size());
        assertTrue(calls.isEmpty());
        assertEquals("deepseek-flash", second.models("deepseek").get(0).id);
        assertEquals(1_048_576, second.models("deepseek").get(0).caps.contextTokens);
    }

    @Test public void corruptCacheIsJournaledAndReloaded() throws Exception {
        Files.write(new File(dir, "deepseek.json").toPath(), "{not json".getBytes("UTF-8"));
        ModelCatalog c = catalog(direct);
        assertEquals(2, c.models("deepseek").size());
        assertEquals(Collections.singletonList("deepseek"), calls);
        assertTrue(journal.toString(), journal.toString().contains("WARN llm"));
        assertNotNull(c.status("deepseek"));
    }
}
