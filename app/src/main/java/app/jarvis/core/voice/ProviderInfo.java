package app.jarvis.core.voice;

/** Static facts shown in settings and the voice studio. Everything here must be true of the real service. */
public final class ProviderInfo {
    public final String id, name;
    /** Short description of the voices ("Svetlana, Dmitry; multilingual voices"). */
    public final String voices;
    public final boolean needsAccount, needsKey;
    /** Honest quota text, e.g. "10 000 символов в месяц на free", or "не публикуется". */
    public final String freeQuota;
    /** Known limits and caveats (unofficial interface, license, latency ...). */
    public final String limitations;
    public final boolean cloning;
    /** How cloning works / why not, e.g. "по образцу 5–30 с, бесплатно, ~13 с на фразу". */
    public final String cloningNote;
    /** False for reverse-engineered/unofficial interfaces that may break without notice. */
    public final boolean official;
    /** Where audio/text is sent, shown to the user before use ("api.elevenlabs.io", "speech.platform.bing.com"). */
    public final String sendsDataTo;
    public final String homepage;

    public ProviderInfo(String id, String name, String voices, boolean needsAccount, boolean needsKey, String freeQuota, String limitations,
                        boolean cloning, String cloningNote, boolean official, String sendsDataTo, String homepage) {
        this.id = id; this.name = name; this.voices = voices; this.needsAccount = needsAccount; this.needsKey = needsKey;
        this.freeQuota = freeQuota; this.limitations = limitations; this.cloning = cloning; this.cloningNote = cloningNote;
        this.official = official; this.sendsDataTo = sendsDataTo; this.homepage = homepage;
    }
}
