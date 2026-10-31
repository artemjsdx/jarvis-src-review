package app.jarvis.core.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class ModelCapsParserTest {
    private static ModelInfo find(List<ModelInfo> ms, String id) {
        for (ModelInfo m : ms) if (m.id.equals(id)) return m;
        throw new AssertionError("no model " + id);
    }

    @Test public void openRouterEntriesGiveContextOutputToolsTemperatureReasoningPricesAndModalities() throws Exception {
        List<ModelInfo> ms = OpenAiClient.parseModels(ModelFixtures.read("openrouter_sample.json"));
        ModelCaps flash = find(ms, "deepseek/deepseek-v4-flash").caps;
        assertEquals(1_048_576, flash.contextTokens);
        assertEquals(943_717, flash.maxOutputTokens);
        assertEquals(Cap.YES, flash.tools);
        assertEquals(Cap.YES, flash.temperature);
        assertEquals(Cap.YES, flash.reasoning);
        assertEquals(Cap.NO, flash.vision);
        assertEquals(0.091, flash.priceInPerM, 1e-9);
        assertEquals(0.182, flash.priceOutPerM, 1e-9);
        assertEquals(ModelCaps.Source.PROVIDER, flash.source);
        assertEquals(Cap.NO, flash.audioOut);

        ModelCaps mercury = find(ms, "inception/mercury-3").caps;
        assertEquals(1_000_000, mercury.contextTokens);
        assertEquals(131_072, mercury.maxOutputTokens);
        assertEquals(Cap.YES, mercury.reasoning);
    }

    @Test public void openRouterModelWithoutReasoningAndWithImageInput() throws Exception {
        List<ModelInfo> ms = OpenAiClient.parseModels(ModelFixtures.read("openrouter_sample.json"));
        ModelCaps chat = find(ms, "deepseek/deepseek-chat").caps;
        assertEquals(Cap.NO, chat.reasoning);
        assertEquals(Cap.YES, chat.temperature);
        assertEquals(Cap.YES, chat.reasoningLevel("off"));
        assertEquals(Cap.NO, chat.reasoningLevel("high"));

        ModelCaps particle = find(ms, "particle-ai/particle-1").caps;
        assertEquals(Cap.YES, particle.vision);
        assertEquals(131_072, particle.maxOutputTokens);
    }

    @Test public void freeOpenRouterModelHasZeroPriceAndTools() throws Exception {
        List<ModelInfo> ms = OpenAiClient.parseModels(ModelFixtures.read("openrouter_sample.json"));
        ModelCaps free = find(ms, "recursive/flint-v1.0:free").caps;
        assertNotNull(free.priceInPerM);
        assertEquals(Cap.YES, free.tools);
    }

    @Test public void deepSeekEntriesGiveLevelsFromEffortAndModalitiesWithoutPrices() throws Exception {
        List<ModelInfo> ms = OpenAiClient.parseModels(ModelFixtures.read("deepseek_models.json"));
        ModelCaps flash = find(ms, "deepseek-flash").caps;
        assertEquals(1_048_576, flash.contextTokens);
        assertEquals(393_216, flash.maxOutputTokens);
        assertEquals(Cap.YES, flash.vision);
        assertEquals(Cap.YES, flash.reasoning);
        assertEquals(java.util.Arrays.asList("low", "high", "max"), flash.reasoningLevels);
        assertEquals(Cap.YES, flash.reasoningLevel("max"));
        assertEquals(Cap.NO, flash.reasoningLevel("medium"));
        assertEquals(Cap.UNKNOWN, flash.reasoningLevel("off"));
        assertEquals(Cap.UNKNOWN, flash.tools);
        assertNull(flash.priceInPerM);

        ModelCaps pro = find(ms, "deepseek-v4-pro").caps;
        assertEquals(Cap.NO, pro.vision);
    }

    @Test public void groqUsesContextWindowAndMaxCompletionTokens() throws Exception {
        List<ModelInfo> ms = OpenAiClient.parseModels(ModelFixtures.read("groq_models.json"));
        ModelCaps llama = find(ms, "llama-3.3-70b-versatile").caps;
        assertEquals(131_072, llama.contextTokens);
        assertEquals(32_768, llama.maxOutputTokens);
        assertEquals(Cap.UNKNOWN, llama.tools);
        assertEquals(Cap.UNKNOWN, llama.reasoning);
    }

    @Test public void mistralCapabilitiesBecomeToolsAndVision() throws Exception {
        List<ModelInfo> ms = OpenAiClient.parseModels(ModelFixtures.read("mistral_models.json"));
        ModelCaps large = find(ms, "mistral-large-latest").caps;
        assertEquals(131_072, large.contextTokens);
        assertEquals(Cap.YES, large.tools);
        assertEquals(Cap.NO, large.vision);
        assertEquals(Cap.YES, find(ms, "pixtral-large-latest").caps.vision);
    }

    @Test public void plainOpenAiEntryReportsNothingSoEverythingStaysUnknown() throws Exception {
        List<ModelInfo> ms = OpenAiClient.parseModels(ModelFixtures.read("openai_plain_models.json"));
        ModelCaps m = find(ms, "gpt-4o-mini").caps;
        assertEquals(0, m.contextTokens);
        assertEquals(0, m.maxOutputTokens);
        assertEquals(Cap.UNKNOWN, m.tools);
        assertEquals(ModelCaps.Source.UNKNOWN, m.source);
        assertEquals(Cap.UNKNOWN, m.reasoningLevel("low"));
        assertEquals(Cap.YES, m.reasoningLevel(""));
    }

    @Test public void robustToStringsAndBrokenNumbers() throws Exception {
        JSONObject m = new JSONObject("{\"id\":\"x\",\"context_length\":\"32000\",\"top_provider\":{\"max_completion_tokens\":\"abc\"},"
            + "\"supported_parameters\":[\"tools\",7,null],\"pricing\":{\"prompt\":\"free\",\"completion\":\"0.000002\"}}");
        ModelCaps c = ModelCapsParser.parse(m, "x");
        assertEquals(32_000, c.contextTokens);
        assertEquals(0, c.maxOutputTokens);
        assertEquals(Cap.YES, c.tools);
        assertEquals(Cap.NO, c.temperature);
        assertNull(c.priceInPerM);
        assertEquals(2.0, c.priceOutPerM, 1e-9);
    }
}
