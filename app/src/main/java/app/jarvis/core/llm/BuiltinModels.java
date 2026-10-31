package app.jarvis.core.llm;

import java.util.Arrays;

/**
 * Capabilities for models whose provider does not report them without a key or at all. Only values seen in a real
 * provider answer are listed (DeepSeek GET /models, observed 2026-10-29); anything else stays unknown.
 */
public final class BuiltinModels {
    private BuiltinModels() { }

    public static ModelCaps lookup(String provider, String id) {
        if (!"deepseek".equals(provider) || id == null) return null;
        if (id.equals("deepseek-flash")) return entry(id, 1_048_576, 393_216, Cap.YES, Cap.NO, true);
        if (id.equals("deepseek-v4-pro")) return entry(id, 1_048_576, 393_216, Cap.YES, Cap.NO, false);
        return null;
    }

    private static ModelCaps entry(String id, int ctx, int out, Cap reasoning, Cap vision, boolean image) {
        ModelCaps c = new ModelCaps(id);
        c.contextTokens = ctx;
        c.maxOutputTokens = out;
        c.reasoning = reasoning;
        c.reasoningLevels.addAll(Arrays.asList("low", "high", "max"));
        c.vision = image ? Cap.YES : vision;
        c.source = ModelCaps.Source.BUILTIN;
        return c;
    }
}
