package app.jarvis.core.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public class ZipSafeTest {
    private static File tmp() throws IOException { return Files.createTempDirectory("jv-zip").toFile(); }

    private static byte[] text(int n) {
        StringBuilder sb = new StringBuilder();
        Random r = new Random(7);
        while (sb.length() < n) sb.append("слово").append(r.nextInt(500)).append(' ');
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static File makeZip(File dir, String name, String[] names, byte[][] datas) throws IOException {
        File z = new File(dir, name);
        try (ZipOutputStream o = new ZipOutputStream(new FileOutputStream(z))) {
            for (int i = 0; i < names.length; i++) {
                o.putNextEntry(new ZipEntry(names[i]));
                if (datas[i] != null) o.write(datas[i]);
                o.closeEntry();
            }
        }
        return z;
    }

    @Test public void validArchiveExtractsAndStripsSingleRoot() throws Exception {
        File dir = tmp();
        File z = makeZip(dir, "m.zip", new String[]{"model-1.0/", "model-1.0/a.txt", "model-1.0/sub/b.bin"}, new byte[][]{null, text(5000), text(9000)});
        ZipSafe.verify(z);
        File dest = new File(dir, "model");
        ZipSafe.extract(z, dest, true, null);
        assertTrue(new File(dest, "a.txt").isFile());
        assertTrue(new File(dest, "sub/b.bin").isFile());
        assertFalse(new File(dest, "model-1.0").exists());
    }

    @Test public void twoTopLevelRootsAreNotStripped() throws Exception {
        File dir = tmp();
        File z = makeZip(dir, "m.zip", new String[]{"a/x.txt", "b/y.txt"}, new byte[][]{text(100), text(100)});
        File dest = new File(dir, "out");
        ZipSafe.extract(z, dest, true, null);
        assertTrue(new File(dest, "a/x.txt").isFile());
    }

    @Test public void truncatedArchiveIsDetectedBeforeAnythingIsInstalled() throws Exception {
        File dir = tmp();
        File z = makeZip(dir, "m.zip", new String[]{"m/big.txt"}, new byte[][]{text(400_000)});
        try (RandomAccessFile raf = new RandomAccessFile(z, "rw")) { raf.setLength(z.length() * 6 / 10); }   // the classic half-downloaded file
        try { ZipSafe.verify(z); fail("truncated zip must be rejected"); }
        catch (IOException e) { assertTrue(e.getMessage(), e.getMessage().contains("повреждён")); }
        File dest = new File(dir, "model");
        try { ZipSafe.extract(z, dest, true, null); fail(); } catch (IOException expected) { }
        assertFalse(dest.exists());
    }

    @Test public void corruptedDeflateDataWithIntactDirectoryIsDetected() throws Exception {
        File dir = tmp();
        File z = makeZip(dir, "m.zip", new String[]{"big.txt"}, new byte[][]{text(300_000)});
        try (RandomAccessFile raf = new RandomAccessFile(z, "rw")) {
            raf.seek(z.length() / 3);
            byte[] junk = new byte[400];
            new Random(1).nextBytes(junk);
            raf.write(junk);
        }
        try { ZipSafe.verify(z); fail("corrupted data must be rejected"); }
        catch (IOException e) { assertTrue(e.getMessage(), e.getMessage().contains("повреждён")); }
    }

    @Test public void zipSlipEntryIsRejectedAndNothingEscapes() throws Exception {
        File dir = tmp();
        File z = makeZip(dir, "evil.zip", new String[]{"ok.txt", "../../evil.txt"}, new byte[][]{text(10), text(10)});
        File dest = new File(dir, "out");
        try { ZipSafe.extract(z, dest, false, null); fail("zip-slip must be rejected"); }
        catch (IOException e) { assertTrue(e.getMessage(), e.getMessage().contains("Небезопасный")); }
        assertFalse(new File(dir.getParentFile(), "evil.txt").exists());
        assertFalse(new File(dir, "evil.txt").exists());
        assertFalse(dest.exists());
        for (File f : dir.listFiles()) assertFalse("temp dir must be cleaned: " + f, f.getName().contains(".tmp-"));
    }

    @Test public void failedUpdateKeepsThePreviousWorkingVersion() throws Exception {
        File dir = tmp();
        File dest = new File(dir, "model");
        File v1 = makeZip(dir, "v1.zip", new String[]{"old.txt"}, new byte[][]{"v1".getBytes()});
        ZipSafe.extract(v1, dest, false, null);
        File bad = makeZip(dir, "bad.zip", new String[]{"new.txt", "../x"}, new byte[][]{text(10), text(10)});
        try { ZipSafe.extract(bad, dest, false, null); fail(); } catch (IOException expected) { }
        assertTrue("previous version must survive a failed update", new File(dest, "old.txt").isFile());
        File v2 = makeZip(dir, "v2.zip", new String[]{"new.txt"}, new byte[][]{"v2".getBytes()});
        ZipSafe.extract(v2, dest, false, null);
        assertTrue(new File(dest, "new.txt").isFile());
        assertFalse(new File(dest, "old.txt").exists());
        for (File f : dir.listFiles()) assertFalse("old copy must be removed after success: " + f, f.getName().contains(".old-"));
    }

    @Test public void sizeLimitStopsDecompressionBomb() throws Exception {
        File dir = tmp();
        File z = makeZip(dir, "b.zip", new String[]{"zeros.bin"}, new byte[][]{new byte[2_000_000]});
        ZipSafe.Limits lim = new ZipSafe.Limits();
        lim.maxTotal = 100_000;
        try { ZipSafe.extract(z, new File(dir, "o"), false, lim); fail(); }
        catch (IOException e) { assertTrue(e.getMessage(), e.getMessage().contains("лимит")); }
        assertFalse(new File(dir, "o").exists());
    }

    @Test public void safeNameRejectsTraversal() throws Exception {
        assertEquals("a/b", ZipSafe.safeName("/a/b"));
        try { ZipSafe.safeName("a/../../b"); fail(); } catch (IOException expected) { }
        try { ZipSafe.safeName("..\\x"); fail(); } catch (IOException expected) { }
    }
}
