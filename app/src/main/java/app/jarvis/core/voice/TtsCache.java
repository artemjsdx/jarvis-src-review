package app.jarvis.core.voice;

import org.json.JSONObject;

import app.jarvis.core.util.J;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/**
 * Disk cache of TTS audio. One entry = "&lt;sha1&gt;.aud" (exact provider bytes) plus "&lt;sha1&gt;.meta"
 * (tiny JSON header: codec/sampleRate/channels). The cache key is a SHA-1 of the full request identity
 * (providerId|voice|model|speed|settings|text), see {@link #key}.
 *
 * Properties: writes are atomic (temp file + rename), access is thread-safe, eviction is LRU by
 * last-modified with a total-size cap (default 64 MB) and an age cap (default 30 days). A commit
 * happens only via {@link Writer#commit()} — {@link CachingSink} calls it from end(), never on abort.
 */
public final class TtsCache {

    public static final long DEFAULT_MAX_BYTES = 64L * 1024 * 1024;
    public static final long DEFAULT_MAX_AGE_MS = 30L * 24 * 3600 * 1000;
    private static final int COPY_BUFFER = 16 * 1024;

    public static final class Entry {
        public final File file;
        public final String headerJson;
        public final long size;

        Entry(File file, String headerJson, long size) {
            this.file = file;
            this.headerJson = headerJson;
            this.size = size;
        }
    }

    /** Streaming write opened by {@link #beginWrite}; commit() makes the entry visible atomically. */
    public final class Writer {
        private final String key;
        private final File tmp;
        private final OutputStream out;
        private final String headerJson;
        private boolean done;

        Writer(String key, File tmp, OutputStream out, String headerJson) {
            this.key = key;
            this.tmp = tmp;
            this.out = out;
            this.headerJson = headerJson;
        }

        public void write(byte[] data, int off, int len) throws IOException {
            if (done) throw new IOException("cache write already finished");
            out.write(data, off, len);
        }

        /** Publishes the entry (atomic rename) and trims the cache to its limits. */
        public void commit() throws IOException {
            if (done) return;
            done = true;
            out.close();
            synchronized (TtsCache.this) {
                File aud = new File(dir, key + AUD_EXT);
                moveReplace(tmp, aud);
                File metaTmp = new File(dir, key + "." + threadTag() + META_TMP_EXT);
                try {
                    Files.write(metaTmp.toPath(), (headerJson + "\n").getBytes(StandardCharsets.UTF_8));
                    moveReplace(metaTmp, new File(dir, key + META_EXT));
                } finally {
                    if (metaTmp.isFile()) //noinspection ResultOfMethodCallIgnored
                        metaTmp.delete();
                }
                evictLocked();
            }
        }

