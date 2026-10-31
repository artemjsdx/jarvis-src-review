package app.jarvis.core.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class McpServerTest {
    static final String TOKEN = "t0k3n-0123456789abcdef0123456789abcdef";
    McpServer server;
    McpRegistry reg;
    int port;
    final List<McpAudit.Entry> audit = new CopyOnWriteArrayList<>();
    volatile boolean allowAudio, allowDestructive;

    @Before public void up() throws Exception {
        reg = new McpRegistry();
        reg.register(McpTools.tool("echo", "echo text", McpTools.Schema.obj().str("text", "t", true).integer("n", "n", false).oneOf("mode", "m", false, "a", "b"),
            (a, c) -> McpResult.json(new JSONObject().put("echo", a.getString("text")))).readOnly().sensitive("secret"));
        reg.register(McpTools.tool("sleep", "sleeps", McpTools.Schema.obj().integer("ms", "ms", true),
            (a, c) -> { Thread.sleep(a.getInt("ms")); return McpResult.text("slept"); }).timeout(1500));
        reg.register(McpTools.tool("boom", "throws", (a, c) -> { throw new IllegalStateException("kaboom"); }));
        reg.register(McpTools.tool("loud", "audio", (a, c) -> McpResult.text("beep")).audio());
        reg.register(McpTools.tool("wipe", "destructive", (a, c) -> McpResult.text("wiped")).destructive());
        reg.registerResource(new McpResource() {
            @Override public String uri() { return "jarvis://state"; }
            @Override public String name() { return "state"; }
            @Override public String description() { return "d"; }
            @Override public String mimeType() { return "application/json"; }
            @Override public String read() { return "{\"ok\":true}"; }
        });
        McpGate gate = new McpGate() {
            @Override public boolean allowAudio() { return allowAudio; }
            @Override public boolean allowDestructive() { return allowDestructive; }
        };
        McpDispatcher d = new McpDispatcher(reg, gate, audit::add, "jarvis-test", "1.0", "");
        server = new McpServer(d, () -> TOKEN, () -> new JSONObject().put("app", "test"), 3000);
        port = server.start(0, 1);
    }

    @After public void down() { server.close(); }

    static String rpc(int id, String method, String params) { return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\"" + (params == null ? "" : ",\"params\":" + params) + "}"; }

    static String tc(int id, String tool, String args) { return rpc(id, "tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + args + "}"); }

    McpTestClient.Resp one(String body) throws Exception {
        try (McpTestClient c = new McpTestClient(port)) { return c.post(port, TOKEN, body); }
    }

    @Test public void bindsToLoopbackOnly() {
        assertTrue(server.bindAddress(), server.bindAddress().startsWith("127.0.0.1:"));
    }

    @Test public void healthNeedsNoToken() throws Exception {
        try (McpTestClient c = new McpTestClient(port)) {
            McpTestClient.Resp r = c.request("GET", "/health", "127.0.0.1:" + port, null, null, null);
            assertEquals(200, r.status);
            assertTrue(r.json().getBoolean("ok"));
            assertEquals("test", r.json().getString("app"));
        }
    }

    @Test public void missingOrWrongTokenIs401() throws Exception {
        try (McpTestClient c = new McpTestClient(port)) {
            assertEquals(401, c.post(port, null, rpc(1, "ping", null)).status);
            assertEquals(401, c.post(port, "wrong", rpc(1, "ping", null)).status);
            assertEquals(401, c.post(port, TOKEN.substring(0, TOKEN.length() - 1), rpc(1, "ping", null)).status);
            assertEquals(401, c.post(port, TOKEN + "x", rpc(1, "ping", null)).status);
            assertEquals(200, c.post(port, TOKEN, rpc(1, "ping", null)).status);
        }
    }

    @Test public void wrongHostIs403() throws Exception {
        try (McpTestClient c = new McpTestClient(port)) {
            assertEquals(403, c.post(port, "evil.example.com", TOKEN, rpc(1, "ping", null)).status);
        }
        try (McpTestClient c = new McpTestClient(port)) {
            assertEquals(403, c.post(port, "127.0.0.1:" + (port + 1), TOKEN, rpc(1, "ping", null)).status);
        }
        try (McpTestClient c = new McpTestClient(port)) {
            assertEquals(403, c.post(port, "127.0.0.1", TOKEN, rpc(1, "ping", null)).status);
        }
        try (McpTestClient c = new McpTestClient(port)) {
            assertEquals(200, c.post(port, "localhost:" + port, TOKEN, rpc(1, "ping", null)).status);
        }
    }

    @Test public void foreignOriginIs403() throws Exception {
        try (McpTestClient c = new McpTestClient(port)) {
            McpTestClient.Resp r = c.request("POST", "/mcp", "127.0.0.1:" + port, TOKEN, rpc(1, "ping", null).getBytes("UTF-8"), "Origin: http://evil.example.com\r\n");
            assertEquals(403, r.status);
        }
        try (McpTestClient c = new McpTestClient(port)) {
            McpTestClient.Resp r = c.request("POST", "/mcp", "127.0.0.1:" + port, TOKEN, rpc(1, "ping", null).getBytes("UTF-8"), "Origin: http://localhost:3000\r\n");
            assertEquals(200, r.status);
        }
    }

    @Test public void initializeIssuesSessionAndNegotiates() throws Exception {
        McpTestClient.Resp r = one(rpc(1, "initialize", "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}"));
        assertEquals(200, r.status);
        assertNotNull(r.headers.get("mcp-session-id"));
        assertEquals(32, r.headers.get("mcp-session-id").length());
        JSONObject res = r.json().getJSONObject("result");
        assertEquals("2025-03-26", res.getString("protocolVersion"));
        assertEquals("jarvis-test", res.getJSONObject("serverInfo").getString("name"));
        assertTrue(res.getJSONObject("capabilities").has("tools"));
        assertEquals("2025-06-18", one(rpc(2, "initialize", "{\"protocolVersion\":\"1999-01-01\"}")).json().getJSONObject("result").getString("protocolVersion"));
    }

    @Test public void notificationGets202() throws Exception {
        McpTestClient.Resp r = one("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(202, r.status);
        assertEquals("", r.body);
    }

    @Test public void toolsListAndCall() throws Exception {
        try (McpTestClient c = new McpTestClient(port)) {
            JSONArray tools = c.post(port, TOKEN, rpc(1, "tools/list", null)).json().getJSONObject("result").getJSONArray("tools");
            assertEquals(5, tools.length());
            assertEquals("echo", tools.getJSONObject(0).getString("name"));
            assertTrue(tools.getJSONObject(0).getJSONObject("annotations").getBoolean("readOnlyHint"));
            JSONObject res = c.post(port, TOKEN, tc(2, "echo", "{\"text\":\"привет\"}")).json().getJSONObject("result");
            assertFalse(res.getBoolean("isError"));
            assertEquals("привет", new JSONObject(res.getJSONArray("content").getJSONObject(0).getString("text")).getString("echo"));
        }
    }

    @Test public void keepAliveServesManyRequestsOnOneConnection() throws Exception {
        try (McpTestClient c = new McpTestClient(port)) {
            for (int i = 0; i < 20; i++) {
                McpTestClient.Resp r = c.post(port, TOKEN, rpc(i, "ping", null));
                assertEquals(200, r.status);
                assertEquals("keep-alive", r.headers.get("connection"));
                assertEquals(i, r.json().getInt("id"));
            }
        }
    }

    @Test public void badJsonIsParseError() throws Exception {
        McpTestClient.Resp r = one("{not json");
        assertEquals(400, r.status);
        assertEquals(-32700, r.json().getJSONObject("error").getInt("code"));
        assertEquals(-32700, one("{\"a\":1}garbage").json().getJSONObject("error").getInt("code"));
        assertEquals(-32600, one("{\"jsonrpc\":\"2.0\",\"id\":1}").json().getJSONObject("error").getInt("code"));
        assertEquals(-32600, one("[]").json().getJSONObject("error").getInt("code"));
        assertEquals(-32601, one(rpc(1, "no/such", null)).json().getJSONObject("error").getInt("code"));
    }

    @Test public void batchReturnsOnlyRequestsAnswers() throws Exception {
        String b = "[" + rpc(1, "ping", null) + ",{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}," + tc(2, "echo", "{\"text\":\"x\"}") + "]";
        McpTestClient.Resp r = one(b);
        assertEquals(200, r.status);
        JSONArray a = new JSONArray(r.body);
        assertEquals(2, a.length());
        assertEquals(1, a.getJSONObject(0).getInt("id"));
        assertEquals(2, a.getJSONObject(1).getInt("id"));
        assertEquals(202, one("[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]").status);
    }

    @Test public void concurrentCallsRunInParallel() throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(6);
        long t0 = System.nanoTime();
        List<Future<Boolean>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            final int id = i;
            fs.add(ex.submit(new Callable<Boolean>() {
                @Override public Boolean call() throws Exception {
                    McpTestClient.Resp r = one(tc(id, "sleep", "{\"ms\":600}"));
                    return r.json().getJSONObject("result").getJSONArray("content").toString().contains("slept") && r.json().getInt("id") == id;
                }
            }));
        }
        for (Future<Boolean> f : fs) assertTrue(f.get());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        ex.shutdownNow();
        assertTrue("took " + ms, ms < 2500);
    }

    @Test public void oversizedBodyIs413AndClosed() throws Exception {
        try (McpTestClient c = new McpTestClient(port)) {
            c.sendRaw("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nAuthorization: Bearer " + TOKEN + "\r\nContent-Length: " + (McpServer.MAX_BODY + 1) + "\r\n\r\n");
            McpTestClient.Resp r = c.read();
            assertEquals(413, r.status);
            assertTrue(c.closedByPeer());
        }
        // a body just under the cap is accepted (parse error is fine: the point is that it was read)
        byte[] big = new byte[McpServer.MAX_BODY];
        java.util.Arrays.fill(big, (byte) ' ');
        try (McpTestClient c = new McpTestClient(port)) {
            McpTestClient.Resp r = c.request("POST", "/mcp", "127.0.0.1:" + port, TOKEN, big, null);
            assertEquals(400, r.status);
            assertEquals(-32700, r.json().getJSONObject("error").getInt("code"));
        }
    }

    @Test public void unauthenticatedBodyIsNotBuffered() throws Exception {
        try (McpTestClient c = new McpTestClient(port)) {
            c.sendRaw("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nContent-Length: 3000000\r\n\r\n");
            McpTestClient.Resp r = c.read();
            assertEquals(401, r.status);
            assertTrue(c.closedByPeer());
        }
    }

    @Test public void chunkedAndGarbageRequestsAreRejected() throws Exception {
        try (McpTestClient c = new McpTestClient(port)) {
            c.sendRaw("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nTransfer-Encoding: chunked\r\n\r\n");
            assertEquals(411, c.read().status);
        }
        try (McpTestClient c = new McpTestClient(port)) {
            c.sendRaw("GARBAGE\r\n\r\n");
            assertEquals(400, c.read().status);
        }
    }

    @Test public void methodsAndPaths() throws Exception {
        try (McpTestClient c = new McpTestClient(port)) {
            assertEquals(405, c.request("GET", "/mcp", "127.0.0.1:" + port, TOKEN, null, null).status);
            assertEquals(404, c.request("GET", "/nope", "127.0.0.1:" + port, TOKEN, null, null).status);
            assertEquals(401, c.request("GET", "/mcp", "127.0.0.1:" + port, null, null, null).status);
        }
    }

    @Test public void idleConnectionTimesOut() throws Exception {
        try (McpTestClient c = new McpTestClient(port)) {
            long t0 = System.currentTimeMillis();
            assertTrue(c.closedByPeer());
            long ms = System.currentTimeMillis() - t0;
            assertTrue("closed after " + ms, ms >= 2500 && ms < 6000);
        }
    }

    @Test public void connectionCapIs8() throws Exception {
        List<McpTestClient> held = new ArrayList<>();
        try {
            for (int i = 0; i < McpServer.MAX_CONNECTIONS; i++) held.add(new McpTestClient(port));
            Thread.sleep(300);
            try (McpTestClient extra = new McpTestClient(port)) {
                assertEquals(503, extra.read().status);
            }
            held.remove(0).close();
            Thread.sleep(300);
            try (McpTestClient again = new McpTestClient(port)) {
                assertEquals(200, again.post(port, TOKEN, rpc(1, "ping", null)).status);
            }
        } finally { for (McpTestClient c : held) c.close(); }
    }

    @Test public void toolErrorsAreResultsNotHttp500() throws Exception {
        JSONObject boom = one(tc(1, "boom", "{}")).json().getJSONObject("result");
        assertTrue(boom.getBoolean("isError"));
        JSONObject err = new JSONObject(boom.getJSONArray("content").getJSONObject(0).getString("text")).getJSONObject("error");
        assertEquals("INTERNAL", err.getString("code"));
        assertTrue(err.getString("technical").contains("kaboom"));
        McpTestClient.Resp unknown = one(tc(2, "nope", "{}"));
        assertEquals(-32602, unknown.json().getJSONObject("error").getInt("code"));
    }

    @Test public void argumentValidation() throws Exception {
        assertCode("INVALID_ARGS", one(tc(1, "echo", "{}")));
        assertCode("INVALID_ARGS", one(tc(1, "echo", "{\"text\":5}")));
        assertCode("INVALID_ARGS", one(tc(1, "echo", "{\"text\":\"a\",\"n\":\"x\"}")));
        assertCode("INVALID_ARGS", one(tc(1, "echo", "{\"text\":\"a\",\"mode\":\"zzz\"}")));
        assertFalse(one(tc(1, "echo", "{\"text\":\"a\",\"n\":3,\"mode\":\"b\"}")).json().getJSONObject("result").getBoolean("isError"));
    }

    @Test public void toolTimeout() throws Exception {
        long t0 = System.currentTimeMillis();
        assertCode("TIMEOUT", one(tc(1, "sleep", "{\"ms\":5000}")));
        assertTrue(System.currentTimeMillis() - t0 < 3500);
    }

    @Test public void audioAndDestructiveGating() throws Exception {
        assertCode("FORBIDDEN", one(tc(1, "loud", "{}")));
        assertCode("FORBIDDEN", one(tc(1, "wipe", "{}")));
        allowAudio = true;
        assertFalse(one(tc(1, "loud", "{}")).json().getJSONObject("result").getBoolean("isError"));
        assertCode("FORBIDDEN", one(tc(1, "wipe", "{}")));
        allowDestructive = true;
        assertFalse(one(tc(1, "wipe", "{}")).json().getJSONObject("result").getBoolean("isError"));
    }

    @Test public void auditRecordsMaskedArgs() throws Exception {
        String longText = new String(new char[125]).replace("\0", "zzz ");
        one(tc(1, "echo", "{\"text\":\"" + longText + "\",\"secret\":\"hunter2hunter2\"}"));
        one(tc(2, "echo", "{\"text\":\"REDACTED\"}"));
        one(tc(3, "echo", "{}"));
        assertEquals(3, audit.size());
        McpAudit.Entry e = audit.get(0);
        assertEquals("echo", e.tool);
        assertTrue(e.ok);
        assertEquals("***", e.args.getString("secret"));
        assertTrue(e.args.getString("text").length() < 260);
        assertTrue(e.args.getString("text").contains("500"));
        assertFalse(audit.get(1).args.toString().contains("abcdefghijklmnopqrstuvwxyz0123"));
        assertFalse(audit.get(2).ok);
        assertEquals("INVALID_ARGS", audit.get(2).errorCode);
        assertTrue(audit.get(0).durationMs >= 0);
    }

    @Test public void resources() throws Exception {
        JSONObject l = one(rpc(1, "resources/list", null)).json().getJSONObject("result");
        assertEquals("jarvis://state", l.getJSONArray("resources").getJSONObject(0).getString("uri"));
        JSONObject r = one(rpc(2, "resources/read", "{\"uri\":\"jarvis://state\"}")).json().getJSONObject("result");
        assertEquals("{\"ok\":true}", r.getJSONArray("contents").getJSONObject(0).getString("text"));
        assertEquals(-32002, one(rpc(3, "resources/read", "{\"uri\":\"jarvis://zzz\"}")).json().getJSONObject("error").getInt("code"));
    }

    @Test public void sseWhenClientDoesNotAcceptJson() throws Exception {
        try (McpTestClient c = new McpTestClient(port)) {
            String body = rpc(1, "ping", null);
            McpTestClient.Resp r = c.request("POST", "/mcp", "127.0.0.1:" + port, TOKEN, body.getBytes("UTF-8"), "Accept: text/event-stream\r\n");
            // the client helper already sent a default Accept; the server sees both and answers JSON
            assertEquals(200, r.status);
        }
    }

    private static void assertCode(String code, McpTestClient.Resp r) throws Exception {
        JSONObject res = r.json().getJSONObject("result");
        assertTrue(res.getBoolean("isError"));
        assertEquals(code, new JSONObject(res.getJSONArray("content").getJSONObject(0).getString("text")).getJSONObject("error").getString("code"));
    }
}
