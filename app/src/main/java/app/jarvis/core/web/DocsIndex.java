package app.jarvis.core.web;

import app.jarvis.core.tools.Sandbox;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Searchable index of text documents under the sandbox's downloads/ and docs/ folders. Built lazily, kept in memory, and
 * refreshed on every search: files whose modification time or size changed are re-read, vanished files are dropped.
 * Scoring is a simplified BM25 over lower-cased word tokens; there is no stemming.
 */
public final class DocsIndex {
    public static final long MAX_FILE_BYTES = 512L * 1024;
    static final int MAX_FILES = 2000, MAX_DEPTH = 8, SNIPPET = 220;
    private static final Set<String> EXT = new HashSet<>(Arrays.asList("md", "txt", "html", "htm", "json", "py", "java", "kt", "js",
        "ts", "sh", "xml", "csv", "yaml", "yml", "rst", "log", "cfg", "ini", "toml", "c", "h", "cpp", "go", "rs", "gradle",
        "properties", "tex"));
    private static final Pattern SPLIT = Pattern.compile("[^\\p{L}\\p{N}_]+");
    private static final double K1 = 1.2, B = 0.75;

    public static final class Hit {
        public final String path, snippet;
        public final double score;

        Hit(String path, double score, String snippet) {
            this.path = path;
            this.score = score;
            this.snippet = snippet;
        }
    }

    public static final class Result {
        public final List<Hit> hits;
        public final int indexed;

        Result(List<Hit> hits, int indexed) {
            this.hits = hits;
            this.indexed = indexed;
        }
    }

    private static final class Entry {
        final File file;
        final long mtime, size;
        final Map<String, Integer> tf;
        final int len;

        Entry(File file, long mtime, long size, Map<String, Integer> tf, int len) {
            this.file = file;
            this.mtime = mtime;
            this.size = size;
            this.tf = tf;
            this.len = len;
        }
    }

    private final Sandbox sb;
    private final Map<String, Entry> entries = new HashMap<>();

    public DocsIndex(Sandbox sb) {
        this.sb = sb;
    }

    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        for (String t : SPLIT.split(text.toLowerCase(Locale.ROOT))) {
            if (t.length() >= 2) out.add(t.length() > 64 ? t.substring(0, 64) : t);
        }
        return out;
    }

    public synchronized Result search(String query, int limit) throws IOException {
        refresh();
        List<String> terms = new ArrayList<>(new HashSet<>(tokens(query)));
        if (terms.isEmpty()) return new Result(new ArrayList<Hit>(), entries.size());
        int n = entries.size();
        double avg = 1;
        if (n > 0) {
            long total = 0;
            for (Entry e : entries.values()) total += e.len;
            avg = Math.max(1.0, (double) total / n);
        }
        Map<String, Integer> df = new HashMap<>();
        for (String t : terms) {
            int d = 0;
            for (Entry e : entries.values()) if (e.tf.containsKey(t)) d++;
            df.put(t, d);
        }
        List<Object[]> scored = new ArrayList<>();
        for (Entry e : entries.values()) {
            double score = 0;
            String name = e.file.getName().toLowerCase(Locale.ROOT);
            for (String t : terms) {
                Integer f = e.tf.get(t);
                int d = df.get(t);
                if (d == 0) continue;
                double idf = Math.log(1 + (n - d + 0.5) / (d + 0.5));
                if (f != null) score += idf * f * (K1 + 1) / (f + K1 * (1 - B + B * e.len / avg));
                if (name.contains(t)) score += idf;
            }
            if (score > 0) scored.add(new Object[]{e, score});
        }
        scored.sort((a, b) -> Double.compare((Double) b[1], (Double) a[1]));
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < scored.size() && hits.size() < limit; i++) {
            Entry e = (Entry) scored.get(i)[0];
            hits.add(new Hit(sb.rel(e.file), (Double) scored.get(i)[1], snippet(e.file, terms)));
        }
        return new Result(hits, n);
    }

    private void refresh() throws IOException {
        Map<String, File> present = new HashMap<>();
        for (File root : new File[]{sb.downloads, sb.docs}) collect(root, 0, present);
        entries.keySet().retainAll(present.keySet());
        for (Map.Entry<String, File> me : present.entrySet()) {
            File f = me.getValue();
            Entry old = entries.get(me.getKey());
            if (old != null && old.mtime == f.lastModified() && old.size == f.length()) continue;
            String text = readText(f);
            if (text == null) { entries.remove(me.getKey()); continue; }
            Map<String, Integer> tf = new HashMap<>();
            List<String> toks = tokens(text);
            for (String t : toks) tf.merge(t, 1, Integer::sum);
            entries.put(me.getKey(), new Entry(f, f.lastModified(), f.length(), tf, toks.size()));
        }
    }

    private static void collect(File dir, int depth, Map<String, File> out) {
        File[] kids = dir.listFiles();
        if (kids == null || depth > MAX_DEPTH) return;
        for (File f : kids) {
            if (out.size() >= MAX_FILES) return;
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) collect(f, depth + 1, out);
            else if (f.isFile() && EXT.contains(ext(f.getName())) && f.length() <= MAX_FILE_BYTES) out.put(f.getAbsolutePath(), f);
        }
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Plain text of a document; HTML is converted so that markup does not pollute the index. Null when unreadable. */
    private static String readText(File f) {
        try {
            byte[] b = Files.readAllBytes(f.toPath());
            String s = new String(b, StandardCharsets.UTF_8);
            String e = ext(f.getName());
            if (e.equals("html") || e.equals("htm")) return HtmlToText.convert(s, "", false).text;
            return s;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private String snippet(File f, List<String> terms) {
        String text = readText(f);
        if (text == null) return "";
        String low = text.toLowerCase(Locale.ROOT);
        int at = -1;
        for (String t : terms) {
            int i = low.indexOf(t);
            if (i >= 0 && (at < 0 || i < at)) at = i;
        }
        if (at < 0) return HtmlToText.collapse(text.substring(0, Math.min(SNIPPET, text.length()))).trim();
        int from = Math.max(0, at - SNIPPET / 2), to = Math.min(text.length(), at + SNIPPET / 2);
        return (from > 0 ? "…" : "") + HtmlToText.collapse(text.substring(from, to)).trim() + (to < text.length() ? "…" : "");
    }
}
