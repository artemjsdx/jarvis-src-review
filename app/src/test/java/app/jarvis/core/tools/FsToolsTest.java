package app.jarvis.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.agent.ToolResult;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class FsToolsTest extends ToolsTestBase {
    @Before public void setUp() throws Exception { init(); }

    @Test public void writeReadAppendRoundTrip() throws Exception {
        ToolResult w = call("fs_write", "{\"path\":\"docs/n.txt\",\"content\":\"раз\"}");
        assertTrue(w.error, w.ok);
        assertEquals(6, w.data.getInt("size_on_disk"));                 // "раз" is 6 bytes in UTF-8
        assertTrue(call("fs_write", "{\"path\":\"docs/n.txt\",\"content\":\"два\",\"append\":true}").ok);
        ToolResult r = call("fs_read", "{\"path\":\"docs/n.txt\"}");
        assertEquals("раздва", r.data.getString("content"));
        assertTrue(r.data.getBoolean("eof"));
        assertFalse("no temp files must stay behind", new File(sb.docs, ".n.txt.tmp").exists());
    }

    @Test public void readingInChunksNeverSplitsACyrillicLetter() throws Exception {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 3000; i++) big.append((char) ('а' + (i % 32)));
        Files.write(new File(sb.work, "big.txt").toPath(), big.toString().getBytes("UTF-8"));
        StringBuilder got = new StringBuilder();
        long off = 0;
        for (int guard = 0; guard < 100; guard++) {
            ToolResult r = call("fs_read", "{\"path\":\"work/big.txt\",\"offset\":" + off + ",\"max_bytes\":1001}");
            assertTrue(r.error, r.ok);
            String c = r.data.getString("content");
            assertFalse("replacement char means a split sequence", c.contains("\uFFFD"));
            got.append(c);
            off = r.data.getLong("next_offset");
            if (r.data.getBoolean("eof")) break;
        }
        assertEquals(big.toString(), got.toString());
    }

    @Test public void binaryFilesReturnMetadataOnly() throws Exception {
        byte[] b = new byte[2048];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (i * 7);
        b[10] = 0;
        Files.write(new File(sb.music, "a.mp3").toPath(), b);
        ToolResult r = call("fs_read", "{\"path\":\"music/a.mp3\"}");
        assertTrue(r.ok);
        assertTrue(r.data.getBoolean("binary"));
        assertFalse(r.data.has("content"));
        assertEquals("audio/mpeg", r.data.getString("mime"));
    }

    @Test public void readMissingFileFails() {
        ToolResult r = call("fs_read", "{\"path\":\"nope.txt\"}");
        assertFalse(r.ok);
        assertTrue(r.error, r.error.contains("не найден"));
    }

    @Test public void escapeAttemptsFailWithoutTouchingDisk() {
        ToolResult r = call("fs_write", "{\"path\":\"../evil.txt\",\"content\":\"x\"}");
        assertFalse(r.ok);
        assertFalse(new File(root.getParentFile(), "evil.txt").exists());
    }

    @Test public void coreDirectoriesAreProtected() {
        assertFalse(call("fs_delete", "{\"path\":\"work\",\"recursive\":true}").ok);
        assertFalse(call("fs_delete", "{\"path\":\"/\",\"recursive\":true}").ok);
        assertTrue(sb.work.isDirectory());
        assertFalse(call("fs_move", "{\"from\":\"music\",\"to\":\"work/m\"}").ok);
        assertFalse(call("fs_write", "{\"path\":\"work\",\"content\":\"x\"}").ok);
    }

    @Test public void listSortsDirsFirstAndMoveDeleteWork() throws Exception {
        call("fs_mkdir", "{\"path\":\"work/zdir\"}");
        call("fs_write", "{\"path\":\"work/a.txt\",\"content\":\"1\"}");
        ToolResult l = call("fs_list", "{\"path\":\"work\"}");
        JSONArray e = l.data.getJSONArray("entries");
        assertEquals("zdir", e.getJSONObject(0).getString("name"));
        assertEquals("a.txt", e.getJSONObject(1).getString("name"));
        assertTrue(call("fs_move", "{\"from\":\"work/a.txt\",\"to\":\"work/zdir\"}").ok);
        assertTrue(new File(sb.work, "zdir/a.txt").isFile());
        assertFalse("non-empty dir needs recursive", call("fs_delete", "{\"path\":\"work/zdir\"}").ok);
        ToolResult d = call("fs_delete", "{\"path\":\"work/zdir\",\"recursive\":true}");
        assertTrue(d.error, d.ok);
        assertFalse(new File(sb.work, "zdir").exists());
    }

    @Test public void modelTextShortensHugeOutputButStaysJson() throws Exception {
        StringBuilder sbd = new StringBuilder();
        for (int i = 0; i < 40000; i++) sbd.append('x');
        ToolResult r = ToolResult.ok(new JSONObject().put("stdout", sbd.toString()), "s");
        String t = r.toModelText(8000);
        assertTrue(t.length() < 9500);
        assertTrue(t.contains("обрезано"));
        assertTrue(new JSONObject(t).getBoolean("ok"));
    }
}
