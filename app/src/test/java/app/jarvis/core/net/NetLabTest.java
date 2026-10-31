package app.jarvis.core.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.jarvis.core.MockServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.net.SocketException;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class NetLabTest {
    /** Time that advances only when something sleeps, so pacing can be checked exactly. */
    static final class FakeTime implements NetThrottle.Time {
        final AtomicLong ns = new AtomicLong(5_000_000_000L);
        final List<Long> sleeps = new CopyOnWriteArrayList<>();
        @Override public long nanos() { return ns.get(); }
        @Override public void sleep(long ms) { sleeps.add(ms); ns.addAndGet(ms * 1_000_000L); }
    }

    private FakeTime fake;
    private List<Long> sleeps;

    @Before public void setUp() {
        NetThrottle.clear();
        fake = new FakeTime();
        sleeps = fake.sleeps;
        NetThrottle.setForTest(fake);
    }

    @After public void tearDown() {
        NetThrottle.clear();
        NetThrottle.setForTest(NetThrottle.SYSTEM);
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 7);
        return b;
    }

    private static long sum(List<Long> l) {
        long s = 0;
        for (long v : l) s += v;
        return s;
    }

    // ---------------------------------------------------------------- NetThrottle

    @Test public void offMeansNoWrapping() throws IOException {
        InputStream raw = new ByteArrayInputStream(bytes(10));
        OutputStream rawOut = new ByteArrayOutputStream();
        assertSame(raw, NetThrottle.in(raw));
        assertSame(rawOut, NetThrottle.out(rawOut));
        assertNull(NetThrottle.active());
        assertFalse(NetThrottle.state().getBoolean("active"));
    }

    @Test public void rejectsOutOfRangeValues() {
        expectBad(-1, 0, 0, 0, 1000);
        expectBad(0, -5, 0, 0, 1000);
        expectBad(0, 0, 0, 101, 1000);
        expectBad(0, 0, 0, 0, 0);
        expectBad(0, 0, 0, 0, NetThrottle.MAX_DURATION_MS + 1);
    }

    private static void expectBad(long lat, long up, long down, int loss, long dur) {
        try {
            NetThrottle.set(lat, up, down, loss, dur);
            fail("accepted " + lat + "," + up + "," + down + "," + loss + "," + dur);
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test public void expiresOnItsOwn() {
        NetThrottle.set(100, 0, 0, 0, 5_000);
        assertNotNull(NetThrottle.active());
        fake.ns.addAndGet(4_999_000_000L);
        assertNotNull(NetThrottle.active());
        fake.ns.addAndGet(1_000_000L);
        assertNull(NetThrottle.active());
        assertFalse(NetThrottle.state().getBoolean("active"));
    }

    @Test public void stateReportsRemainingTime() {
        NetThrottle.set(250, 100, 200, 3, 60_000);
        JSONObject s = NetThrottle.state();
        assertTrue(s.getBoolean("active"));
        assertEquals(250, s.getLong("latencyMs"));
        assertEquals(100, s.getLong("kbpsUp"));
        assertEquals(200, s.getLong("kbpsDown"));
        assertEquals(3, s.getInt("lossPct"));
        assertEquals(60_000, s.getLong("remainingMs"));
    }

    @Test public void downloadIsPacedToTheRate() throws IOException {
        NetThrottle.set(0, 0, 80, 0, 60_000);              // 80 kbit/s = 10 000 bytes per second
        InputStream in = NetThrottle.in(new ByteArrayInputStream(bytes(10_000)));
        byte[] buf = new byte[1000];
        int total = 0, n;
        while ((n = in.read(buf)) > 0) total += n;
        assertEquals(10_000, total);
        long slept = sum(sleeps);
        assertTrue("expected about 1000 ms of pacing, got " + slept, slept >= 900 && slept <= 1000);
    }

    @Test public void latencyIsAddedOnceBeforeFirstRead() throws IOException {
        NetThrottle.set(300, 0, 0, 0, 60_000);
        InputStream in = NetThrottle.in(new ByteArrayInputStream(bytes(50)));
        byte[] buf = new byte[10];
        in.read(buf);
        in.read(buf);
        assertEquals(1, sleeps.size());
        assertEquals(300L, (long) sleeps.get(0));
    }

    @Test public void lossResetsStreamAfterBudget() throws IOException {
        NetThrottle.set(0, 0, 0, 50, 60_000);              // budget 100000 / 50 = 2000 bytes
        InputStream in = NetThrottle.in(new ByteArrayInputStream(bytes(10_000)));
        byte[] buf = new byte[512];
        int total = 0;
        try {
            int n;
            while ((n = in.read(buf)) > 0) total += n;
            fail("no reset");
        } catch (SocketException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("reset"));
        }
        assertEquals(2000, total);
    }

    @Test public void uploadIsPacedAndLossAppliesToWrites() throws IOException {
        NetThrottle.set(0, 160, 0, 0, 60_000);             // 160 kbit/s = 20 000 bytes per second
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        OutputStream out = NetThrottle.out(sink);
        out.write(bytes(20_000));
        assertEquals(20_000, sink.size());
        assertTrue("slept " + sum(sleeps), sum(sleeps) >= 900 && sum(sleeps) <= 1000);

        NetThrottle.set(0, 0, 0, 100, 60_000);             // budget 1000 bytes
        OutputStream lossy = NetThrottle.out(new ByteArrayOutputStream());
        try {
            lossy.write(bytes(5000));
            fail("write did not reset");
        } catch (SocketException expected) {
            assertTrue(expected.getMessage().contains("reset"));
        }
    }

    // ---------------------------------------------------------------- NetLab against a local server

    @Test public void probeMeasuresPhasesOfAPlainHttpRequest() throws Exception {
        try (MockServer s = new MockServer(x -> MockServer.json(x, 200, "{\"ok\":true}"))) {
            JSONObject r = NetLab.probeHost(s.url("/health"), 5000);
            assertTrue(r.toString(), r.getBoolean("ok"));
            assertEquals("127.0.0.1", r.getString("ip"));
            assertTrue(r.getString("status").startsWith("HTTP/1.1 200"));
            assertTrue(r.has("dnsMs") && r.has("tcpMs") && r.has("firstByteMs") && r.has("totalMs"));
            assertFalse(r.has("tlsMs"));
            assertFalse(r.getBoolean("throttled"));
        }
    }

    @Test public void probeShowsLatencyUnderThrottle() throws Exception {
        NetThrottle.setForTest(NetThrottle.SYSTEM);
        NetThrottle.set(400, 0, 0, 0, 60_000);
        try (MockServer s = new MockServer(x -> MockServer.json(x, 200, "{}"))) {
            JSONObject r = NetLab.probeHost(s.url("/"), 5000);
            assertTrue(r.toString(), r.getBoolean("ok"));
            assertTrue("firstByteMs " + r.getDouble("firstByteMs"), r.getDouble("firstByteMs") >= 380);
            assertTrue(r.getBoolean("throttled"));
        }
    }

    @Test public void probeReportsFailureStage() throws Exception {
        JSONArray hosts = new JSONArray(Arrays.asList("http://127.0.0.1:1/", "ftp://x/", "http://[bad"));
        JSONObject r = NetLab.probe(hosts, 2000);
        assertEquals(3, r.getInt("count"));
        assertEquals(0, r.getInt("okCount"));
        JSONArray arr = r.getJSONArray("hosts");
        assertEquals("tcp", arr.getJSONObject(0).getString("stage"));
        assertFalse(arr.getJSONObject(0).getBoolean("ok"));
        assertFalse(arr.getJSONObject(0).getString("error").isEmpty());
        assertEquals("parse", arr.getJSONObject(1).getString("stage"));
    }

    @Test public void downloadThroughputIsLimited() throws Exception {
        NetThrottle.setForTest(NetThrottle.SYSTEM);
        NetThrottle.set(0, 0, 400, 0, 60_000);             // 400 kbit/s = 50 KB/s; 40 KB body -> about 0.8 s
        try (MockServer s = new MockServer(x -> {
            x.sendResponseHeaders(200, 40_000);
            x.getResponseBody().write(bytes(40_000));
            x.close();
        })) {
            long t0 = System.nanoTime();
            java.net.HttpURLConnection c = Http.open(s.url("/big"), "GET", null, 5000, 10000);
            String body = Http.readText(c.getInputStream(), 100_000);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            assertEquals(40_000, body.length());
            assertTrue("took " + ms + " ms", ms >= 700);
        }
    }

    @Test public void uploadMeasuresThroughput() throws Exception {
        final List<Integer> received = new CopyOnWriteArrayList<>();
        try (MockServer s = new MockServer((x, body) -> {
            received.add(Integer.parseInt(x.getRequestHeaders().getFirst("Content-Length")));
            MockServer.json(x, 200, "{}");
        })) {
            JSONObject ok = NetLab.upload(s.url("/upload"), 64 * 1024, 10000);
            assertTrue(ok.toString(), ok.getBoolean("ok"));
            assertEquals(200, ok.getInt("status"));
            assertTrue(ok.getDouble("kbytesPerSec") > 0);
            assertEquals(Integer.valueOf(64 * 1024), received.get(0));
        }
    }

    @Test public void uploadUnderThrottleTakesAboutTheRate() throws Exception {
        NetThrottle.setForTest(NetThrottle.SYSTEM);
        NetThrottle.set(0, 800, 0, 0, 60_000);             // 100 KB/s; 64 KB -> about 0.64 s
        try (MockServer s = new MockServer((x, body) -> MockServer.json(x, 200, "{}"))) {
            JSONObject r = NetLab.upload(s.url("/u"), 64 * 1024, 10000);
            assertTrue(r.toString(), r.getBoolean("ok"));
            assertTrue("sendMs " + r.getDouble("sendMs"), r.getDouble("sendMs") >= 500);
            assertTrue(r.getBoolean("throttled"));
        }
    }

    @Test public void uploadReportsConnectFailure() {
        JSONObject r = NetLab.upload("http://127.0.0.1:1/x", 1024, 2000);
        assertFalse(r.getBoolean("ok"));
        assertEquals("connect", r.getString("stage"));
    }

    // ---------------------------------------------------------------- parsing

    @Test public void parsesHostSpecs() {
        NetLab.Target t = NetLab.Target.parse("api.groq.com");
        assertEquals("https", t.scheme);
        assertEquals(443, t.port);
        assertEquals("/", t.path);
        t = NetLab.Target.parse("http://127.0.0.1:8767/health");
        assertEquals("http", t.scheme);
        assertEquals(8767, t.port);
        assertEquals("/health", t.path);
        t = NetLab.Target.parse("speech.platform.bing.com:443/x");
        assertEquals(443, t.port);
        assertEquals("/x", t.path);
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsBadScheme() { NetLab.Target.parse("ftp://x"); }

    @Test(expected = IllegalArgumentException.class) public void rejectsBadPort() { NetLab.Target.parse("h:99999"); }

    private static byte[] readBody(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

}
