package app.jarvis.core.voice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.jarvis.core.voice.TtsCacheTest.CollectingSink;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class CachingSinkTest {

    private static final byte[] CHUNK1 = "первый-кусок".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CHUNK2 = "второй-кусок".getBytes(StandardCharsets.UTF_8);
    private static final String KEY = TtsCache.key("p", "", "", 1f, "", "phrase");

    private static File tmpDir() throws IOException {
        return Files.createTempDirectory("cachingsink").toFile();
    }

    @Test
    public void commitsOnlyOnEnd() throws IOException {
        TtsCache cache = new TtsCache(tmpDir());
        CollectingSink player = new CollectingSink();
        CachingSink s = new CachingSink(cache, KEY, player);
        s.begin(AudioSpec.mp3());
        s.write(CHUNK1, 0, CHUNK1.length);
        s.write(CHUNK2, 0, CHUNK2.length);
        assertNull("no entry before end()", cache.get(KEY));
        s.end();
        TtsCache.Entry e = cache.get(KEY);
        assertNotNull(e);
        byte[] all = new byte[CHUNK1.length + CHUNK2.length];
        System.arraycopy(CHUNK1, 0, all, 0, CHUNK1.length);
        System.arraycopy(CHUNK2, 0, all, CHUNK1.length, CHUNK2.length);
        assertArrayEquals(all, Files.readAllBytes(e.file.toPath()));
        org.json.JSONObject hdr = new org.json.JSONObject(e.headerJson);
        assertEquals("MP3", hdr.getString("codec"));
        assertEquals(0, hdr.getInt("sampleRate"));
        assertEquals(0, hdr.getInt("channels"));
        // delegate saw everything
        assertTrue(player.ended);
        assertArrayEquals(all, player.bytes.toByteArray());
        assertEquals(AudioSpec.Codec.MP3, player.spec.codec);
    }

    @Test
    public void abortNeverCommits() throws IOException {
        TtsCache cache = new TtsCache(tmpDir());
        CollectingSink player = new CollectingSink();
        CachingSink s = new CachingSink(cache, KEY, player);
        s.begin(AudioSpec.wav());
        s.write(CHUNK1, 0, CHUNK1.length);
        s.abort();
        s.abort(); // idempotent
        assertNull(cache.get(KEY));
        assertTrue(player.aborted);
        assertFalse(new File(cache.dir(), KEY + ".aud").exists());
    }

    @Test
    public void delegateFailureToEndStillCommits() throws IOException {
        TtsCache cache = new TtsCache(tmpDir());
        AudioSink brokenPlayer = new CollectingSink() {
            @Override public void end() { throw new RuntimeException("playout boom"); }
        };
        CachingSink s = new CachingSink(cache, KEY, brokenPlayer);
        s.begin(AudioSpec.mp3());
        s.write(CHUNK1, 0, CHUNK1.length);
        try {
            s.end();
            fail("delegate exception must propagate");
        } catch (RuntimeException expected) {
            // recorded bytes are complete either way
        }
        assertNotNull("bytes were fully recorded, entry must be committed", cache.get(KEY));
    }

    @Test
    public void unusableCacheDoesNotBreakPlayback() throws IOException {
        // a file where the cache directory should be: mkdirs fails, beginWrite throws
        File blocker = File.createTempFile("blocker", ".tmp");
        blocker.deleteOnExit();
        TtsCache cache = new TtsCache(new File(blocker, "sub"));
        CollectingSink player = new CollectingSink();
        CachingSink s = new CachingSink(cache, KEY, player);
        s.begin(AudioSpec.mp3());          // must not throw
        s.write(CHUNK1, 0, CHUNK1.length); // must reach the delegate
        s.end();
        assertArrayEquals(CHUNK1, player.bytes.toByteArray());
        assertTrue(player.ended);
    }

    @Test
    public void replayFromCacheFeedsFreshSink() throws IOException {
        TtsCache cache = new TtsCache(tmpDir());
        CollectingSink first = new CollectingSink();
        CachingSink s = new CachingSink(cache, KEY, first);
        s.begin(AudioSpec.mp3());
        s.write(CHUNK1, 0, CHUNK1.length);
        s.end();

        CollectingSink second = new CollectingSink();
        assertTrue(cache.replay(KEY, second));
        assertArrayEquals(CHUNK1, second.bytes.toByteArray());
        assertTrue(second.ended);
    }
}
