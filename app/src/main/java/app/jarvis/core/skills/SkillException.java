package app.jarvis.core.skills;

import java.io.IOException;

/** A skill rule was violated. The message is Russian and safe to show; {@link #op} is the 1-based batch operation, 0 if none. */
public final class SkillException extends IOException {
    public final int op;

    public SkillException(String message) { this(message, 0); }

    public SkillException(String message, int op) {
        super(message);
        this.op = op;
    }
}
