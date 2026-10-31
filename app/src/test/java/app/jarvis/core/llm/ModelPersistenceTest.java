package app.jarvis.core.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class ModelPersistenceTest {
    private static File tmp(String name) throws Exception {
        File d = Files.createTempDirectory("models-test").toFile();
        return new File(d, name);
    }

    @Test public void paramMemoryPersistsAcrossInstancesAndDeduplicates() throws Exception {
        File f = tmp("params.json");
        ParamMemory a = new ParamMemory(f);
        assertTrue(a.learn("deepseek", "deepseek-flash", "temperature"));
        assertFalse(a.learn("deepseek", "deepseek-flash", "temperature"));
        assertTrue(a.learn("deepseek", "deepseek-flash", "reasoning_effort"));
        ParamMemory b = new ParamMemory(f);
        assertEquals(Arrays.asList("reasoning_effort", "temperature"), new ArrayList<>(b.rejected("deepseek", "deepseek-flash")));
        assertTrue(b.rejected("deepseek", "deepseek-v4-pro").isEmpty());
        assertTrue(b.rejected("openrouter", "deepseek-flash").isEmpty());
    }

    @Test public void paramMemoryKeepsWorkingWhenFileIsDamaged() throws Exception {
        File f = tmp("params.json");
        Files.write(f.toPath(), "[[[".getBytes("UTF-8"));
        ParamMemory m = new ParamMemory(f);
        assertTrue(m.rejected("x", "y").isEmpty());
        assertTrue(m.learn("x", "y", "max_tokens"));
        assertEquals(Collections.singleton("max_tokens"), m.rejected("x", "y"));
    }

    @Test public void recentModelsKeepNewestFirstWithoutDuplicatesAndAtMostEight() {
        List<String> r = Collections.emptyList();
        for (int i = 0; i < 10; i++) r = RecentModels.push(r, "p", "m" + i);
        assertEquals(RecentModels.MAX, r.size());
        assertEquals("p|m9", r.get(0));
        r = RecentModels.push(r, "openrouter", "inception/mercury-3");
        r = RecentModels.push(r, "p", "m5");
        assertEquals("p|m5", r.get(0));
        assertEquals(1, java.util.Collections.frequency(r, "p|m5"));
        assertEquals(RecentModels.MAX, r.size());
    }

    @Test public void recentModelsIgnoreIdsThatBreakStorageAndMalformedEntries() {
        List<String> r = RecentModels.push(Collections.singletonList("broken"), "p", "a,b");
        assertTrue(r.isEmpty());
        r = RecentModels.push(Arrays.asList("p|x", "", "noseparator", "p|"), "q", "y");
        assertEquals(Arrays.asList("q|y", "p|x"), r);
        String[] parts = RecentModels.split("openrouter|inception/mercury-3");
        assertEquals("openrouter", parts[0]);
        assertEquals("inception/mercury-3", parts[1]);
        assertEquals(null, RecentModels.split("nope"));
    }

    @Test public void modelCapsRoundTripThroughJsonAndKeepUnknownsUnknown() throws Exception {
        ModelCaps c = new ModelCaps("deepseek-flash");
        c.contextTokens = 1_048_576;
        c.tools = Cap.YES;
        c.reasoningLevels.addAll(Arrays.asList("low", "high"));
        c.priceOutPerM = 0.18;
        c.source = ModelCaps.Source.PROVIDER;
        c.fetchedAt = 42;
        ModelCaps back = ModelCaps.fromJson(c.toJson());
        assertEquals(1_048_576, back.contextTokens);
        assertEquals(Cap.YES, back.tools);
        assertEquals(Cap.UNKNOWN, back.temperature);
        assertEquals(Arrays.asList("low", "high"), back.reasoningLevels);
        assertEquals(0.18, back.priceOutPerM, 1e-9);
        assertEquals(null, back.priceInPerM);
        assertEquals(ModelCaps.Source.PROVIDER, back.source);
        assertEquals(42, back.fetchedAt);
    }

    @Test public void reasoningLevelRulesFollowTheListedLevelsAndNonReasoningModels() {
        ModelCaps listed = new ModelCaps("a");
        listed.reasoning = Cap.YES;
        listed.reasoningLevels.addAll(Arrays.asList("low", "high", "max"));
        assertEquals(Cap.YES, listed.reasoningLevel("max"));
        assertEquals(Cap.NO, listed.reasoningLevel("medium"));
        assertEquals(Cap.UNKNOWN, listed.reasoningLevel("off"));
        assertEquals(Cap.YES, listed.reasoningLevel(""));

        ModelCaps plain = new ModelCaps("b");
        plain.reasoning = Cap.NO;
        assertEquals(Cap.YES, plain.reasoningLevel("off"));
        assertEquals(Cap.NO, plain.reasoningLevel("low"));

        ModelCaps unknown = new ModelCaps("c");
        assertEquals(Cap.UNKNOWN, unknown.reasoningLevel("high"));
    }

    @Test public void fillTakesOnlyUnknownFieldsAndKeepsOwnSource() {
        ModelCaps own = new ModelCaps("m");
        own.contextTokens = 5;
        own.source = ModelCaps.Source.PROVIDER;
        ModelCaps lower = new ModelCaps("m");
        lower.contextTokens = 9;
        lower.maxOutputTokens = 7;
        lower.tools = Cap.YES;
        lower.source = ModelCaps.Source.OPENROUTER;
        ModelCaps merged = own.fill(lower);
        assertEquals(5, merged.contextTokens);
        assertEquals(7, merged.maxOutputTokens);
        assertEquals(Cap.YES, merged.tools);
        assertEquals(ModelCaps.Source.PROVIDER, merged.source);
        assertEquals(Cap.UNKNOWN, own.tools);
    }
}
