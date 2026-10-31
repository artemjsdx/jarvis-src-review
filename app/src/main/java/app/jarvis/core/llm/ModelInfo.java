package app.jarvis.core.llm;

public final class ModelInfo {
    public final String id;
    public final String name;
    public final String ownedBy;
    public final int contextLength;
    /** Heuristic from the id (embedding/audio/image models are not chat models). */
    public final boolean chatLikely;
    /** What the provider's own entry says about this model; unknown fields stay unknown. */
    public final ModelCaps caps;

    public ModelInfo(String id, String name, String ownedBy, int contextLength) {
        this(id, name, ownedBy, withContext(id, contextLength));
    }

    public ModelInfo(String id, String name, String ownedBy, ModelCaps caps) {
        this.id = id;
        this.name = name == null || name.isEmpty() ? id : name;
        this.ownedBy = ownedBy == null ? "" : ownedBy;
        this.caps = caps == null ? ModelCaps.unknown(id) : caps;
        this.contextLength = this.caps.contextTokens;
        String l = id.toLowerCase(java.util.Locale.ROOT);
        this.chatLikely = !(l.contains("embed") || l.contains("whisper") || l.contains("tts") || l.contains("moderation")
            || l.contains("dall-e") || l.contains("image") || l.contains("rerank") || l.contains("edit-"));
    }

    private static ModelCaps withContext(String id, int ctx) {
        ModelCaps c = ModelCaps.unknown(id);
        c.contextTokens = Math.max(0, ctx);
        if (c.hasData()) c.source = ModelCaps.Source.PROVIDER;
        return c;
    }
}
