package app.jarvis.ui.chat;

import static org.junit.Assert.assertEquals;

import app.jarvis.ui.chat.actions.ToolIcons;
import org.junit.Test;

public class ToolIconsTest {
    @Test
    public void knownFamiliesMapToTheirIcons() {
        assertEquals("ic_folder", ToolIcons.iconName("fs_read"));
        assertEquals("ic_terminal", ToolIcons.iconName("shell_exec"));
        assertEquals("ic_search", ToolIcons.iconName("web_search"));
        assertEquals("ic_globe", ToolIcons.iconName("http_fetch"));
        assertEquals("ic_brain", ToolIcons.iconName("memory_save"));
    }

    @Test
    public void unknownOrNullFallsBackToWrench() {
        assertEquals("ic_wrench", ToolIcons.iconName("session_search"));
        assertEquals("ic_wrench", ToolIcons.iconName(null));
    }
}
