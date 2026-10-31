package app.jarvis.core.llm;

/** A known OpenAI-compatible LLM endpoint with its protocol quirks. Addresses are verified (see {@link #verified}). */
public final class ProviderPreset {
    public final String id, name, baseUrl, note, verified;
    /** Suggestions from provider documentation only; the live GET /models catalog is always the source of truth. */
    public final String[] modelHints;
    public final boolean keyRequired;
    final LlmConfig.Quirks quirks;

    ProviderPreset(String id, String name, String baseUrl, boolean keyRequired, String[] hints, String note, String verified, LlmConfig.Quirks q) {
        this.id = id; this.name = name; this.baseUrl = baseUrl; this.keyRequired = keyRequired;
        this.modelHints = hints; this.note = note; this.verified = verified; this.quirks = q;
    }

    public LlmConfig toConfig(String apiKey) {
        LlmConfig c = new LlmConfig();
        c.name = name;
        c.providerId = id;
        c.baseUrl = baseUrl;
        c.apiKey = apiKey == null ? "" : apiKey;
        c.quirks.tempMin = quirks.tempMin; c.quirks.tempMax = quirks.tempMax; c.quirks.sendRealtime = quirks.sendRealtime;
        c.quirks.reasoningStyle = quirks.reasoningStyle; c.quirks.effortOffValue = quirks.effortOffValue; c.quirks.echoReasoning = quirks.echoReasoning;
        c.quirks.emptyContentAsString = quirks.emptyContentAsString; c.quirks.includeStreamUsage = quirks.includeStreamUsage;
        c.quirks.maxCompletionTokens = quirks.maxCompletionTokens; c.quirks.toolMessageName = quirks.toolMessageName;
        c.quirks.strictTools = quirks.strictTools;
        c.quirks.cacheControl = quirks.cacheControl;
        c.quirks.sendEffort = quirks.sendEffort;
        return c;
    }
}
