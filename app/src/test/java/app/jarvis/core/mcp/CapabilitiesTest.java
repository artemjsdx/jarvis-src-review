package app.jarvis.core.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Rule "no hook, no readiness": every feature has MCP tools, every non-planned feature has a scenario, tools match the source. */
public class CapabilitiesTest {
    static File root() {
        File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (d != null && !new File(d, "app/src/main/java").isDirectory()) d = d.getParentFile();
        assertNotNull("repository root not found", d);
        return d;
    }

    static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    @Test public void featuresAreConsistent() throws Exception {
        Set<String> catalog = ToolCatalog.allSet();
        Set<String> ids = new HashSet<>();
        Set<String> used = new HashSet<>();
        for (Capabilities.Feature f : Capabilities.all()) {
            assertTrue("duplicate feature id " + f.id, ids.add(f.id));
            assertFalse("feature " + f.id + " has no MCP tool", f.tools.isEmpty());
            for (String t : f.tools) { assertTrue("feature " + f.id + " names unknown tool " + t, catalog.contains(t)); used.add(t); }
            File sc = new File(root(), f.scenarioPath());
            if (f.planned) {
                assertFalse("planned feature " + f.id + " must say what it waits for", f.note.isEmpty());
                continue;
            }
            assertTrue("feature " + f.id + " has no scenario " + f.scenarioPath(), sc.isFile());
            for (String t : f.tools) assertFalse("covered feature " + f.id + " relies on stub tool " + t, ToolCatalog.STUBS.contains(t));
        }
        Set<String> unhooked = new LinkedHashSet<>(catalog);
        unhooked.removeAll(used);
        assertTrue("tools that no feature lists: " + unhooked, unhooked.isEmpty());
    }

    @Test public void scenariosAreWellFormed() throws Exception {
        File dir = new File(root(), "tests/mcp");
        File[] files = dir.listFiles();
        assertNotNull(files);
        Set<String> catalog = ToolCatalog.allSet();
        int n = 0;
        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            n++;
            String id = f.getName().substring(0, f.getName().length() - 5);
            Capabilities.Feature feat = Capabilities.byId(id);
            assertNotNull("scenario file without a feature: " + f.getName(), feat);
            JSONObject o = new JSONObject(read(f));
            JSONArray scenarios = o.has("scenarios") ? o.getJSONArray("scenarios") : new JSONArray().put(o);
            assertTrue(f.getName() + " is empty", scenarios.length() > 0);
            boolean touchesFeatureTool = false;
            for (int i = 0; i < scenarios.length(); i++) {
                JSONObject sc = scenarios.getJSONObject(i);
                assertTrue(f.getName() + ": name missing", sc.optString("name").length() > 0);
                JSONArray steps = sc.getJSONArray("steps");
                assertTrue(f.getName() + ": no steps", steps.length() > 0);
                for (int k = 0; k < steps.length(); k++) {
                    String call = steps.getJSONObject(k).getString("call");
                    assertTrue(f.getName() + ": unknown tool " + call, catalog.contains(call));
                    if (feat.tools.contains(call)) touchesFeatureTool = true;
                }
                JSONArray needs = sc.optJSONArray("needs");
                if (needs != null) for (int k = 0; k < needs.length(); k++) assertTrue(f.getName() + ": needs unknown tool " + needs.getString(k), catalog.contains(needs.getString(k)));
            }
            assertTrue(f.getName() + " never calls one of its feature's tools " + feat.tools, touchesFeatureTool);
        }
        assertTrue("expected scenarios in tests/mcp", n >= 10);
    }

    /** The catalog must list exactly the tools the Android sources register. */
    @Test public void catalogMatchesSources() throws Exception {
        File[] dirs = {new File(root(), "app/src/main/java/app/jarvis/mcp"), new File(root(), "app/src/main/java/app/jarvis/core/mcp")};
        Pattern p = Pattern.compile("(?:McpTools\\.tool\\(|Tl\\.stub\\(|\\bdelegate\\(r, b,)\\s*\"([a-z0-9_]+)\"");
        Set<String> found = new LinkedHashSet<>();
        for (File dir : dirs) for (File f : dir.listFiles()) {
            if (!f.getName().endsWith(".java")) continue;
            Matcher m = p.matcher(read(f));
            while (m.find()) found.add(m.group(1));
        }
        Set<String> catalog = ToolCatalog.allSet();
        Set<String> missingInSource = new LinkedHashSet<>(catalog);
        missingInSource.removeAll(found);
        Set<String> notInCatalog = new LinkedHashSet<>(found);
        notInCatalog.removeAll(catalog);
        assertEquals("catalog lists tools that no source registers: " + missingInSource, 0, missingInSource.size());
        assertEquals("sources register tools missing from ToolCatalog: " + notInCatalog, 0, notInCatalog.size());
    }

    @Test public void reportMarksMissingTools() {
        Set<String> reg = ToolCatalog.allSet();
        JSONObject r = Capabilities.report(reg);
        assertEquals(0, r.getInt("broken"));
        reg.remove("chat_send");
        assertTrue(Capabilities.report(reg).getInt("broken") > 0);
    }
}
