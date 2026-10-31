package app.jarvis.core.mcp.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.jarvis.core.util.CancelToken;
import java.util.List;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Legacy HTTP+SSE (2024-11-05) against {@link FakeMcpServer} in LEGACY mode. */
public class McpClientLegacySseTest {
    private FakeMcpServer server;
    private McpClient client;
    private final TransportOptions opt = McpClientStreamableTest.fast();

    @Before public void setUp() throws Exception { server = new FakeMcpServer(FakeMcpServer.Mode.LEGACY); }

    @After public void tearDown() {
        if (client != null) client.close();
        server.close();
    }

    private McpClient connected() throws McpException {
        client = new McpClient(new LegacySseTransport(server.url(), null, opt), 5000, opt);
        client.connect(new CancelToken());
        return client;
    }

    @Test public void endpointEventThenInitializeAndListTools() throws Exception {
        connected();
        assertEquals("2025-06-18", client.protocolVersion());
        List<RemoteTool> tools = client.listTools(new CancelToken());
        assertEquals(9, tools.size());
    }

    @Test public void callAnswersArriveOnTheStream() throws Exception {
        connected();
        ToolCallResult r = client.callTool("echo", new JSONObject().put("text", "по потоку"), new CancelToken());
        assertEquals("по потоку", r.text);
    }

    @Test public void malformedEventsAreSkipped() throws Exception {
        server.malformed = true;
        connected();
        ToolCallResult r = client.callTool("echo", new JSONObject().put("text", "устойчиво"), new CancelToken());
        assertEquals("устойчиво", r.text);
    }

    @Test public void droppedStreamIsReopenedAndWorksAgain() throws Exception {
        server.dropLegacyStreamOnce = true;
        connected();
        ToolCallResult r = client.callTool("echo", new JSONObject().put("text", "после переподключения"), new CancelToken());
        assertEquals("после переподключения", r.text);
        assertTrue(server.getOpened.get() >= 2);
    }

    @Test public void listChangedOnStreamTriggersCallback() throws Exception {
        connected();
        final int[] calls = {0};
        client.setToolsChangedListener(new Runnable() { @Override public void run() { calls[0]++; } });
        client.callTool("notify_change", new JSONObject(), new CancelToken());
        assertTrue(McpClientStreamableTest.waitUntil(new McpClientStreamableTest.Cond() { @Override public boolean ok() { return calls[0] > 0; } }));
    }

    @Test public void serverWithoutEventStreamFailsTheConnect() throws Exception {
        FakeMcpServer plain = new FakeMcpServer(FakeMcpServer.Mode.STREAMABLE);
        try {
            McpClient c = new McpClient(new LegacySseTransport(plain.url(), null, opt), 5000, opt);
            try {
                c.connect(new CancelToken());
                fail();
            } catch (McpException e) {
                assertTrue(e.kind == McpException.Kind.HTTP || e.kind == McpException.Kind.UNSUPPORTED || e.kind == McpException.Kind.TRANSPORT);
            } finally {
                c.close();
            }
        } finally {
            plain.close();
        }
    }

    @Test public void cancelledCallIsReportedAndServerNotified() throws Exception {
        connected();
        final CancelToken cancel = new CancelToken();
        new Thread(new Runnable() { @Override public void run() {
            try { Thread.sleep(150); } catch (InterruptedException e) { return; }
            cancel.cancel("user");
        } }).start();
        try {
            client.callTool("slow", new JSONObject().put("ms", 10_000), cancel);
            fail();
        } catch (McpException e) {
            assertEquals(McpException.Kind.CANCELLED, e.kind);
        }
        assertTrue(McpClientStreamableTest.waitUntil(new McpClientStreamableTest.Cond() { @Override public boolean ok() { return !server.cancelled.isEmpty(); } }));
    }
}
