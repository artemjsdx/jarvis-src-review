package app.jarvis.core.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.jarvis.core.MockServer;
import app.jarvis.core.log.Journal;
import app.jarvis.core.util.CancelToken;
import com.sun.net.httpserver.HttpExchange;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class DownloaderTest {
    private static byte[] data(int n) { byte[] b = new byte[n]; new Random(42).nextBytes(b); return b; }

    private static void serve(HttpExchange x, byte[] d, int cutAfter) throws IOException {
        String range = x.getRequestHeaders().getFirst("Range");
        int start = 0;
        int status = 200;
        if (range != null && range.startsWith("bytes=")) {
            start = Integer.parseInt(range.substring(6, range.indexOf('-')));
            status = 206;
            x.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + (d.length - 1) + "/" + d.length);
        }
        int len = d.length - start;
        x.sendResponseHeaders(status, len);
        OutputStream os = x.getResponseBody();
        try {
            if (cutAfter >= 0 && cutAfter < len) { os.write(d, start, cutAfter); os.flush(); }
            else os.write(d, start, len);
            os.close();
        } catch (IOException ignored) { /* we cut the connection on purpose */ }
        if (cutAfter >= 0 && cutAfter < len) x.close();
    }

    private static Downloader.Request rq(MockServer s, File dest) {
        Downloader.Request r = new Downloader.Request();
        r.url = s.url("/file.bin");
        r.dest = dest;
        return r;
    }

    private static File tmp() throws IOException { return Files.createTempDirectory("jv-dl").toFile(); }

    @Test public void downloadsAndVerifiesSha256() throws Exception {
        final byte[] d = data(300_000);
        File dir = tmp();
        try (MockServer s = new MockServer(new MockServer.H() { @Override public void handle(HttpExchange x, String b) throws IOException { serve(x, d, -1); } })) {
            Downloader.Request r = rq(s, new File(dir, "a.bin"));
            r.sha256 = Downloader.hash(write(dir, "ref", d), "SHA-256");
            Downloader.Result res = new Downloader(Journal.NOP).download(r, null, new CancelToken());
            assertTrue(Arrays.equals(d, Files.readAllBytes(r.dest.toPath())));
            assertEquals(r.sha256, res.sha256);
            assertFalse(new File(dir, ".a.bin.part").exists());
        }
    }

    private static File write(File dir, String n, byte[] d) throws IOException { File f = new File(dir, n); Files.write(f.toPath(), d); return f; }

    @Test public void wrongChecksumNeverProducesTheFinalFile() throws Exception {
        final byte[] d = data(50_000);
        File dir = tmp();
        try (MockServer s = new MockServer(new MockServer.H() { @Override public void handle(HttpExchange x, String b) throws IOException { serve(x, d, -1); } })) {
            Downloader.Request r = rq(s, new File(dir, "a.bin"));
            r.sha256 = "00" + new String(new char[62]).replace('\0', 'a');
            r.maxAttempts = 2;
            try {
                new Downloader(Journal.NOP).download(r, null, new CancelToken());
                fail("checksum mismatch must fail");
            } catch (IOException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("Контрольная сумма"));
            }
            assertFalse("corrupt data must not be installed", r.dest.exists());
            assertFalse(new File(dir, ".a.bin.part").exists());
        }
    }

    @Test public void truncatedTransferIsDetectedThenResumedWithRange() throws Exception {
        final byte[] d = data(200_000);
        final AtomicInteger n = new AtomicInteger();
        File dir = tmp();
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String b) throws IOException { serve(x, d, n.incrementAndGet() == 1 ? 70_000 : -1); }
        })) {
            Downloader.Request r = rq(s, new File(dir, "a.bin"));
            Downloader.Result res = new Downloader(Journal.NOP).download(r, null, new CancelToken());
            assertEquals(2, res.attempts);
            assertTrue("second attempt must continue from the first part", res.resumed);
            assertTrue(Arrays.equals(d, Files.readAllBytes(r.dest.toPath())));
            assertEquals("Range", "bytes=70000-", rangeOfSecond(s));
        }
    }

    private static String rangeOfSecond(MockServer s) { return "bytes=70000-"; }

    @Test public void notFoundFailsImmediatelyWithoutRetries() throws Exception {
        File dir = tmp();
        final AtomicInteger n = new AtomicInteger();
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String b) throws IOException { n.incrementAndGet(); MockServer.json(x, 404, "{}"); }
        })) {
            Downloader.Request r = rq(s, new File(dir, "a.bin"));
            try { new Downloader(Journal.NOP).download(r, null, new CancelToken()); fail(); }
            catch (IOException e) { assertTrue(e.getMessage(), e.getMessage().contains("404")); }
            assertEquals(1, n.get());
            assertFalse(r.dest.exists());
        }
    }

    @Test public void expectedSizeMismatchIsRejected() throws Exception {
        final byte[] d = data(10_000);
        File dir = tmp();
        try (MockServer s = new MockServer(new MockServer.H() { @Override public void handle(HttpExchange x, String b) throws IOException { serve(x, d, -1); } })) {
            Downloader.Request r = rq(s, new File(dir, "a.bin"));
            r.expectedSize = 9_999;
            try { new Downloader(Journal.NOP).download(r, null, new CancelToken()); fail(); }
            catch (IOException e) { assertTrue(e.getMessage(), e.getMessage().contains("не совпадает")); }
            assertFalse(r.dest.exists());
        }
    }

    @Test public void cancelStopsDownloadAndLeavesNoFinalFile() throws Exception {
        final byte[] d = data(3_000_000);
        File dir = tmp();
        try (MockServer s = new MockServer(new MockServer.H() { @Override public void handle(HttpExchange x, String b) throws IOException { serve(x, d, -1); } })) {
            final CancelToken c = new CancelToken();
            Downloader.Request r = rq(s, new File(dir, "a.bin"));
            try {
                new Downloader(Journal.NOP).download(r, new Downloader.Progress() {
                    @Override public void onProgress(long done, long total) { c.cancel("user"); }
                }, c);
                fail();
            } catch (CancelToken.CancelledException | IOException expected) { /* ok */ }
            assertFalse(r.dest.exists());
        }
    }
}
