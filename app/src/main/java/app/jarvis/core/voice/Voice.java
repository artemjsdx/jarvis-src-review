package app.jarvis.core.voice;

public final class Voice {
    public final String id, name;
    /** "ru", "en", "multi" ... informal. */
    public final String language;
    /** "female" | "male" | "neutral" | "". */
    public final String gender;
    public final String description;
    /** True for user-cloned / designed voices. */
    public final boolean custom;

    public Voice(String id, String name, String language, String gender, String description, boolean custom) {
        this.id = id;
        this.name = name;
        this.language = language == null ? "" : language;
        this.gender = gender == null ? "" : gender;
        this.description = description == null ? "" : description;
        this.custom = custom;
    }

    @Override public String toString() { return name + " (" + id + ")"; }
}
