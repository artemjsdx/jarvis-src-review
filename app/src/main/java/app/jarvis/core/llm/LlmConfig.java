package app.jarvis.core.llm;

import java.util.LinkedHashMap;
import java.util.Map;

/** Connection settings + per-provider protocol quirks. */
public final class LlmConfig {
    public String name = "custom";
    /** Preset id ("openrouter", "deepseek", "custom", …); keys learned parameters and model capabilities. */
    public String providerId = "custom";
    public String baseUrl = "";
    public String apiKey = "";
    /** "", "http://host:port" or "socks5://host:port"; blank = system default routing. */
    public String proxy = "";
    public int connectTimeoutMs = 15_000;
    /** Max silence between bytes; reasoning models may think before the first token. */
    public int idleTimeoutMs = 60_000;
    public int maxRetries = 2;
    public final Map<String, String> headers = new LinkedHashMap<>();
    public final Quirks quirks = new Quirks();

    /** How a provider expresses "think less / not at all". */
    public enum ReasoningStyle {
        NONE,
        /** reasoning_effort: "..." (Inception, OpenAI-style). */
        EFFORT,
        /** thinking: {"type":"enabled|disabled"} (DeepSeek, Z.ai GLM, Moonshot Kimi). */
        THINKING_OBJECT,
        /** enable_thinking: true|false (Alibaba Qwen / DashScope). */
        ENABLE_THINKING,
        /** reasoning: {"effort": "..."} or {"enabled": false} (OpenRouter). */
        OPENROUTER
    }

    public static final class Quirks {
        public double tempMin = 0.0, tempMax = 2.0;
        /** send "realtime": true when the request asks for it (Inception, for voice). */
        public boolean sendRealtime = false;
        public ReasoningStyle reasoningStyle = ReasoningStyle.NONE;
        /** reasoning_effort value that means "off / fastest" for EFFORT style (Inception: instant; OpenAI: minimal). */
        public String effortOffValue = "low";
        /** Send the model's reasoning_content back on assistant messages of the current run (DeepSeek/GLM thinking + tools). */
        public boolean echoReasoning = false;
        public boolean emptyContentAsString = false;
        public boolean includeStreamUsage = false;
        public boolean maxCompletionTokens = false;
        public boolean toolMessageName = false;
        public boolean strictTools = false;
        /** Provider forwards Anthropic-style {"cache_control":{"type":"ephemeral"}} markers (OpenRouter does) and only for model ids starting with "anthropic/". */
        public boolean cacheControl = false;
        /** Send reasoning_effort with the chosen level next to the thinking switch (DeepSeek accepts low/high/max). */
        public boolean sendEffort = false;
    }

    public String url(String path) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b + path;
    }

    public LlmConfig copy() {
        LlmConfig c = new LlmConfig();
        c.name = name; c.providerId = providerId; c.baseUrl = baseUrl; c.apiKey = apiKey; c.proxy = proxy;
        c.connectTimeoutMs = connectTimeoutMs; c.idleTimeoutMs = idleTimeoutMs; c.maxRetries = maxRetries;
        c.headers.putAll(headers);
        c.quirks.tempMin = quirks.tempMin; c.quirks.tempMax = quirks.tempMax; c.quirks.sendRealtime = quirks.sendRealtime;
        c.quirks.reasoningStyle = quirks.reasoningStyle; c.quirks.effortOffValue = quirks.effortOffValue; c.quirks.echoReasoning = quirks.echoReasoning;
        c.quirks.emptyContentAsString = quirks.emptyContentAsString;
        c.quirks.includeStreamUsage = quirks.includeStreamUsage; c.quirks.maxCompletionTokens = quirks.maxCompletionTokens;
        c.quirks.toolMessageName = quirks.toolMessageName; c.quirks.strictTools = quirks.strictTools;
        c.quirks.cacheControl = quirks.cacheControl;
        c.quirks.sendEffort = quirks.sendEffort;
        return c;
    }
}
