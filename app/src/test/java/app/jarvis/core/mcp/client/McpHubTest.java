package app.jarvis.core.mcp.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ToolSpec;
import app.jarvis.core.log.Journal;
import app.jarvis.core.util.CancelToken;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class McpHubTest {
    private File dir;
    private FakeMcpServer server;
    private final Map<String, String> secrets = new HashMap<>();
    private final List<String> journalLines = new ArrayList<>();
    private final List<JSONObject> journalDetails = new ArrayList<>();
    private final Journal journal = new Journal() {
        @Override public void log(String category, Level level, String message, String runId, JSONObject details, long durationMs) {
            journalLines.add(message);
            if (details != null) journalDetails.add(details);
        }
    };
    private final McpHub.HeaderStore headers = new McpHub.HeaderStore() {
        @Override public String get(String id) { return secrets.containsKey(id) ? secrets.get(id) : ""; }
        @Override public void put(String id, String json) { secrets.put(id, json); }
        @Override public void remove(String id) { secrets.remove(id); }
    };

    @Before public void setUp() throws Exception {
        dir = Files.createTempDirectory("jv-mcp-hub").toFile();
        server = new FakeMcpServer(FakeMcpServer.Mode.STREAMABLE);
    }

    @After public void tearDown() {
        server.close();
        for (File f : dir.listFiles()) f.delete();
        dir.delete();
    }

    private File storeFile() { return new File(dir, "mcp-servers.json"); }

    private McpHub hub(ToolRegistry reg, ToolGate gate) {
        TransportOptions opt = McpClientStreamableTest.fast();
        return new McpHub(storeFile(), headers, journal, reg, gate, opt);
    }

    private ServerConfig fake(String id, String url) {
        ServerConfig c = new ServerConfig();
        c.id = id;
        c.name = "Fake " + id;
        c.url = url;
        c.timeoutMs = 5000;
        return c;
    }

    private static ToolSpec specOf(ToolRegistry reg, String name) {
        assertNotNull("registered " + name, reg.get(name));
        return reg.get(name).spec();
    }

    @Test public void directExposureRegistersPrefixedToolsWithSanitizedSchemas() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        McpHub h = hub(reg, ToolGate.DENY);
        h.load();
        h.save(fake("fake", server.url()), null);
        h.testServer("fake", new CancelToken());
        assertTrue(reg.has("fake__echo"));
        assertTrue(reg.has("fake__read_thing"));
        assertFalse(reg.has(McpHub.SEARCH_TOOL));
        ToolSpec echo = specOf(reg, "fake__echo");
        assertTrue(echo.description.endsWith(McpSchemas.DATA_NOTICE));
        assertEquals(false, echo.parameters.getBoolean("additionalProperties"));
        h.close();
    }

    @Test public void lazyExposureWhenMoreThanTwelveToolsAndOnlyMetaToolsAreRegistered() throws Exception {
        for (int i = 0; i < 4; i++) server.addToolAndNotify("extra_" + i);
        ToolRegistry reg = new ToolRegistry();
        McpHub h = hub(reg, ToolGate.DENY);
        h.load();
        h.save(fake("big", server.url()), null);
        assertEquals(13, h.testServer("big", new CancelToken()).size());
        assertTrue(reg.has(McpHub.SEARCH_TOOL));
        assertTrue(reg.has(McpHub.CALL_TOOL));
        assertFalse(reg.has("big__echo"));
        assertTrue(h.promptLine().contains("big (13)"));
        assertTrue(h.promptLine().length() <= 200);
        h.close();
    }

    @Test public void readOnlyToolRunsWithoutTheGate() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        McpHub h = hub(reg, ToolGate.DENY);
        h.load();
        h.save(fake("fake", server.url()), null);
        h.testServer("fake", new CancelToken());
        ToolResult r = h.invoke("fake", "read_thing", new JSONObject(), new CancelToken(), "run1");
        assertTrue(r.ok);
        assertEquals("прочитано", r.data.getString("content"));
        assertEquals("fake", r.data.getString("server"));
        h.close();
    }

    @Test public void writeToolIsRefusedWhenTheUserSaysNo() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        McpHub h = hub(reg, ToolGate.DENY);
        h.load();
        h.save(fake("fake", server.url()), null);
        h.testServer("fake", new CancelToken());
        ToolResult r = h.invoke("fake", "send_msg", new JSONObject().put("text", "секрет"), new CancelToken(), "run1");
        assertFalse(r.ok);
        assertTrue(r.error.contains("отклонено"));
        assertFalse(server.callArgs.contains("send_msg"));
        h.close();
    }

    @Test public void writeToolRunsWhenAllowedAndJournalHoldsKeysNotValues() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        ToolGate yes = new ToolGate() { @Override public boolean confirm(String s, String t, String k, CancelToken c) {
            assertEquals("text", k);
            return true;
        } };
        McpHub h = hub(reg, yes);
        h.load();
        h.save(fake("fake", server.url()), null);
        h.testServer("fake", new CancelToken());
        ToolResult r = h.invoke("fake", "send_msg", new JSONObject().put("text", "СЕКРЕТНЫЙ-ТЕКСТ"), new CancelToken(), "run1");
        assertTrue(r.ok);
        assertTrue(server.callArgs.contains("send_msg"));
        for (String line : journalLines) assertFalse(line.contains("СЕКРЕТНЫЙ"));
        boolean found = false;
        for (JSONObject d : journalDetails) {
            if ("text".equals(d.optString("argKeys"))) found = true;
            assertFalse(d.toString().contains("СЕКРЕТНЫЙ"));
        }
        assertTrue(found);
        h.close();
    }

    @Test public void gateExceptionCountsAsRefusal() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        ToolGate boom = new ToolGate() { @Override public boolean confirm(String s, String t, String k, CancelToken c) {
            throw new IllegalStateException("диалог недоступен");
        } };
        McpHub h = hub(reg, boom);
        h.load();
        h.save(fake("fake", server.url()), null);
        h.testServer("fake", new CancelToken());
        assertFalse(h.invoke("fake", "send_msg", new JSONObject(), new CancelToken(), "r").ok);
        h.close();
    }

    @Test public void confirmWritesOffSkipsTheGate() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        McpHub h = hub(reg, ToolGate.DENY);
        h.load();
        ServerConfig c = fake("fake", server.url());
        c.confirmWrites = false;
        h.save(c, null);
        h.testServer("fake", new CancelToken());
        assertTrue(h.invoke("fake", "send_msg", new JSONObject(), new CancelToken(), "r").ok);
        h.close();
    }

    @Test public void allowAndDenyListsApply() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        McpHub h = hub(reg, ToolGate.DENY);
        h.load();
        ServerConfig c = fake("fake", server.url());
        c.deny.add("echo");
        h.save(c, null);
        h.testServer("fake", new CancelToken());
        assertFalse(reg.has("fake__echo"));
        assertTrue(reg.has("fake__add"));
        ToolResult r = h.invoke("fake", "echo", new JSONObject(), new CancelToken(), "r");
        assertFalse(r.ok);
        assertTrue(r.error.contains("запрещён"));
        ServerConfig onlyAdd = fake("fake", server.url());
        onlyAdd.allow.add("add");
        h.save(onlyAdd, null);
        assertFalse(reg.has("fake__read_thing"));
        assertTrue(reg.has("fake__add"));
        h.close();
    }

    @Test public void headersReachTheServerAndNeverTheServerFile() throws Exception {
        secrets.put("fake", "{\"Authorization\":\"Bearer tok-XYZ-42\"}");
        ToolRegistry reg = new ToolRegistry();
        McpHub h = hub(reg, ToolGate.DENY);
        h.load();
        h.save(fake("fake", server.url()), null);
        h.testServer("fake", new CancelToken());
        assertTrue(server.authHeaders.contains("Bearer tok-XYZ-42"));
        String file = new String(Files.readAllBytes(storeFile().toPath()), "UTF-8");
        assertFalse(file.contains("tok-XYZ-42"));
        assertFalse(file.contains("Bearer"));
        h.close();
    }

    @Test public void nonAsciiHeaderValuesAreDropped() {
        Map<String, String> m = McpHub.parseHeaders("{\"Authorization\":\"Bearer ключ\",\"X-Ok\":\"1\"}");
        assertFalse(m.containsKey("Authorization"));
        assertEquals("1", m.get("X-Ok"));
    }

    @Test public void cachedToolsRegisterWithoutAnyConnectionAfterRestart() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        McpHub h = hub(reg, ToolGate.DENY);
        h.load();
        h.save(fake("fake", server.url()), null);
        h.testServer("fake", new CancelToken());
        h.close();
        int before = server.methods.size();

        ToolRegistry reg2 = new ToolRegistry();
        McpHub h2 = hub(reg2, ToolGate.DENY);
        h2.load();
        assertTrue(reg2.has("fake__echo"));
        assertEquals(before, server.methods.size());
        assertEquals(ExternalMcpInfo.State.IDLE, h2.servers().get(0).state);
        h2.close();
    }

    @Test public void serverGoneTurnsIntoErrorStateAndAUserSafeFailure() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        McpHub h = hub(reg, ToolGate.DENY);
        h.load();
        h.save(fake("fake", server.url()), null);
        h.testServer("fake", new CancelToken());
        server.close();
        ToolResult r = h.invoke("fake", "read_thing", new JSONObject(), new CancelToken(), "r");
        assertFalse(r.ok);
        assertFalse(r.error.isEmpty());
        ExternalMcpInfo.Status st = h.servers().get(0);
        assertEquals(ExternalMcpInfo.State.ERROR, st.state);
        assertFalse(st.error.isEmpty());
        h.close();
    }

    @Test public void disabledServerIsNotRegisteredAndNotCalled() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        McpHub h = hub(reg, ToolGate.DENY);
        h.load();
        h.save(fake("fake", server.url()), null);
        h.testServer("fake", new CancelToken());
        h.setEnabled("fake", false);
        assertFalse(reg.has("fake__echo"));
        assertFalse(h.invoke("fake", "read_thing", new JSONObject(), new CancelToken(), "r").ok);
        assertEquals(ExternalMcpInfo.State.DISABLED, h.servers().get(0).state);
        h.setEnabled("fake", true);
        assertTrue(reg.has("fake__echo"));
        h.close();
    }

    @Test public void searchFindsByWordsAndReportsNoHits() throws Exception {
        for (int i = 0; i < 12; i++) server.addToolAndNotify("extra_" + i);
        ToolRegistry reg = new ToolRegistry();
        McpHub h = hub(reg, ToolGate.DENY);
        h.load();
        h.save(fake("big", server.url()), null);
        h.testServer("big", new CancelToken());
        ToolResult r = h.search("", "складывает", new CancelToken());
        assertTrue(r.ok);
        assertEquals("add", r.data.getJSONArray("hits").getJSONObject(0).getString("tool"));
        assertEquals("big", r.data.getJSONArray("hits").getJSONObject(0).getString("server"));
        ToolResult none = h.search("", "несуществующее-слово", new CancelToken());
        assertEquals(0, none.data.getInt("total"));
        ToolResult all = h.search("big", "", new CancelToken());
        assertEquals(21, all.data.getInt("total"));
        assertEquals(McpHub.SEARCH_HITS, all.data.getJSONArray("hits").length());
        h.close();
    }

    @Test public void autoTransportFallsBackToLegacySse() throws Exception {
        FakeMcpServer legacy = new FakeMcpServer(FakeMcpServer.Mode.LEGACY);
        try {
            ToolRegistry reg = new ToolRegistry();
            McpHub h = hub(reg, ToolGate.DENY);
            h.load();
            h.save(fake("old", legacy.url()), null);
            List<String> names = h.testServer("old", new CancelToken());
            assertTrue(names.contains("echo"));
            assertTrue(h.invoke("old", "read_thing", new JSONObject(), new CancelToken(), "r").ok);
            h.close();
        } finally {
            legacy.close();
        }
    }

    @Test public void configFileRoundTripsWithoutSecrets() throws Exception {
        ServerConfig c = fake("tg", "http://127.0.0.1:8830/mcp");
        c.transport = ServerConfig.Transport.HTTP;
        c.exposure = ServerConfig.Exposure.LAZY;
        c.toolPrefix = "tg";
        c.allow.add("get_me");
        c.confirmWrites = false;
        ServerStore.save(storeFile(), java.util.Collections.singletonList(c));
        List<ServerConfig> back = ServerStore.load(storeFile());
        assertEquals(1, back.size());
        ServerConfig b = back.get(0);
        assertEquals("http://127.0.0.1:8830/mcp", b.url);
        assertEquals(ServerConfig.Transport.HTTP, b.transport);
        assertEquals(ServerConfig.Exposure.LAZY, b.exposure);
        assertEquals("get_me", b.allow.get(0));
        assertFalse(b.confirmWrites);
    }

    @Test public void damagedFileIsSetAsideInsteadOfCrashing() throws Exception {
        Files.write(storeFile().toPath(), "{не json".getBytes("UTF-8"));
        assertTrue(ServerStore.load(storeFile()).isEmpty());
        assertTrue(new File(dir, "mcp-servers.json.bad").exists());
    }

    @Test public void urlValidationAndSlugs() {
        assertEquals("", ServerConfig.validateUrl("http://127.0.0.1:8830/mcp"));
        assertFalse(ServerConfig.validateUrl("ftp://x/y").isEmpty());
        assertFalse(ServerConfig.validateUrl("").isEmpty());
        assertEquals("telegram-account", ServerConfig.slug("Telegram Account!"));
        assertEquals("server", ServerConfig.slug("!!!"));
    }
}
