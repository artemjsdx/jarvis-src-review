package app.jarvis.core.llm;

/** Tri-state capability: the provider or catalog said yes or no, or nothing is known. Unknown is never shown as no. */
public enum Cap {
    YES, NO, UNKNOWN;

    public static Cap of(Boolean b) { return b == null ? UNKNOWN : b ? YES : NO; }

    /** This value when it is known, otherwise the other one. */
    public Cap or(Cap other) { return this == UNKNOWN ? other : this; }
}
