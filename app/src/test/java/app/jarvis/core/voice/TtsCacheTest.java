package app.jarvis.core.voice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TtsCacheTest {

    private static final byte[] DATA = "АУДИО-ДАННЫЕ-0123456789".getBytes(StandardCharsets.UTF_8);
    private static final String HDR = "{\"codec\":\"MP3\",\"sampleRate\":24000,\"channels\":1}";

    private static File tmpDir() throws IOException {
        return Files.createTempDirectory("ttscache").toFile();
    }

    private static void put(TtsCache c, String key, byte[] data) throws IOException {
        TtsCache.Writer w = c.beginWrite(key, HDR);
        w.write(data, 0, data.length);
        w.commit();
    }

    @Test
    public void keyIsDeterministicAndSensitive() {
        String a = TtsCache.key("edge", "svetlana", "", 1f, "", "привет");
        assertEquals(a, TtsCache.key("edge", "svetlana", "", 1f, "", "привет"));
        assertFalse(a.equals(TtsCache.key("edge", "svetlana", "", 1f, "", "привет!")));
        assertFalse(a.equals(TtsCache.key("edge", "dmitry", "", 1f, "", "привет")));
        assertFalse(a.equals(TtsCache.key("edge", "svetlana", "", 1.5f, "", "привет")));
        assertFalse(a.equals(TtsCache.key("edge", "svetlana", "", 1f, "stability=0.5", "привет")));
        assertEquals(40, a.length());
        TtsRequest r = new TtsRequest();
        r.text = "текст";
        r.voiceId = "v";
        r.speed = 2f;
        r.options.put("model", "m");
        String k1 = TtsCache.keyFor(r, "p");
        String k2 = TtsCache.keyFor(r, "p");
        assertEquals(k1, k2);
        r.options.put("stability", "0.5");
        assertFalse(k1.equals(TtsCache.keyFor(r, "p")));
        assertFalse(k1.equals(TtsCache.keyFor(r, "q")));
    }

    @Test
    public void putGetExactBytes() throws IOException {
        TtsCache c = new TtsCache(tmpDir());
        String k = TtsCache.key("p", "", "", 1f, "", "t");
        put(c, k, DATA);
        TtsCache.Entry e = c.get(k);
        assertNotNull(e);
        assertEquals(DATA.length, e.size);
        assertEquals(HDR, e.headerJson);
        assertArrayEquals(DATA, Files.readAllBytes(e.file.toPath()));
        AudioSpec spec = TtsCache.parseHeader(HDR);
        assertEquals(AudioSpec.Codec.MP3, spec.codec);
        assertEquals(24000, spec.sampleRate);
        assertEquals(1, spec.channels);
        assertEquals(1, c.entryCount());
        assertEquals(DATA.length, c.totalBytes());
    }

    @Test
    public void replayStreamsToSink() throws Exception {
        TtsCache c = new TtsCache(tmpDir());
        String k = TtsCache.key("p", "", "", 1f, "", "t");
        assertFalse("miss before put", c.replay(k, new CollectingSink()));
        put(c, k, DATA);
        CollectingSink sink = new CollectingSink();
        assertTrue(c.replay(k, sink));
        assertEquals(AudioSpec.Codec.MP3, sink.spec.codec);
        assertEquals(24000, sink.spec.sampleRate);
        assertArrayEquals(DATA, sink.bytes.toByteArray());
        assertTrue(sink.ended);
    }

    @Test
    public void missOnIncompleteOrCorruptEntries() throws IOException {
        TtsCache c = new TtsCache(tmpDir());
        String k = TtsCache.key("p", "", "", 1f, "", "t");
        assertNull(c.get(k));
        // meta without audio
        Files.write(new File(c.dir(), k + ".meta").toPath(), HDR.getBytes(StandardCharsets.UTF_8));
        assertNull(c.get(k));
        // audio without meta
        put(c, k, DATA);
        //noinspection ResultOfMethodCallIgnored
        new File(c.dir(), k + ".meta").delete();
        assertNull(c.get(k));
        // corrupt meta
        put(c, k, DATA);
        Files.write(new File(c.dir(), k + ".meta").toPath(), "garbage{{".getBytes(StandardCharsets.UTF_8));
        assertNull(c.get(k));
        assertEquals(0, c.entryCount());
    }

    @Test
    public void lruEvictionBySize() throws IOException {
        TtsCache c = new TtsCache(tmpDir(), 1000);
        String ka = TtsCache.key("p", "a", "", 1f, "", "t");
        String kb = TtsCache.key("p", "b", "", 1f, "", "t");
        String kc = TtsCache.key("p", "c", "", 1f, "", "t");
        long t = System.currentTimeMillis() - 1_000_000;
        put(c, ka, new byte[400]);
        //noinspection ResultOfMethodCallIgnored
        new File(c.dir(), ka + ".aud").setLastModified(t);
        put(c, kb, new byte[400]);
        //noinspection ResultOfMethodCallIgnored
        new File(c.dir(), kb + ".aud").setLastModified(t + 1000);
        put(c, kc, new byte[400]); // fresh; total 1200 > 1000 → oldest (a) must go
        assertNull("oldest entry evicted", c.get(ka));
        assertNotNull(c.get(kc));
        assertTrue(c.totalBytes() <= 1000);

        // make c explicitly older than "now": otherwise c's write, b's touch and d's write can share one
        // millisecond and the eviction order between them becomes a tie
        //noinspection ResultOfMethodCallIgnored
        new File(c.dir(), kc + ".aud").setLastModified(t + 2000);
        // touch b, then add d → c (now oldest untouched) goes instead of b
        String kd = TtsCache.key("p", "d", "", 1f, "", "t");
        assertNotNull(c.get(kb)); // LRU touch
        put(c, kd, new byte[400]);
        assertNull(c.get(kc));
        assertNotNull(c.get(kb));
        assertNotNull(c.get(kd));
    }

    @Test
    public void ageEviction() throws IOException, InterruptedException {
        TtsCache c = new TtsCache(tmpDir());
        c.setMaxAgeMs(1000);
        String k = TtsCache.key("p", "", "", 1f, "", "t");
        put(c, k, DATA);
        //noinspection ResultOfMethodCallIgnored
        new File(c.dir(), k + ".aud").setLastModified(System.currentTimeMillis() - 60_000);
        assertNull(c.get(k));
        assertFalse(new File(c.dir(), k + ".aud").exists());
        assertFalse(new File(c.dir(), k + ".meta").exists());
    }

    @Test
    public void noCommitNoEntryAndStaleTempsAreCleaned() throws IOException, InterruptedException {
        TtsCache c = new TtsCache(tmpDir());
        String k = TtsCache.key("p", "", "", 1f, "", "t");
        TtsCache.Writer w = c.beginWrite(k, HDR);
        w.write(DATA, 0, DATA.length);
        w.abort();          // explicit abort
        w.abort();          // idempotent
        assertNull(c.get(k));

        // simulate a crash mid-write: temp file left behind
        File tmp = new File(c.dir(), k + ".1-123.tmp");
        Files.write(tmp.toPath(), DATA);
        //noinspection ResultOfMethodCallIgnored
        tmp.setLastModified(System.currentTimeMillis() - 7200_000);
        c.evict();          // stale temp is garbage-collected
        assertFalse(tmp.exists());
        assertNull(c.get(k));
    }

    @Test
    public void concurrentWritersDistinctKeys() throws Exception {
        TtsCache c = new TtsCache(tmpDir(), 8 * 1024 * 1024);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger failures = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            final int tid = t;
            futures.add(pool.submit((Callable<Void>) () -> {
                try {
                    for (int i = 0; i < 25; i++) {
                        String k = TtsCache.key("p", "t" + tid, "", 1f, "", "text" + i);
                        byte[] data = new byte[1000 + tid * 100 + i];
                        java.util.Arrays.fill(data, (byte) ('A' + tid));
                        put(c, k, data);
                        TtsCache.Entry e = c.get(k);
                        if (e != null) assertArrayEquals(data, Files.readAllBytes(e.file.toPath()));
                    }
                } catch (Throwable th) {
                    failures.incrementAndGet();
                }
                return null;
            }));
        }
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(0, failures.get());
        assertEquals(8 * 25, c.entryCount());
        assertEquals(0, orphanMetaCount(c));
    }

    @Test
    public void concurrentWritersSameKey() throws Exception {
        TtsCache c = new TtsCache(tmpDir());
        String k = TtsCache.key("p", "", "", 1f, "", "same");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        AtomicInteger failures = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 6; t++) {
            final int tid = t;
            futures.add(pool.submit((Callable<Void>) () -> {
                try {
                    for (int i = 0; i < 30; i++) {
                        TtsCache.Writer w = c.beginWrite(k, HDR);
                        w.write(new byte[]{(byte) tid, (byte) i}, 0, 2);
                        w.commit();
                        c.get(k);
                    }
                } catch (Throwable th) {
                    failures.incrementAndGet();
                }
                return null;
            }));
        }
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(0, failures.get());
        TtsCache.Entry e = c.get(k);
        assertNotNull(e);
        assertEquals(2, e.size);
        assertEquals(1, c.entryCount());
    }

    @Test
    public void evictionUnderConcurrentPressure() throws Exception {
        TtsCache c = new TtsCache(tmpDir(), 32 * 1024); // small: forces eviction while writing
        ExecutorService pool = Executors.newFixedThreadPool(6);
        AtomicInteger failures = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 6; t++) {
            final int tid = t;
            futures.add(pool.submit((Callable<Void>) () -> {
                try {
                    for (int i = 0; i < 20; i++) {
                        String k = TtsCache.key("p", "u" + tid, "", 1f, "", "n" + i);
                        put(c, k, new byte[3000]);
                        c.get(k);
                    }
                } catch (Throwable th) {
                    failures.incrementAndGet();
                }
                return null;
            }));
        }
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(0, failures.get());
        assertTrue("total " + c.totalBytes(), c.totalBytes() <= 32 * 1024);
        assertEquals(0, orphanMetaCount(c));
    }

    /** Counts .meta files that have no .aud sibling — those must be cleaned up by eviction. */
    private static int orphanMetaCount(TtsCache c) {
        File[] all = c.dir().listFiles();
        int n = 0;
        if (all != null) for (File f : all) {
            String name = f.getName();
            if (name.endsWith(".meta")) {
                File aud = new File(c.dir(), name.substring(0, name.length() - 5) + ".aud");
                if (!aud.isFile()) n++;
            }
        }
        return n;
    }

    /** Minimal in-memory AudioSink for replay tests. */
    static class CollectingSink implements AudioSink {
        AudioSpec spec;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean ended, aborted;

        @Override public void begin(AudioSpec s) { spec = s; }

        @Override public void write(byte[] data, int off, int len) { bytes.write(data, off, len); }

        @Override public void end() { ended = true; }

        @Override public void abort() { aborted = true; }
    }
}
