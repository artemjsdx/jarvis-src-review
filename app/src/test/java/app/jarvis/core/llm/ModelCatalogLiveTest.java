package app.jarvis.core.llm;

import static org.junit.Assert.assertTrue;

import app.jarvis.core.log.Journal;
import app.jarvis.core.util.CancelToken;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;

/** Real GET /models of OpenRouter (public, no key, no tokens spent). Runs only with JV_LIVE_MODELS=1. */
public class ModelCatalogLiveTest {
    @Test public void loadsRealOpenRouterCatalogAndPrintsCapabilities() throws Exception {
        Assume.assumeTrue("JV_LIVE_MODELS=1 not set", "1".equals(System.getenv("JV_LIVE_MODELS")));
        LlmConfig cfg = new LlmConfig();
        cfg.name = "OpenRouter";
        cfg.providerId = "openrouter";
        cfg.baseUrl = "https://openrouter.ai/api/v1";
        cfg.connectTimeoutMs = 20_000;
        cfg.idleTimeoutMs = 120_000;
        long t0 = System.currentTimeMillis();
        List<ModelInfo> ms = new OpenAiClient(Journal.NOP).listModels(cfg, new CancelToken());
        System.out.println("[live] openrouter /models: " + ms.size() + " models in " + (System.currentTimeMillis() - t0) + " ms");
        assertTrue(ms.size() > 100);
        String[] want = {"deepseek/deepseek-v4-flash", "inception/mercury-3", "openai/gpt-4o-mini", "particle-ai/particle-1", "deepseek/deepseek-chat"};
        for (String id : want) {
            ModelInfo m = null;
            for (ModelInfo x : ms) if (x.id.equals(id)) { m = x; break; }
            if (m == null) { System.out.println("[live] " + id + ": not in catalog"); continue; }
            ModelCaps c = m.caps;
            System.out.println("[live] " + id + " | ctx=" + c.contextTokens + " out=" + c.maxOutputTokens + " tools=" + c.tools
                + " temperature=" + c.temperature + " reasoning=" + c.reasoning + " vision=" + c.vision
                + " in/out per 1M=" + c.priceInPerM + "/" + c.priceOutPerM + " source=" + c.source.key);
        }
    }
}
