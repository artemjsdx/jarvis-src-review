package app.jarvis.audio;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import app.jarvis.core.audio.CuePackCatalog;
import app.jarvis.core.log.Journal;
import app.jarvis.core.net.Downloader;
import app.jarvis.core.util.CancelToken;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Takes a user-picked file as the start or stop cue. The file is copied into {@link #dir} as custom-&lt;slot&gt;.&lt;ext&gt; and
 * must be OGG, WAV or MP3, at most {@link #MAX_BYTES} and at most {@link #MAX_MS} long. Nothing is converted on the device.
 */
public final class CueImport {
    public static final long MAX_BYTES = 300L * 1024;
    public static final long MAX_MS = 3000;
    private static final String[] EXTENSIONS = {"ogg", "wav", "mp3"};

    private CueImport() { }

    public static File dir(Context ctx) { return new File(ctx.getFilesDir(), "cues"); }

    public static File packDir(Context ctx) { return new File(dir(ctx), "packs/" + CuePackCatalog.PACK_ID); }

    /** Downloads and installs the CC0 cue pack (about 230 KB). Call off the main thread. */
    public static void installPack(Context ctx, Journal journal, Downloader.Progress progress, CancelToken cancel) throws IOException {
        CuePackCatalog.install(new Downloader(journal), CuePackCatalog.URL, CuePackCatalog.SHA256, packDir(ctx), progress, cancel);
    }

    /**
     * @param slot "start" or "stop"
     * @return the stored file name, to be saved as cue.custom.start or cue.custom.stop
     * @throws IOException with a Russian message the user can read
     */
    public static String importFile(Context ctx, Uri uri, String slot) throws IOException {
        if (!"start".equals(slot) && !"stop".equals(slot)) throw new IOException("Неизвестный сигнал: " + slot);
        String ext = extensionOf(ctx, uri);
        if (ext == null) throw new IOException("Формат не поддерживается: нужен OGG, WAV или MP3");
        File d = dir(ctx);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("Не удалось создать папку сигналов");
        File tmp = new File(d, "import-" + slot + ".tmp");
        try {
            copyLimited(ctx, uri, tmp);
            long ms = durationMs(tmp);
            if (ms <= 0) throw new IOException("В файле нет звука");
            if (ms > MAX_MS) throw new IOException("Звук длиннее 3 секунд");
            String name = "custom-" + slot + "." + ext;
            for (String e : EXTENSIONS) new File(d, "custom-" + slot + "." + e).delete();
            File target = new File(d, name);
            if (!tmp.renameTo(target)) throw new IOException("Не удалось сохранить сигнал");
            return name;
        } finally {
            tmp.delete();
        }
    }

    private static String extensionOf(Context ctx, Uri uri) {
        String type = ctx.getContentResolver().getType(uri);
        String name = displayName(ctx, uri);
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (type != null) {
            if (type.equals("audio/ogg") || type.equals("application/ogg")) return "ogg";
            if (type.equals("audio/wav") || type.equals("audio/x-wav") || type.equals("audio/wave")) return "wav";
            if (type.equals("audio/mpeg") || type.equals("audio/mp3")) return "mp3";
        }
        for (String e : EXTENSIONS) if (lower.endsWith("." + e)) return e;
        return null;
    }

    private static String displayName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static void copyLimited(Context ctx, Uri uri, File to) throws IOException {
        long total = 0;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(to)) {
            if (in == null) throw new IOException("Файл не открывается");
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new IOException("Файл больше 300 КБ");
                out.write(buf, 0, n);
            }
        }
    }

    /** Length of the audio in milliseconds; 0 when the file has no readable audio track. */
    private static long durationMs(File f) throws IOException {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getPath());
            if (!"yes".equals(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))) return 0;
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? 0 : Long.parseLong(d);
        } catch (RuntimeException e) {
            throw new IOException("Файл не распознан как звук", e);
        } finally {
            try { r.release(); } catch (IOException ignored) { }
        }
    }
}
