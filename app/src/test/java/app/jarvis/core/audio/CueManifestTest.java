package app.jarvis.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;

public class CueManifestTest {
    /** The real asset, found by walking up from the working directory (the runner starts in the project root or below). */
    private static String shippedManifest() throws Exception {
        File dir = new File("").getAbsoluteFile();
        while (dir != null && !new File(dir, "app/assets/sounds/sounds.json").isFile()) dir = dir.getParentFile();
        assertTrue("sounds.json not found", dir != null);
        return new String(Files.readAllBytes(new File(dir, "app/assets/sounds/sounds.json").toPath()), StandardCharsets.UTF_8);
    }

    @Test public void shippedManifestHasTenSetsWithThreeFilesEach() throws Exception {
        List<CueManifest.Set> sets = CueManifest.parse(shippedManifest());
        assertEquals(10, sets.size());
        CueManifest.Set crystal = CueManifest.find(sets, "crystal");
        assertEquals("crystal_start.ogg", crystal.start);
        assertEquals("crystal_stop.ogg", crystal.stop);
        assertEquals("crystal_error.ogg", crystal.error);
        assertEquals("Хрусталь", crystal.title);
        assertEquals(320, crystal.durationMs(CuePolicy.Kind.START));
        assertEquals(380, crystal.durationMs(CuePolicy.Kind.STOP));
    }

    @Test public void unknownIdFallsBackToDefaultSet() throws Exception {
        List<CueManifest.Set> sets = CueManifest.parse(shippedManifest());
        assertEquals("crystal", CueManifest.find(sets, "nope").id);
        assertEquals("marimba", CueManifest.find(sets, "marimba").id);
    }

    @Test public void malformedEntriesAreSkipped() throws Exception {
        String json = "[{\"id\":\"ok\",\"start\":\"ok_start.ogg\",\"stop\":\"ok_stop.ogg\",\"error\":\"ok_error.ogg\"},"
            + "{\"id\":\"../evil\",\"start\":\"x.ogg\",\"stop\":\"x.ogg\",\"error\":\"x.ogg\"},"
            + "{\"id\":\"bad\",\"start\":\"../../x.ogg\",\"stop\":\"x.ogg\",\"error\":\"x.ogg\"},"
            + "\"junk\"]";
        List<CueManifest.Set> sets = CueManifest.parse(json);
        assertEquals(1, sets.size());
        assertEquals("ok", sets.get(0).id);
        assertEquals(300, sets.get(0).startMs);
    }

    @Test public void durationsAreBounded() throws Exception {
        String json = "[{\"id\":\"a\",\"start\":\"a_start.ogg\",\"stop\":\"a_stop.ogg\",\"error\":\"a_error.ogg\","
            + "\"durationMs\":{\"start\":99999,\"stop\":0,\"error\":420}}]";
        CueManifest.Set a = CueManifest.parse(json).get(0);
        assertEquals(1000, a.startMs);
        assertEquals(1, a.stopMs);
        assertEquals(420, a.errorMs);
    }

    @Test public void emptyManifestHasNoSet() throws Exception {
        assertNull(CueManifest.find(CueManifest.parse("[]"), "crystal"));
    }
}
