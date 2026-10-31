package app.jarvis.core.llm;

import java.util.List;

/** Provider presets. Model names here come from the provider's own documentation; the app still loads the live catalog. */
public final class Providers {
    private Providers() { }

    public static final String INCEPTION_BASE = "https://api.inceptionlabs.ai/v1";
    /** From Inception docs (snapshot 2026-10): chat models. mercury-edit-2 is FIM/edit only; Mercury Voice is enterprise-only. */
    public static final String[] INCEPTION_DOCUMENTED_CHAT = {"mercury-3", "mercury-2.5", "mercury-2"};

    public static LlmConfig inception(String apiKey) {
        LlmConfig c = new LlmConfig();
        c.name = "Inception";
        c.baseUrl = INCEPTION_BASE;
        c.apiKey = apiKey == null ? "" : apiKey;
        c.quirks.tempMin = 0.5;          // documented valid range [0.5, 1]
        c.quirks.tempMax = 1.0;
        c.quirks.sendRealtime = true;     // documented low-latency flag for voice workloads
        c.quirks.reasoningStyle = LlmConfig.ReasoningStyle.EFFORT; // instant | low | medium | high
        c.quirks.effortOffValue = "instant";
        c.quirks.strictTools = true;      // docs recommend strict:true
        return c;
    }

    public static LlmConfig openAiCompatible(String name, String baseUrl, String apiKey) {
        LlmConfig c = new LlmConfig();
        c.name = name;
        c.baseUrl = baseUrl;
        c.apiKey = apiKey == null ? "" : apiKey;
        return c;
    }

    /** Preferred default from a live catalog: newest documented Mercury first, else the first chat-like model. */
    public static String pickDefaultModel(List<ModelInfo> catalog) {
        for (String want : INCEPTION_DOCUMENTED_CHAT)
            for (ModelInfo m : catalog) if (m.id.equals(want)) return m.id;
        for (ModelInfo m : catalog) if (m.chatLikely) return m.id;
        return catalog.isEmpty() ? "" : catalog.get(0).id;
    }
}
