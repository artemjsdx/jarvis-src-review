package app.jarvis.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ToolCall;
import app.jarvis.core.log.Journal;
import app.jarvis.core.util.CancelToken;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class TermuxToolTest {
    private static final class Fake implements TermuxTool.Transport {
        boolean ready = true;
        TermuxTool.Reply reply;
        IOException refuse;
        String lastWrapper;
        long lastWait;
        final List<String> killed = new ArrayList<>();

        @Override public boolean ready() { return ready; }
        @Override public TermuxTool.Reply exec(String id, String wrapper, long waitMs, CancelToken cancel) throws IOException {
            if (refuse != null) throw refuse;
            lastWrapper = wrapper;
            lastWait = waitMs;
            return reply;
        }
        @Override public void kill(String id) { killed.add(id); }
    }

    private Fake fake;
    private ToolRegistry reg;

    @Before public void setUp() {
        fake = new Fake();
        reg = new ToolRegistry();
        TermuxTool.register(reg, fake);
    }

    private ToolResult call(String args) {
        return reg.execute(new ToolCall("t", "termux_exec", args), "run", new CancelToken(), Journal.NOP, null);
    }

    @Test public void successReturnsStdoutAndExitCodeZero() {
        fake.reply = new TermuxTool.Reply(0, "Python 3.12.0\n", "", 0, "");
        ToolResult r = call("{\"command\":\"python3 -V\"}");
        assertTrue(r.ok);
        assertEquals("Python 3.12.0\n", r.data.optString("stdout"));
        assertEquals(0, r.data.optInt("exit_code", -1));
        assertTrue(fake.lastWrapper.contains("jv-req-"));
        assertEquals(60_000L + TermuxTool.GRACE_MS, fake.lastWait);
    }

    @Test public void nonZeroExitIsFailWithCode() {
        fake.reply = new TermuxTool.Reply(3, "", "boom\n", 0, "");
        ToolResult r = call("{\"command\":\"exit 3\"}");
        assertFalse(r.ok);
        assertTrue(r.error, r.error.contains("кодом 3"));
        assertEquals("boom\n", r.data.optString("stderr"));
    }

    @Test public void timeoutExitCodeIsReportedAsTimeout() {
        fake.reply = new TermuxTool.Reply(124, "", "", 0, "");
        ToolResult r = call("{\"command\":\"sleep 999\",\"timeout_s\":5}");
        assertFalse(r.ok);
        assertTrue(r.data.optBoolean("timed_out"));
    }

    @Test public void termuxErrorCodeIsSurfaced() {
        fake.reply = new TermuxTool.Reply(-1, "", "", 2, "not allowed");
        ToolResult r = call("{\"command\":\"ls\"}");
        assertFalse(r.ok);
        assertTrue(r.error, r.error.contains("not allowed"));
    }

    @Test public void noAnswerKillsRemoteAndFails() {
        fake.reply = null;
        ToolResult r = call("{\"command\":\"ls\",\"timeout_s\":7}");
        assertFalse(r.ok);
        assertTrue(r.error, r.error.contains("не ответил"));
        assertEquals(1, fake.killed.size());
    }

    @Test public void refusedRequestReturnsTransportMessage() {
        fake.refuse = new IOException("Termux не установлен");
        ToolResult r = call("{\"command\":\"ls\"}");
        assertFalse(r.ok);
        assertEquals("Termux не установлен", r.error);
    }

    @Test public void notReadyExplainsHowToEnable() {
        fake.ready = false;
        ToolResult r = call("{\"command\":\"ls\"}");
        assertFalse(r.ok);
        assertTrue(r.error, r.error.contains("разрешение моста"));
        assertTrue(fake.lastWrapper == null);
    }

    @Test public void emptyCommandIsRejected() {
        assertFalse(call("{\"command\":\"  \"}").ok);
    }

    @Test public void oversizedInputIsRejectedBeforeSending() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 70_000; i++) big.append('x');
        ToolResult r = call("{\"command\":\"cat\",\"stdin\":\"" + big + "\"}");
        assertFalse(r.ok);
        assertEquals(null, fake.lastWrapper);
    }

    @Test public void longOutputIsHeadTailTruncated() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 60_000; i++) out.append('a');
        fake.reply = new TermuxTool.Reply(0, out.toString(), "", 0, "");
        ToolResult r = call("{\"command\":\"yes\"}");
        assertTrue(r.ok);
        String s = r.data.optString("stdout");
        assertTrue(s.length() < 60_000);
        assertTrue(s.contains("пропущено"));
        assertTrue(r.data.optBoolean("output_truncated"));
    }
}
