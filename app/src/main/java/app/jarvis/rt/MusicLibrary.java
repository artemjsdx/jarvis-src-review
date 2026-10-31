package app.jarvis.rt;

import android.media.MediaMetadataRetriever;
import app.jarvis.core.tools.Sandbox;
import app.jarvis.core.util.Mime;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Finds playable audio files inside the sandbox and describes them (title, duration) from the real file metadata. */
public final class MusicLibrary {
    private MusicLibrary() { }

    public static final class Track {
        public File file;
        public String path = "", title = "", artist = "", mime = "";
        public long size, durationMs = -1, modified;
        public String durationText() { return durationMs < 0 ? "" : String.format(Locale.US, "%d:%02d", durationMs / 60000, durationMs / 1000 % 60); }
    }

    public static boolean isAudio(File f) { return f.isFile() && f.length() > 0 && Mime.isAudio(f.getName()); }

    /** Scans music/, inbox/, downloads/ (in that order), non-recursive plus one level of subfolders. */
    public static List<Track> scan(Sandbox sb, boolean withMeta, int limit) {
        List<Track> out = new ArrayList<>();
        for (File dir : new File[]{sb.music, sb.inbox, sb.downloads}) collect(sb, dir, 0, out, withMeta, limit);
        return out;
    }

    private static void collect(Sandbox sb, File dir, int depth, List<Track> out, boolean meta, int limit) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        Arrays.sort(fs, new Comparator<File>() { @Override public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); } });
        for (File f : fs) {
            if (out.size() >= limit) return;
            if (f.isDirectory() && depth < 1) collect(sb, f, depth + 1, out, meta, limit);
            else if (isAudio(f)) out.add(describe(sb, f, meta));
        }
    }

    public static Track describe(Sandbox sb, File f, boolean meta) {
        Track t = new Track();
        t.file = f;
        t.path = sb.rel(f);
        t.size = f.length();
        t.modified = f.lastModified();
        t.mime = Mime.guess(f.getName());
        String n = f.getName();
        int d = n.lastIndexOf('.');
        t.title = d > 0 ? n.substring(0, d) : n;
        if (meta) {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(f.getPath());
                String title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                String artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                String dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (title != null && !title.trim().isEmpty()) t.title = title.trim();
                if (artist != null) t.artist = artist.trim();
                if (dur != null) t.durationMs = Long.parseLong(dur.trim());
            } catch (Throwable ignored) {
                // unreadable metadata is not an error here: playback reports real problems
            } finally {
                try { r.release(); } catch (Throwable ignored) { }
            }
        }
        return t;
    }

    /** Newest audio file by modification time in inbox/, else downloads/; null if none. */
    public static File latestReceived(Sandbox sb) {
        File best = null;
        for (File dir : new File[]{sb.inbox, sb.downloads}) {
            File[] fs = dir.listFiles();
            if (fs == null) continue;
            for (File f : fs) if (isAudio(f) && (best == null || f.lastModified() > best.lastModified())) best = f;
            if (best != null) return best;
        }
        return best;
    }

    public static List<File> siblings(File f) {
        File[] fs = f.getParentFile() == null ? null : f.getParentFile().listFiles();
        List<File> out = new ArrayList<>();
        if (fs != null) for (File x : fs) if (isAudio(x)) out.add(x);
        Collections.sort(out, new Comparator<File>() { @Override public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); } });
        return out;
    }
}
