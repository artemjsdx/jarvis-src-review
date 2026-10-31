package app.jarvis.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

public class ToolGroupsTest {
    @Test public void groupsAreStableAndEveryOneHasToolsAndAText() {
        assertEquals(Arrays.asList("music", "files", "web", "shell", "adb", "memory", "skills", "system", "history"), ToolGroups.ids());
        for (String g : ToolGroups.ids()) {
            assertFalse(g, ToolGroups.toolsOf(g).isEmpty());
            assertFalse(g, ToolGroups.title(g).isEmpty());
            assertTrue(ToolGroups.isGroup(g));
        }
        assertFalse(ToolGroups.isGroup("all"));
        assertFalse(ToolGroups.isGroup(null));
        assertTrue(ToolGroups.toolsOf("nonsense").isEmpty());
    }

    @Test public void aToolIsInOneGroupAtMostAndNeverBothCoreAndGrouped() {
        Set<String> seen = new HashSet<>();
        for (String g : ToolGroups.ids()) {
            for (String t : ToolGroups.toolsOf(g)) {
                assertTrue("tool listed twice: " + t, seen.add(t));
                assertEquals(g, ToolGroups.groupOf(t));
                assertFalse("core and grouped: " + t, ToolGroups.CORE_BUILTIN.contains(t));
            }
        }
        for (String c : ToolGroups.CORE_BUILTIN) assertNull(c, ToolGroups.groupOf(c));
    }

    @Test public void unknownAndExternalToolsAreCoreSoNothingIsHiddenByAccident() {
        assertNull(ToolGroups.groupOf("telegram__send_message"));
        assertNull(ToolGroups.groupOf("mcp_search"));
        assertNull(ToolGroups.groupOf("brand_new_tool"));
        assertNull(ToolGroups.groupOf(null));
        assertFalse(ToolGroups.isDecided("brand_new_tool"));
    }

    @Test public void aWebSearchImpliesTheWebGroupForReadingPages() {
        assertEquals(Arrays.asList("web"), ToolGroups.groupsForUse("web_search"));
        assertEquals(Arrays.asList("music"), ToolGroups.groupsForUse("music_play"));
        assertTrue(ToolGroups.groupsForUse("app_open").isEmpty());
    }

    // ---- the sources are the truth ---------------------------------------------------------------------------------

    private static File sourceRoot() {
        File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (d != null) {
            File candidate = new File(d, "app/src/main/java/app/jarvis");
            if (candidate.isDirectory()) return candidate;
            d = d.getParentFile();
        }
        throw new AssertionError("app/src/main/java/app/jarvis not found above " + System.getProperty("user.dir"));
    }

    private static void collect(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out);
            else if (f.getName().endsWith(".java")) out.add(f);
        }
    }

    /** Every built-in tool name written as a literal in the sources: ToolSpec.of("name", …) or new ToolSpec("name", …). */
    private static Set<String> builtinToolNamesInSources() throws IOException {
        Pattern p = Pattern.compile("(?:ToolSpec\\.of|new\\s+ToolSpec)\\(\\s*\"([a-z][a-z0-9_]*)\"");
        List<File> files = new ArrayList<>();
        collect(sourceRoot(), files);
        Set<String> names = new TreeSet<>();
        for (File f : files) {
            Matcher m = p.matcher(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            while (m.find()) names.add(m.group(1));
        }
        return names;
    }

    @Test public void everyBuiltInToolInTheSourcesIsPlacedOnPurpose() throws IOException {
        Set<String> names = builtinToolNamesInSources();
        assertTrue("the scan found the tools: " + names, names.size() >= 40);
        List<String> undecided = new ArrayList<>();
        for (String n : names) if (!ToolGroups.isDecided(n)) undecided.add(n);
        assertTrue("These tools are in no group and not in CORE_BUILTIN, so they would be announced on every request. "
            + "Put each one in a group in ToolGroups (and its words in ToolRouter) or list it in CORE_BUILTIN on purpose: " + undecided, undecided.isEmpty());
    }

    @Test public void noGroupOrCoreEntryNamesAToolThatDoesNotExist() throws IOException {
        Set<String> names = builtinToolNamesInSources();
        List<String> stale = new ArrayList<>();
        for (String g : ToolGroups.ids()) for (String t : ToolGroups.toolsOf(g)) if (!names.contains(t)) stale.add(g + "/" + t);
        for (String c : ToolGroups.CORE_BUILTIN) if (!names.contains(c)) stale.add("core/" + c);
        assertTrue("typo or removed tool: " + stale, stale.isEmpty());
        assertNotNull(names);
    }
}
