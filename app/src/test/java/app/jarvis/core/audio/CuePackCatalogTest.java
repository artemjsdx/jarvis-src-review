package app.jarvis.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.jarvis.core.net.Downloader;
import app.jarvis.core.util.CancelToken;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class CuePackCatalogTest {
    private HttpServer server;
    private File zip;
    private File work;

    @Before public void setUp() throws Exception {
        zip = new File(findRoot(), "app/src/test/resources/cuepack/ui_ogg.zip");
        work = Files.createTempDirectory("cuepack").toFile();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ui_ogg.zip", ex -> {
            byte[] b = Files.readAllBytes(zip.toPath());
            ex.getResponseHeaders().add("Content-Type", "application/zip");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        });
        server.start();
    }

    @After public void tearDown() {
        server.stop(0);
        deleteTree(work);
    }

    private String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/ui_ogg.zip"; }

    private static File findRoot() {
        File dir = new File("").getAbsoluteFile();
        while (dir != null && !new File(dir, "app/src/test/resources/cuepack/ui_ogg.zip").isFile()) dir = dir.getParentFile();
        if (dir == null) throw new AssertionError("cue pack fixture not found");
        return dir;
    }

    @Test public void installExtractsTheShortSetAndVerifiesChecksum() throws Exception {
        File packDir = new File(work, "oga-ui");
        assertFalse(CuePackCatalog.installed(packDir));
        CuePackCatalog.install(new Downloader(null), url(), CuePackCatalog.SHA256, packDir, null, new CancelToken());
        assertTrue(CuePackCatalog.installed(packDir));
        assertTrue(new File(packDir, "click_2.ogg").isFile());
        assertFalse("archive copies must not be left next to the pack", new File(work, "oga-ui.part.zip").exists());
        assertFalse(new File(work, "oga-ui.staging").exists());
    }

    @Test public void wrongChecksumInstallsNothing() throws Exception {
        File packDir = new File(work, "oga-ui");
        try {
            CuePackCatalog.install(new Downloader(null), url(), "0000000000000000000000000000000000000000000000000000000000000000",
                packDir, null, new CancelToken());
            fail("checksum mismatch must fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage() != null);
        }
        assertFalse(packDir.exists());
        assertFalse(new File(work, "oga-ui.part.zip").exists());
    }

    @Test public void setsAreShortEnoughToGateTheMicrophone() {
        for (CueManifest.Set s : CuePackCatalog.sets()) {
            for (CuePolicy.Kind k : CuePolicy.Kind.values()) {
                assertTrue(s.id + " " + k, s.durationMs(k) <= 450);
            }
        }
        assertEquals("oga_clicks", CuePackCatalog.setById("oga_clicks").id);
        assertEquals(null, CuePackCatalog.setById("crystal"));
    }

    /** One real download from the published URL; runs only when JV_LIVE_CUEPACK is set (uses the bench network). */
    @Test public void liveDownloadFromOpenGameArt() throws Exception {
        Assume.assumeTrue(System.getenv("JV_LIVE_CUEPACK") != null);
        File packDir = new File(work, "oga-ui");
        long t0 = System.nanoTime();
        CuePackCatalog.install(new Downloader(null), CuePackCatalog.URL, CuePackCatalog.SHA256, packDir, null, new CancelToken());
        System.out.println("LIVE cuepack installed in " + (System.nanoTime() - t0) / 1_000_000 + " ms, sha256 ok");
        assertTrue(CuePackCatalog.installed(packDir));
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
