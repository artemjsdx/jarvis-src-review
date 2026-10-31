package app.jarvis.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.llm.ToolSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ToolSelectionTest {
    private static ToolSelection sel(String... groups) { return new ToolSelection(ToolFixtures.fullRegistry(), Arrays.asList(groups)); }

    private static ToolSpec meta(List<ToolSpec> specs) {
        for (ToolSpec s : specs) if (ToolGroups.META_TOOL.equals(s.name)) return s;
        return null;
    }

    private static JSONObject args(String json) throws Exception { return new JSONObject(json); }

    @Test public void byDefaultOnlyTheCoreAndTheMetaToolAreAnnounced() {
        List<ToolSpec> specs = sel().advertise();
        List<String> expected = new ArrayList<>(ToolGroups.CORE_BUILTIN);
        expected.add(ToolGroups.META_TOOL);
        assertEquals(new java.util.HashSet<>(expected), ToolFixtures.names(specs));
        assertEquals("the meta tool comes last, so the rest of the list keeps its place", ToolGroups.META_TOOL, specs.get(specs.size() - 1).name);
    }

    @Test public void aSwitchedOnGroupAddsItsToolsAndLeavesTheMetaDescription() {
        ToolSelection s = sel("music");
        Set<String> names = ToolFixtures.names(s.advertise());
        assertTrue(names.containsAll(ToolGroups.toolsOf("music")));
        assertFalse(names.contains("fs_read"));
        String d = meta(s.advertise()).description;
        assertFalse("music is already on: " + d, d.contains("music — "));
        assertTrue(d, d.contains("files — "));
        assertTrue(d, d.contains("all — "));
    }

    @Test public void theMetaToolDisappearsWhenNothingIsHiddenAnyMore() {
        ToolSelection s = sel(ToolGroups.ids().toArray(new String[0]));
        List<ToolSpec> specs = s.advertise();
        assertEquals(null, meta(specs));
        assertEquals(ToolFixtures.fullRegistry().specs().size(), specs.size());
        assertTrue(s.hiddenGroups().isEmpty());
    }

    @Test public void externalMcpToolsAreAlwaysAnnounced() {
        assertTrue(ToolFixtures.names(new ToolSelection(ToolFixtures.withMcp(), null).advertise()).contains("telegram__send_message"));
    }

    @Test public void unknownGroupIdsAreIgnoredAndOrderFollowsTheRegistry() {
        ToolSelection s = new ToolSelection(ToolFixtures.fullRegistry(), Arrays.asList("nonsense", "music"));
        assertEquals(Collections.singletonList("music"), s.enabledGroups());
        List<String> announced = new ArrayList<>(ToolFixtures.names(s.advertise()));
        List<String> registryOrder = new ArrayList<>(ToolFixtures.names(ToolFixtures.fullRegistry().specs()));
        registryOrder.retainAll(announced);
        announced.remove(ToolGroups.META_TOOL);
        assertEquals("a stable order keeps provider prefix caches useful", registryOrder, announced);
    }

    @Test public void theMetaSchemaOffersOnlyHiddenGroupsAndAll() throws Exception {
        ToolSpec m = meta(sel("music", "files").advertise());
        JSONArray en = m.parameters.getJSONObject("properties").getJSONObject("groups").getJSONObject("items").getJSONArray("enum");
        List<String> got = new ArrayList<>();
        for (int i = 0; i < en.length(); i++) got.add(en.getString(i));
        assertEquals(Arrays.asList("web", "shell", "adb", "memory", "skills", "system", "history", "all"), got);
        assertEquals("groups", m.parameters.getJSONArray("required").getString(0));
    }

    @Test public void aGroupWithoutToolsInThisInstallationIsNotOffered() {
        ToolRegistry reg = ToolFixtures.fullRegistry();
        for (String n : ToolGroups.toolsOf("adb")) reg.remove(n);
        ToolSelection s = new ToolSelection(reg, null);
        assertFalse(s.hiddenGroups().contains("adb"));
        assertFalse(meta(s.advertise()).description.contains("adb — "));
        ToolResult r = s.handleEnable(new JSONObject().put("groups", new JSONArray(Arrays.asList("adb"))));
        assertFalse(r.ok);
        assertTrue(r.error, r.error.contains("Нет таких групп"));
    }

    // ---- the meta tool ---------------------------------------------------------------------------------------------

    @Test public void enablingAGroupAnnouncesItsToolsFromTheNextRequest() throws Exception {
        ToolSelection s = sel();
        ToolResult r = s.handleEnable(args("{\"groups\":[\"music\"]}"));
        assertTrue(r.error, r.ok);
        assertEquals("music", r.data.getJSONArray("enabled").getString(0));
        assertTrue(r.data.getJSONArray("tools_now_available").toString().contains("music_play"));
        assertTrue(ToolFixtures.names(s.advertise()).contains("music_play"));
        assertTrue(r.summary, r.summary.contains("music"));
    }

    @Test public void groupsMayComeAsAStringAndUnderTheSingularName() throws Exception {
        ToolSelection s = sel();
        assertTrue(s.handleEnable(args("{\"groups\":\"music, files\"}")).ok);
        assertEquals(Arrays.asList("music", "files"), s.enabledGroups());
        ToolSelection s2 = sel();
        assertTrue(s2.handleEnable(args("{\"group\":\"WEB\"}")).ok);
        assertEquals(Collections.singletonList("web"), s2.enabledGroups());
    }

    @Test public void enablingTwiceIsNotAnError() throws Exception {
        ToolSelection s = sel("music");
        ToolResult r = s.handleEnable(args("{\"groups\":[\"music\"]}"));
        assertTrue(r.ok);
        assertEquals(0, r.data.getJSONArray("enabled").length());
        assertEquals("music", r.data.getJSONArray("already_enabled").getString(0));
    }

    @Test public void aWrongGroupGetsACorrectableErrorAndSwitchesNothingOn() throws Exception {
        ToolSelection s = sel();
        ToolResult r = s.handleEnable(args("{\"groups\":[\"музыка\"]}"));
        assertFalse(r.ok);
        assertTrue(r.error, r.error.contains("music"));
        assertTrue(s.enabledGroups().isEmpty());
    }

    @Test public void aValidGroupNextToAWrongOneStillWorksAndTheWrongOneIsReported() throws Exception {
        ToolSelection s = sel();
        ToolResult r = s.handleEnable(args("{\"groups\":[\"music\",\"nonsense\"]}"));
        assertTrue(r.ok);
        assertEquals("nonsense", r.data.getJSONArray("unknown_groups").getString(0));
        assertEquals(Collections.singletonList("music"), s.enabledGroups());
    }

    @Test public void allSwitchesEveryGroupOn() throws Exception {
        ToolSelection s = sel();
        assertTrue(s.handleEnable(args("{\"groups\":[\"all\"]}")).ok);
        assertEquals(ToolGroups.ids(), s.enabledGroups());
        assertTrue(s.hiddenGroups().isEmpty());
        assertFalse("nothing left to ask for", s.handleEnable(args("{\"groups\":[\"all\"]}")).data.has("unknown_groups"));
    }

    @Test public void missingOrBrokenArgumentsAreANormalFailure() throws Exception {
        ToolSelection s = sel();
        assertFalse(s.handleEnable(null).ok);
        assertFalse(s.handleEnable(args("{}")).ok);
        assertFalse(s.handleEnable(args("{\"groups\":[]}")).ok);
        assertFalse(s.handleEnable(args("{\"groups\":42}")).ok);
        assertTrue(s.enabledGroups().isEmpty());
    }

    // ---- calling a hidden tool -------------------------------------------------------------------------------------

    @Test public void callingAToolSwitchesItsGroupOn() {
        ToolSelection s = sel();
        s.noteCalled("music_pause");
        assertEquals(Collections.singletonList("music"), s.enabledGroups());
        s.noteCalled("web_search");
        assertTrue("reading the pages comes next", s.enabledGroups().contains("web"));
    }

    @Test public void callingACoreOrUnknownToolChangesNothing() {
        ToolSelection s = sel();
        s.noteCalled("app_open");
        s.noteCalled("no_such_tool");
        s.noteCalled(null);
        assertTrue(s.enabledGroups().isEmpty());
    }

    @Test public void groupsCanBeSwitchedOnFromSeveralThreads() throws Exception {
        final ToolSelection s = sel();
        List<Thread> ts = new ArrayList<>();
        for (final String tool : new String[] {"music_play", "fs_read", "adb_shell", "memory_delete", "app_wake", "session_search"}) {
            Thread t = new Thread(new Runnable() { @Override public void run() { for (int i = 0; i < 200; i++) { s.noteCalled(tool); s.advertise(); } } });
            ts.add(t);
            t.start();
        }
        for (Thread t : ts) t.join(10_000);
        assertEquals(6, s.enabledGroups().size());
    }
}
