package app.jarvis.core.skills;

import java.util.Collections;
import java.util.List;

/** A parsed SKILL.md: front matter fields plus the markdown body the agent follows after skill_view. */
public final class SkillDoc {
    public final String name, description, version, body;
    public final List<String> tags, requiresTools;

    SkillDoc(String name, String description, String version, List<String> tags, List<String> requiresTools, String body) {
        this.name = name;
        this.description = description;
        this.version = version;
        this.tags = Collections.unmodifiableList(tags);
        this.requiresTools = Collections.unmodifiableList(requiresTools);
        this.body = body;
    }
}