        /** Discards the temp file. Safe to call repeatedly; the entry never becomes visible. */
        public void abort() {
            if (done) return;
            done = true;
            try { out.close(); } catch (IOException ignored) { }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private final File dir;
    private final long maxBytes;
    private long maxAgeMs = DEFAULT_MAX_AGE_MS;

    private static final String AUD_EXT = ".aud";
    private static final String META_EXT = ".meta";
    private static final String META_TMP_EXT = ".mtmp";
    private static final String TMP_MARK = ".tmp";

    public TtsCache(File dir) {
        this(dir, DEFAULT_MAX_BYTES);
    }

    public TtsCache(File dir, long maxBytes) {
        this.dir = dir;
        this.maxBytes = Math.max(1024, maxBytes);
    }

    /** Entries not touched for this long are treated as misses and removed. */
    public void setMaxAgeMs(long ms) { maxAgeMs = Math.max(1000, ms); }

    public File dir() { return dir; }

    /** Stable SHA-1 (40 hex chars) of the request identity; any component change yields a different key. */
    public static String key(String providerId, String voice, String model, float speed, String settings, String text) {
        String composite = nz(providerId) + "|" + nz(voice) + "|" + nz(model) + "|" + speed + "|" + nz(settings) + "|" + nz(text);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(composite.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 unavailable", e); // every JRE has SHA-1
        }
    }

    /** Convenience over a {@link TtsRequest}: options are serialized into settings (order-independent), incl. language and refs. */
    public static String keyFor(TtsRequest req, String providerId) {
        TreeMap<String, String> opts = new TreeMap<>(req.options);
        StringBuilder settings = new StringBuilder();
        for (TreeMap.Entry<String, String> e : opts.entrySet()) {
            if (settings.length() > 0) settings.append(';');
            settings.append(e.getKey()).append('=').append(e.getValue());
        }
        if (settings.length() > 0) settings.append(';');
        settings.append("lang=").append(req.language);
        if (!req.refAudioPath.isEmpty() || !req.refText.isEmpty())
            settings.append(";ref=").append(req.refAudioPath).append('|').append(req.refText);
        String model = opts.containsKey("model") ? opts.get("model") : "";
        return key(providerId, req.voiceId, model, req.speed, settings.toString(), req.text);
    }

    /** Returns the cached entry and refreshes its LRU time, or null on miss/corrupt/expired. */
    public synchronized Entry get(String key) throws IOException {
        File aud = new File(dir, key + AUD_EXT);
        File meta = new File(dir, key + META_EXT);
        if (!aud.isFile() || !meta.isFile()) return null;
        long now = System.currentTimeMillis();
        if (now - aud.lastModified() > maxAgeMs) {
            delete(aud);
            delete(meta);
            return null;
        }
        String header;
        try {
            byte[] b = Files.readAllBytes(meta.toPath());
            header = new String(b, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
        if (!isHeaderValid(header)) {
            delete(aud);
            delete(meta);
            return null;
        }
        //noinspection ResultOfMethodCallIgnored
        aud.setLastModified(now); // LRU touch
        return new Entry(aud, header, aud.length());
    }

    /**
     * Streams a cached entry into the sink (begin → writes → end) without network. Returns false on miss.
     * Throws IOException if the sink or the file fails mid-way; the caller is responsible for sink.abort().
     */
    public boolean replay(String key, AudioSink sink) throws IOException {
        Entry e = get(key);
        if (e == null) return false;
        AudioSpec spec = parseHeader(e.headerJson);
        sink.begin(spec);
        FileInputStream in = new FileInputStream(e.file);
        try {
            byte[] buf = new byte[COPY_BUFFER];
            int r;
            while ((r = in.read(buf)) > 0) sink.write(buf, 0, r);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
        sink.end();
        return true;
    }

    public Writer beginWrite(String key, String headerJson) throws IOException {
        if (headerJson == null || !isHeaderValid(headerJson)) headerJson = "{\"codec\":\"MP3\",\"sampleRate\":0,\"channels\":0}";
        synchronized (this) {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create cache dir " + dir);
        }
        File tmp = new File(dir, key + "." + threadTag() + TMP_MARK);
        OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp), COPY_BUFFER);
        return new Writer(key, tmp, out, headerJson);
    }

    /** Enforces size and age limits now (also runs after every commit). */
    public synchronized void evict() { evictLocked(); }

    public synchronized long totalBytes() { return scanAudFiles().total; }

    public synchronized int entryCount() { return scanAudFiles().files.size(); }

    /** Entries on disk for listing: no LRU touch and no expiry check (use get() for a real lookup). */
    public synchronized List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        for (File f : scanAudFiles().files) {
            String key = f.getName().substring(0, f.getName().length() - AUD_EXT.length());
            String header = "";
            try { header = new String(Files.readAllBytes(new File(dir, key + META_EXT).toPath()), StandardCharsets.UTF_8).trim(); } catch (IOException ignored) { }
            out.add(new Entry(f, header, f.length()));
        }
        return out;
    }

    // ------------------------------------------------------------------ internals

    private static class Scan {
        final List<File> files = new ArrayList<>();
        long total;
    }

    private Scan scanAudFiles() {
        Scan s = new Scan();
        File[] all = dir.listFiles();
        if (all == null) return s;
        for (File f : all) {
            String n = f.getName();
            if (n.endsWith(AUD_EXT) && n.length() == 40 + AUD_EXT.length()) {
                s.files.add(f);
                s.total += f.length();
            }
        }
        return s;
    }

    private void evictLocked() {
        File[] all = dir.listFiles();
        if (all == null) return;
        long now = System.currentTimeMillis();
        for (File f : all) {
            String n = f.getName();
            if (n.endsWith(TMP_MARK) && now - f.lastModified() > 3600_000L) delete(f); // stale temp from a crash
            else if (n.endsWith(META_EXT) || n.endsWith(META_TMP_EXT)) {
                File aud = new File(dir, n.substring(0, n.length() - (n.endsWith(META_EXT) ? META_EXT.length() : META_TMP_EXT.length())) + AUD_EXT);
                if (!aud.isFile()) delete(f); // orphan header
            } else if (n.endsWith(AUD_EXT) && now - f.lastModified() > maxAgeMs) {
                delete(f);
                delete(new File(dir, n.substring(0, n.length() - AUD_EXT.length()) + META_EXT));
            }
        }
        Scan s = scanAudFiles();
        if (s.total <= maxBytes) return;
        File[] byAge = s.files.toArray(new File[0]);
        Arrays.sort(byAge, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                int c = Long.compare(a.lastModified(), b.lastModified());
                return c != 0 ? c : a.getName().compareTo(b.getName());
            }
        });
        for (File f : byAge) {
            if (s.total <= maxBytes) break;
            s.total -= f.length();
            delete(f);
            delete(new File(dir, f.getName().substring(0, f.getName().length() - AUD_EXT.length()) + META_EXT));
        }
    }

    private static void delete(File f) {
        try { Files.deleteIfExists(f.toPath()); } catch (IOException ignored) { }
    }

    private static void moveReplace(File from, File to) throws IOException {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String threadTag() {
        return Long.toHexString(Thread.currentThread().getId()) + "-" + System.nanoTime();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static boolean isHeaderValid(String json) {
        JSONObject o = J.parseOrNull(json);
        return o != null && J.str(o, "codec").length() > 0;
    }

    /** Parses the tiny header JSON; falls back to MP3/unknown when fields are missing. */
    public static AudioSpec parseHeader(String headerJson) {
        JSONObject o = J.parseOrNull(headerJson);
        AudioSpec.Codec codec = AudioSpec.Codec.MP3;
        int rate = 0, ch = 0;
        if (o != null) {
            try { codec = AudioSpec.Codec.valueOf(J.str(o, "codec", "MP3")); } catch (IllegalArgumentException ignored) { }
            rate = J.num(o, "sampleRate", 0);
            ch = J.num(o, "channels", 0);
        }
        return new AudioSpec(codec, rate, ch);
    }

    public static String headerJson(AudioSpec spec) {
        JSONObject o = new JSONObject();
        J.put(o, "codec", spec.codec.name());
        J.put(o, "sampleRate", spec.sampleRate);
        J.put(o, "channels", spec.channels);
        return o.toString();
    }
}
