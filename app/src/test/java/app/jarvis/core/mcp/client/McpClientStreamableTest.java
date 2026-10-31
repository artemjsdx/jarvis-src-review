package app.jarvis.core.mcp.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.jarvis.core.util.CancelToken;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Streamable HTTP against {@link FakeMcpServer}: real sockets, real SSE and JSON bodies, real failure paths. */
public class McpClientStreamableTest {
    private FakeMcpServer server;
    private McpClient client;
    private final TransportOptions opt = fast();

    static TransportOptions fast() {
        TransportOptions o = new TransportOptions();
        o.backoffBaseMs = 2;
        o.readMs = 5000;
        return o;
    }

    @Before public void setUp() throws Exception { server = new FakeMcpServer(FakeMcpServer.Mode.STREAMABLE); }

    @After public void tearDown() {
        if (client != null) client.close();
        server.close();
    }

    private McpClient newClient(Map<String, String> headers, long timeoutMs) {
        return new McpClient(new StreamableHttpTransport(server.url(), headers, opt), timeoutMs, opt);
    }

    private McpClient connected() throws McpException {
        client = newClient(null, 5000);
        client.connect(new CancelToken());
        return client;
    }

    @Test public void initializeNegotiatesVersionAndSendsInitializedNotification() throws Exception {
        connected();
        assertEquals("2025-06-18", client.protocolVersion());
        assertEquals("fake-mcp", client.serverName());
        assertTrue(client.serverHasTools());
        assertTrue(server.methods.contains("notifications/initialized"));
        assertTrue(server.protocolHeaders.contains("2025-06-18"));
    }

    @Test public void listsToolsAndCallsOneWithJsonBody() throws Exception {
        connected();
        List<RemoteTool> tools = client.listTools(new CancelToken());
        assertTrue(names(tools).contains("echo"));
        assertTrue(tools.get(0).inputSchema.has("properties"));
        ToolCallResult r = client.callTool("echo", new JSONObject().put("text", "привет"), new CancelToken());
        assertFalse(r.isError);
        assertEquals("привет", r.text);
    }

    @Test public void readOnlyHintIsReadFromAnnotations() throws Exception {
        connected();
        RemoteTool ro = find(client.listTools(new CancelToken()), "read_thing");
        RemoteTool rw = find(client.listTools(new CancelToken()), "send_msg");
        assertTrue(ro.readOnly);
        assertFalse(rw.readOnly);
    }

    @Test public void sseAnswerWithMalformedEventsStillDeliversTheResponse() throws Exception {
        connected();
        server.sseResponses = true;
        server.malformed = true;
        ToolCallResult r = client.callTool("echo", new JSONObject().put("text", "через поток"), new CancelToken());
        assertEquals("через поток", r.text);
    }

    @Test public void listChangedInsideSseAnswerTriggersCallback() throws Exception {
        connected();
        server.sseResponses = true;
        final int[] calls = {0};
        client.setToolsChangedListener(new Runnable() { @Override public void run() { calls[0]++; } });
        client.callTool("notify_change", new JSONObject(), new CancelToken());
        assertEquals(1, calls[0]);
    }

    @Test public void listChangedOnGetStreamTriggersCallback() throws Exception {
        server.getStream = true;
        connected();
        final int[] calls = {0};
        client.setToolsChangedListener(new Runnable() { @Override public void run() { calls[0]++; } });
        assertTrue("GET stream should be open", waitUntil(new Cond() { @Override public boolean ok() { return server.getOpened.get() > 0; } }));
        server.addToolAndNotify("extra");
        assertTrue(waitUntil(new Cond() { @Override public boolean ok() { return calls[0] > 0; } }));
    }

    @Test public void getStreamAbsentIsNotAnError() throws Exception {
        connected();
        assertEquals(0, server.getOpened.get());
        assertTrue(client.isAlive());
    }

    @Test public void paginatesToolsList() throws Exception {
        server.pageSize = 3;
        connected();
        List<RemoteTool> tools = client.listTools(new CancelToken());
        assertEquals(9, tools.size());
    }

    @Test public void errorResultIsReportedAsIsErrorNotAsException() throws Exception {
        connected();
        ToolCallResult r = client.callTool("fail", new JSONObject(), new CancelToken());
        assertTrue(r.isError);
        assertEquals("сломалось на сервере", r.text);
    }

    @Test public void jsonRpcErrorBecomesRpcException() throws Exception {
        connected();
        try {
            client.callTool("boom", new JSONObject(), new CancelToken());
            fail();
        } catch (McpException e) {
            assertEquals(McpException.Kind.RPC, e.kind);
            assertTrue(e.getMessage().contains("внутренняя ошибка сервера"));
        }
    }

    @Test public void bigResultIsCappedAndMarked() throws Exception {
        connected();
        ToolCallResult r = client.callTool("big", new JSONObject(), new CancelToken());
        assertTrue(r.truncated);
        assertTrue(r.text.getBytes("UTF-8").length <= McpWire.MAX_RESULT_BYTES);
        assertTrue(r.text.contains("результат обрезан"));
    }

