package app.jarvis.wake;

import static org.junit.Assert.*;

import app.jarvis.core.log.Journal;
import app.jarvis.core.util.CancelToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Install/uninstall/state-machine tests against a local fake model server (no network, no vosk natives). */
public class WakePackTest {
    private File tmp;
    private HttpServer server;
    private volatile byte[] payload = new byte[0];
    private final AtomicInteger requests = new AtomicInteger();

    @Before public void setUp() throws IOException {
        tmp = Files.createTempDirectory("wakepack-test").toFile();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model.zip", (HttpExchange ex) -> {
            requests.incrementAndGet();
            ex.getResponseHeaders().set("Content-Type", "application/zip");
            ex.sendResponseHeaders(200, payload.length == 0 ? -1 : payload.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(payload); }
            ex.close();
        });
        server.start();
    }

    @After public void tearDown() {
        server.stop(0);
        delete(tmp);
    }

    private static void delete(File f) {
        if (f.isDirectory()) for (File c : f.listFiles()) delete(c);
        f.delete();
    }

    /** A structurally complete tiny "model" (marker files only), content varied by {@code marker}. */
    private static byte[] modelZip(String marker) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            String root = WakePack.MODEL_NAME + "/";
            for (String name : new String[]{"am/final.mdl", "conf/model.conf", "graph/HCLr.fst"}) {
                z.putNextEntry(new ZipEntry(root + name));
                z.write((name + ":" + marker).getBytes("UTF-8"));
                z.closeEntry();
            }
        }
        return bo.toByteArray();
    }

    private WakePack pack(String sha, long size, String version) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/model.zip";
        return new WakePack(new File(tmp, "pack"), new File(tmp, "work"), Journal.NOP, url, sha, "", size, version);
    }

    @Test public void installExtractsAndVerifies() throws IOException {
        payload = modelZip("v1");
        WakePack p = pack(sha256(payload), payload.length, "test1");
        assertEquals(WakePack.PackState.NOT_INSTALLED, p.status().state);
        List<WakePack.PackState> seen = new ArrayList<>();
        p.install(s -> seen.add(s.state), new CancelToken());
        assertTrue(p.isInstalled());
        assertNotNull(p.installedPath());
        assertTrue(p.sizeBytes() > 0);
        assertEquals(WakePack.PackState.INSTALLED, p.status().state);
        assertEquals("test1", p.status().modelVersion);
        assertTrue("marker file written", new File(p.installedPath(), "jarvis-pack.properties").isFile());
        assertEquals("pack content", "am/final.mdl:v1",
            new String(Files.readAllBytes(new File(p.installedPath(), "am/final.mdl").toPath()), "UTF-8"));
        assertTrue("download+verify+install reported", seen.contains(WakePack.PackState.DOWNLOADING)
            && seen.contains(WakePack.PackState.VERIFYING) && seen.contains(WakePack.PackState.INSTALLED));
    }

    @Test public void installIsIdempotent() throws IOException {
        payload = modelZip("v1");
        WakePack p = pack(sha256(payload), payload.length, "test1");
        p.install(null, new CancelToken());
        requests.set(0);
        p.install(null, new CancelToken());
        assertEquals("no re-download for the same version", 0, requests.get());
        assertEquals(WakePack.PackState.INSTALLED, p.status().state);
    }

    @Test public void checksumMismatchLeavesNothingBehind() throws IOException {
        payload = modelZip("v1");
        WakePack p = pack("0000000000000000000000000000000000000000000000000000000000000000", payload.length, "test1");
        try {
            p.install(null, new CancelToken());
            fail("expected checksum failure");
        } catch (IOException e) { /* expected */ }
        assertEquals(WakePack.PackState.ERROR, p.status().state);
        assertFalse(p.status().message.isEmpty());
        assertFalse("no half-installed pack", p.isInstalled());
        assertFalse(new File(p.installedPath() == null ? tmp : p.installedPath(), "am/final.mdl").exists());
    }

    @Test public void failedUpdateKeepsPreviousVersion() throws IOException {
        payload = modelZip("v1");
        WakePack good = pack(sha256(payload), payload.length, "test1");
        good.install(null, new CancelToken());
        // now serve a corrupt archive under a different declared checksum
        payload = "this is not a zip file".getBytes("UTF-8");
        WakePack bad = pack(sha256(modelZip("v2")), payload.length, "test2");
        try {
            bad.install(null, new CancelToken());
            fail("expected corrupt-archive failure");
        } catch (IOException e) { /* expected */ }
        assertEquals(WakePack.PackState.ERROR, bad.status().state);
        assertTrue("previous pack still usable", good.isInstalled());
        assertEquals("test1", good.status().modelVersion);
        assertEquals("am/final.mdl:v1",
            new String(Files.readAllBytes(new File(good.installedPath(), "am/final.mdl").toPath()), "UTF-8"));
    }

    @Test public void uninstallRemovesEverything() throws IOException {
        payload = modelZip("v1");
        WakePack p = pack(sha256(payload), payload.length, "test1");
        p.install(null, new CancelToken());
        assertFalse("successful install deletes the cached zip",
            new File(tmp, "work/" + WakePack.ZIP_NAME).exists());
        p.uninstall();
        assertFalse(p.isInstalled());
        assertNull(p.installedPath());
        assertEquals(-1, p.sizeBytes());
        assertEquals(WakePack.PackState.NOT_INSTALLED, p.status().state);
        assertFalse("pack directory gone", new File(tmp, "pack").exists());
    }

    @Test public void cachedZipSkipsDownload() throws IOException {
        payload = modelZip("v1");
        WakePack p = pack(sha256(payload), payload.length, "test1");
        // pre-seed the cache the way a previous run would have left it
        File work = new File(tmp, "work");
        work.mkdirs();
        Files.write(new File(work, WakePack.ZIP_NAME).toPath(), payload);
        requests.set(0);
        p.install(null, new CancelToken());
        assertEquals("verified cache used, no download", 0, requests.get());
        assertTrue(p.isInstalled());
    }

    private String sha256(byte[] data) throws IOException {
        File t = File.createTempFile("sha", ".tmp", tmp);
        try { Files.write(t.toPath(), data); return app.jarvis.core.net.Downloader.hash(t, "SHA-256"); }
        finally { t.delete(); }
    }
}
