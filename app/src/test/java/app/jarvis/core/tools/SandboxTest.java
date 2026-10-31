package app.jarvis.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Test;

public class SandboxTest extends ToolsTestBase {
    @Before public void setUp() throws Exception { init(); }

    private void assertEscapes(String p) {
        try {
            File f = sb.resolve(p);
            fail("должен быть отклонён: " + p + " → " + f);
        } catch (IOException expected) { /* ok */ }
    }

    @Test public void dotDotEscapesAreRejected() {
        assertEscapes("../x");
        assertEscapes("work/../../x");
        assertEscapes("music/../../../etc/passwd");
    }

    @Test public void rootRelativeAndEmptyPaths() throws Exception {
        assertEquals(sb.root, sb.resolve(""));
        assertEquals(sb.root, sb.resolve("/"));
        assertEquals(new File(sb.work, "a.txt"), sb.resolve("/work/a.txt"));
        assertEquals(new File(sb.music, "s.mp3"), sb.resolve("music/s.mp3"));
        assertEquals(new File(sb.music, "s.mp3"), sb.resolve(sb.music.getPath() + "/s.mp3"));
    }

    @Test public void foreignAbsolutePathIsMappedInsideNotFollowedOutside() throws Exception {
        File f = sb.resolve("/etc/passwd");
        assertTrue(f.getPath(), f.getPath().startsWith(sb.root.getPath() + File.separator));
    }

    @Test public void symlinkLeavingTheSandboxIsRejected() throws Exception {
        File outside = Files.createTempDirectory("jv-outside").toFile();
        Files.write(new File(outside, "secret.txt").toPath(), "x".getBytes());
        Files.createSymbolicLink(new File(sb.work, "link").toPath(), outside.toPath());
        assertEscapes("work/link/secret.txt");
        assertEscapes("work/link");
    }

    @Test public void explicitlyAllowedExternalDirectoryWorks() throws Exception {
        File outside = Files.createTempDirectory("jv-ext").toFile();
        // without permission an absolute foreign path is mapped *inside* the sandbox, never followed outside
        assertTrue(sb.resolve(outside.getPath() + "/a.txt").getPath().startsWith(sb.root.getPath() + File.separator));
        sb.allowExternal(outside);
        assertEquals(new File(outside.getCanonicalFile(), "a.txt"), sb.resolve(outside.getPath() + "/a.txt"));
        sb.clearExternal();
        assertTrue(sb.resolve(outside.getPath() + "/a.txt").getPath().startsWith(sb.root.getPath()));
    }
}