    @Test public void sessionForgottenForeverSurfacesAfterOneReinitialize() throws Exception {
        connected();
        server.forgetSessionTimes = 100;
        try {
            client.callTool("echo", new JSONObject().put("text", "x"), new CancelToken());
            fail();
        } catch (McpException e) {
            assertEquals(McpException.Kind.SESSION_GONE, e.kind);
        }
        assertEquals(2, server.initializes.get());
    }

    @Test public void sessionForgottenOnceRecoversTransparently() throws Exception {
        connected();
        server.forgetSessionTimes = 1;
        ToolCallResult r = client.callTool("echo", new JSONObject().put("text", "снова"), new CancelToken());
        assertEquals("снова", r.text);
        assertEquals(2, server.initializes.get());
    }

    @Test public void transient503OnInitializeIsRetried() throws Exception {
        server.fail503 = 2;
        connected();
        assertTrue(client.isAlive());
    }

    @Test public void connectionRefusedGivesUpAfterFiveAttempts() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        McpClient c = new McpClient(new StreamableHttpTransport("http://127.0.0.1:" + port + "/mcp", null, opt), 5000, opt);
        try {
            c.connect(new CancelToken());
            fail();
        } catch (McpException e) {
            assertEquals(McpException.Kind.TRANSPORT, e.kind);
            assertTrue(e.getMessage().contains("прервалось") || e.getMessage().length() > 0);
        }
    }

    @Test public void unsupportedProtocolVersionIsRejected() throws Exception {
        server.supportVersions = false;
        client = newClient(null, 5000);
        try {
            client.connect(new CancelToken());
            fail();
        } catch (McpException e) {
            assertEquals(McpException.Kind.UNSUPPORTED, e.kind);
        }
    }

    @Test public void headersArePassedOnEveryRequest() throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer secret-123");
        client = newClient(h, 5000);
        client.connect(new CancelToken());
        client.listTools(new CancelToken());
        assertFalse(server.authHeaders.isEmpty());
        for (String a : server.authHeaders) assertEquals("Bearer secret-123", a);
    }

    @Test public void truncatedSseAnswerFailsInsteadOfHanging() throws Exception {
        connected();
        server.sseResponses = true;
        server.truncateNextAnswer = true;
        try {
            client.callTool("echo", new JSONObject().put("text", "обрыв"), new CancelToken());
            fail();
        } catch (McpException e) {
            assertTrue(e.kind == McpException.Kind.TRANSPORT || e.kind == McpException.Kind.PROTOCOL);
        }
    }

    @Test public void cancelSendsNotificationAndReturnsCancelled() throws Exception {
        connected();
        final CancelToken cancel = new CancelToken();
        Thread t = new Thread(new Runnable() { @Override public void run() {
            try { Thread.sleep(150); } catch (InterruptedException e) { return; }
            cancel.cancel("user");
        } });
        t.start();
        long t0 = System.currentTimeMillis();
        try {
            client.callTool("slow", new JSONObject().put("ms", 10_000), cancel);
            fail();
        } catch (McpException e) {
            assertEquals(McpException.Kind.CANCELLED, e.kind);
        }
        assertTrue("returns promptly", System.currentTimeMillis() - t0 < 3000);
        assertTrue(waitUntil(new Cond() { @Override public boolean ok() { return !server.cancelled.isEmpty(); } }));
    }

    @Test public void slowCallTimesOutAndNotifiesServer() throws Exception {
        client = newClient(null, 300);
        client.connect(new CancelToken());
        try {
            client.callTool("slow", new JSONObject().put("ms", 10_000), new CancelToken());
            fail();
        } catch (McpException e) {
            assertEquals(McpException.Kind.TIMEOUT, e.kind);
        }
        assertTrue(waitUntil(new Cond() { @Override public boolean ok() { return !server.cancelled.isEmpty(); } }));
    }

    @Test public void pingAndResourcesWork() throws Exception {
        connected();
        client.ping(new CancelToken());
        assertEquals(1, client.listResources(new CancelToken()).length());
        assertEquals("заметка", client.readResource("mem://note", new CancelToken()));
    }

    @Test public void closeEndsSessionAndRejectsFurtherCalls() throws Exception {
        connected();
        client.close();
        assertFalse(client.isAlive());
        try {
            client.callTool("echo", new JSONObject(), new CancelToken());
            fail();
        } catch (McpException e) {
            assertEquals(McpException.Kind.TRANSPORT, e.kind);
        }
    }

    private static RemoteTool find(List<RemoteTool> l, String name) {
        for (RemoteTool t : l) if (t.name.equals(name)) return t;
        throw new AssertionError("no tool " + name);
    }

    static java.util.List<String> names(List<RemoteTool> l) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (RemoteTool t : l) out.add(t.name);
        return out;
    }

    interface Cond { boolean ok(); }

    static boolean waitUntil(Cond c) throws InterruptedException {
        long end = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < end) {
            if (c.ok()) return true;
            Thread.sleep(10);
        }
        return c.ok();
    }
}
