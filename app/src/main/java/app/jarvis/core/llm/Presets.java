package app.jarvis.core.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Provider registry. Base URLs were probed on 2026-10-27 from a Belarus network with GET {base}/models:
 * 401 without a key means "endpoint exists, key needed"; 200 means a public catalog. This proves reachability and
 * the path only — it does NOT prove that a particular key or model works.
 */
public final class Presets {
    private Presets() { }

    private static final String P401 = "2026-10-27: GET /models без ключа → 401 (адрес и путь существуют); ключ не проверялся";
    private static final List<ProviderPreset> ALL;

    private static LlmConfig.Quirks q(LlmConfig.ReasoningStyle st, String off, boolean echo) {
        LlmConfig.Quirks x = new LlmConfig.Quirks();
        x.reasoningStyle = st;
        if (off != null) x.effortOffValue = off;
        x.echoReasoning = echo;
        return x;
    }

    static {
        List<ProviderPreset> l = new ArrayList<>();
        LlmConfig.Quirks inc = q(LlmConfig.ReasoningStyle.EFFORT, "instant", false);
        inc.tempMin = 0.5; inc.tempMax = 1.0; inc.sendRealtime = true; inc.strictTools = true;
        l.add(new ProviderPreset("inception", "Inception (Mercury)", Providers.INCEPTION_BASE, true, Providers.INCEPTION_DOCUMENTED_CHAT,
            "Основной провайдер. Доступ из некоторых стран (в т.ч. Беларусь) закрыт на уровне CloudFront: нужен VPN/прокси либо путь через OpenRouter (модель inception/mercury-3).",
            "2026-10-27: из сети BY → 403 CloudFront geo-block; напрямую не проверялся. Модели и параметры — по документации Inception (снимок 2026-10-21)", inc));
        LlmConfig.Quirks orq = q(LlmConfig.ReasoningStyle.OPENROUTER, null, false);
        orq.cacheControl = true;
        l.add(new ProviderPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", true,
            new String[]{"inception/mercury-3", "inception/mercury-2.5", "inception/mercury-2"},
            "Агрегатор. Mercury доступна как inception/mercury-*, это обход гео-блока Inception без VPN.",
            "2026-10-27: /models → 200 (публичный каталог); стриминг, tool calls и отмена проверены на inception/mercury-3",
            orq));
        LlmConfig.Quirks dsq = q(LlmConfig.ReasoningStyle.THINKING_OBJECT, null, true);
        dsq.sendEffort = true;
        l.add(new ProviderPreset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", true, new String[]{"deepseek-flash", "deepseek-v4-pro"},
            "Режим «thinking» отключается параметром thinking; reasoning_content возвращается внутри хода автоматически, если провайдер этого требует.",
            P401, dsq));
        l.add(new ProviderPreset("zai", "GLM (Z.ai, международный)", "https://api.z.ai/api/paas/v4", true, new String[0],
            "GLM через Z.ai. Список моделей — из каталога после ввода ключа.", P401, q(LlmConfig.ReasoningStyle.THINKING_OBJECT, null, true)));
        l.add(new ProviderPreset("zai-coding", "GLM Coding Plan (Z.ai)", "https://api.z.ai/api/coding/paas/v4", true, new String[0],
            "Эндпоинт тарифа Coding Plan (ключ от этого тарифа).", P401, q(LlmConfig.ReasoningStyle.THINKING_OBJECT, null, true)));
        l.add(new ProviderPreset("bigmodel", "GLM (BigModel, Китай)", "https://open.bigmodel.cn/api/paas/v4", true, new String[0],
            "Китайский контур Zhipu AI.", P401, q(LlmConfig.ReasoningStyle.THINKING_OBJECT, null, true)));
        l.add(new ProviderPreset("moonshot", "Kimi (Moonshot, международный)", "https://api.moonshot.ai/v1", true, new String[0],
            "Kimi от Moonshot AI.", P401, q(LlmConfig.ReasoningStyle.THINKING_OBJECT, null, true)));
        l.add(new ProviderPreset("moonshot-cn", "Kimi (Moonshot, Китай)", "https://api.moonshot.cn/v1", true, new String[0],
            "Китайский контур Moonshot AI.", P401, q(LlmConfig.ReasoningStyle.THINKING_OBJECT, null, true)));
        l.add(new ProviderPreset("qwen", "Qwen (Alibaba DashScope, международный)", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", true, new String[0],
            "OpenAI-совместимый режим DashScope.", P401, q(LlmConfig.ReasoningStyle.ENABLE_THINKING, null, false)));
        l.add(new ProviderPreset("qwen-cn", "Qwen (Alibaba DashScope, Китай)", "https://dashscope.aliyuncs.com/compatible-mode/v1", true, new String[0],
            "Китайский контур DashScope.", P401, q(LlmConfig.ReasoningStyle.ENABLE_THINKING, null, false)));
        l.add(new ProviderPreset("groq", "Groq", "https://api.groq.com/openai/v1", true, new String[0], "Быстрый инференс открытых моделей.", P401, q(LlmConfig.ReasoningStyle.NONE, null, false)));
        l.add(new ProviderPreset("cerebras", "Cerebras", "https://api.cerebras.ai/v1", true, new String[0], "Быстрый инференс открытых моделей.", "2026-10-27: GET /models без ключа → 403, с неверным ключом → 401 (адрес существует); ключ не проверялся", q(LlmConfig.ReasoningStyle.NONE, null, false)));
        l.add(new ProviderPreset("mistral", "Mistral", "https://api.mistral.ai/v1", true, new String[0], "", P401, q(LlmConfig.ReasoningStyle.NONE, null, false)));
        l.add(new ProviderPreset("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1", true, new String[0],
            "Каталог открытый, для чата нужен ключ NVIDIA.", "2026-10-27: /models → 200 (публичный каталог); чат с ключом не проверялся", q(LlmConfig.ReasoningStyle.NONE, null, false)));
        LlmConfig.Quirks oa = q(LlmConfig.ReasoningStyle.EFFORT, "minimal", false);
        oa.maxCompletionTokens = true;
        l.add(new ProviderPreset("openai", "OpenAI", "https://api.openai.com/v1", true, new String[0], "", P401, oa));
        l.add(new ProviderPreset("xai", "xAI (Grok)", "https://api.x.ai/v1", true, new String[0], "", P401, q(LlmConfig.ReasoningStyle.NONE, null, false)));
        l.add(new ProviderPreset("gemini", "Google Gemini (OpenAI-совместимый)", "https://generativelanguage.googleapis.com/v1beta/openai", true, new String[0],
            "OpenAI-совместимый слой Gemini API.", "2026-10-27: с неверным ключом → 400 «Please pass a valid API key» (адрес существует); ключ не проверялся", q(LlmConfig.ReasoningStyle.NONE, null, false)));
        l.add(new ProviderPreset("together", "Together AI", "https://api.together.xyz/v1", true, new String[0], "", P401, q(LlmConfig.ReasoningStyle.NONE, null, false)));
        l.add(new ProviderPreset("fireworks", "Fireworks AI", "https://api.fireworks.ai/inference/v1", true, new String[0], "", P401, q(LlmConfig.ReasoningStyle.NONE, null, false)));
        l.add(new ProviderPreset("sambanova", "SambaNova", "https://api.sambanova.ai/v1", true, new String[0], "", "2026-10-27: /models → 200 (публичный каталог); чат с ключом не проверялся", q(LlmConfig.ReasoningStyle.NONE, null, false)));
        l.add(new ProviderPreset("huggingface", "Hugging Face Router", "https://router.huggingface.co/v1", true, new String[0], "Нужен токен Hugging Face.", "2026-10-27: /models → 200 (публичный каталог); чат с токеном не проверялся", q(LlmConfig.ReasoningStyle.NONE, null, false)));
        l.add(new ProviderPreset("ikhdev", "Ikhdev API (GLM / DeepSeek / Qwen / Kimi)", "https://api.ikhdev.xyz/v1", true, new String[0],
            "OpenAI-совместимый шлюз с моделями GLM, DeepSeek, Qwen, Kimi (адрес из личной конфигурации OpenCode).",
            "2026-10-27: GET /models отвечал 503 «migration_in_progress» (временно); ранее модели glm-5.3 и deepseek-v4.1-flash работали со стримингом и инструментами", q(LlmConfig.ReasoningStyle.NONE, null, false)));
        l.add(new ProviderPreset("reformboss", "ReformBoss (GLM / DeepSeek / Kimi / Qwen)", "https://api.reformboss.com/v1", true, new String[0],
            "OpenAI-совместимый шлюз (адрес из личной конфигурации OpenCode).", "2026-10-27: GET /models с ключом → 200, 14 моделей; чат не проверялся", q(LlmConfig.ReasoningStyle.NONE, null, false)));
        l.add(new ProviderPreset("selora", "Selora", "https://api.selora.lol/v1", true, new String[0],
            "OpenAI-совместимый шлюз (адрес из личной конфигурации OpenCode).", "2026-10-27: GET /models с ключом → 200, 21 модель; чат не проверялся", q(LlmConfig.ReasoningStyle.NONE, null, false)));
        l.add(new ProviderPreset("ollama", "Ollama (локально)", "http://127.0.0.1:11434/v1", false, new String[0], "Запущенный на устройстве или в сети сервер Ollama. Ключ не нужен.", "не проверялся: на хосте разработки сервер не запущен", q(LlmConfig.ReasoningStyle.NONE, null, false)));
        l.add(new ProviderPreset("lmstudio", "LM Studio (локально)", "http://127.0.0.1:1234/v1", false, new String[0], "Локальный сервер LM Studio. Ключ не нужен.", "не проверялся: на хосте разработки сервер не запущен", q(LlmConfig.ReasoningStyle.NONE, null, false)));
        l.add(new ProviderPreset("custom", "Свой OpenAI-совместимый адрес", "", false, new String[0], "Любой шлюз с POST /chat/completions и GET /models.", "—", q(LlmConfig.ReasoningStyle.NONE, null, false)));
        ALL = Collections.unmodifiableList(l);
    }

    public static List<ProviderPreset> all() { return ALL; }

    public static ProviderPreset get(String id) {
        for (ProviderPreset p : ALL) if (p.id.equals(id)) return p;
        return null;
    }

    public static ProviderPreset inception() { return get("inception"); }
}
