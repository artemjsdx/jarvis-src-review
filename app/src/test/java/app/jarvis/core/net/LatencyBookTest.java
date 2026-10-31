package app.jarvis.core.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;

public class LatencyBookTest {
    @Test public void ewmaMedianAndWindow() {
        LatencyBook b = new LatencyBook(null);
        assertEquals(-1, b.ewma("llm:x"));
        assertEquals(-1, b.median("llm:x", 5));
        b.record("llm:x", 1000);
        assertEquals(1000, b.ewma("llm:x"));
        b.record("llm:x", 2000);
        assertEquals(1300, b.ewma("llm:x"));          // 0.3*2000 + 0.7*1000
        for (int i = 0; i < 20; i++) b.record("llm:y", i * 100);
        assertEquals(LatencyBook.KEEP, b.count("llm:y"));
        assertEquals(1750, b.median("llm:y", 4));     // last four: 1600,1700,1800,1900
    }

    @Test public void medianAboveNeedsEnoughSamples() {
        LatencyBook b = new LatencyBook(null);
        b.record("k", 5000); b.record("k", 6000);
        assertFalse(b.medianAbove("k", 5, 2000, 3));
        b.record("k", 4000);
        assertTrue(b.medianAbove("k", 5, 2000, 3));
        for (int i = 0; i < 5; i++) b.record("k", 500);
        assertFalse(b.medianAbove("k", 5, 2000, 3));
    }

    @Test public void persistsAndReloads() throws Exception {
        File f = new File(Files.createTempDirectory("jv-lat").toFile(), "lat.json");
        LatencyBook a = new LatencyBook(f);
        a.record("llm:openrouter", 3100); a.record("llm:openrouter", 2900); a.record("llm:openrouter", 3300);
        a.flush();
        LatencyBook b = new LatencyBook(f);
        assertEquals(3, b.count("llm:openrouter"));
        assertEquals(3100, b.median("llm:openrouter", 5));
        assertEquals(a.ewma("llm:openrouter"), b.ewma("llm:openrouter"));
    }

    @Test public void garbageFileIsIgnored() throws Exception {
        File f = new File(Files.createTempDirectory("jv-lat").toFile(), "lat.json");
        Files.write(f.toPath(), "{not json".getBytes("UTF-8"));
        assertEquals(-1, new LatencyBook(f).ewma("x"));
    }
}
