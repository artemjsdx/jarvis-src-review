package app.jarvis.ui.files;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import app.jarvis.core.tools.Sandbox;
import app.jarvis.core.util.CancelToken;
import app.jarvis.rt.Attachments;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Streaming copies for the Files screen: picked documents into the current folder, file/folder copies for move and
 * copy. 64 KB buffer, ".part" + atomic rename, cooperative cancel — a multi-gigabyte file never sits in RAM.
 * All methods block; call them off the UI thread.
 */
public final class SandboxImporter {
    private SandboxImporter() { }

    public interface Progress { void onProgress(String label, long copied, long total); }

    /** Copies one stream into dest (".part" first, rename at the end). */
    public static void copyStream(InputStream in, long total, File dest, Progress p, CancelToken cancel) throws IOException {
        File part = new File(dest.getParentFile(), "." + dest.getName() + ".part");
        long copied = 0;
        try (OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            long last = 0;
            while ((n = in.read(buf)) > 0) {
                if (cancel != null && cancel.isCancelled()) throw new CancelToken.CancelledException("Копирование отменено");
                out.write(buf, 0, n);
                copied += n;
                long now = System.nanoTime();
                if (p != null && now - last > 150_000_000L) { last = now; p.onProgress(dest.getName(), copied, total); }
            }
            out.flush();
        } catch (IOException | RuntimeException e) {
            part.delete();
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
        }
        if (cancel != null && cancel.isCancelled()) { part.delete(); throw new CancelToken.CancelledException("Копирование отменено"); }
        if (!part.renameTo(dest)) { part.delete(); throw new IOException("Не удалось сохранить: " + dest.getName()); }
        if (p != null) p.onProgress(dest.getName(), copied, copied);
    }

    /** Imports one picked document into dir (real name/size from the content provider). Returns the created file. */
    public static File importUri(Context c, Sandbox sb, Uri uri, File dir, Progress p, CancelToken cancel) throws IOException {
        ContentResolver cr = c.getContentResolver();
        String name = null;
        long total = -1;
        try (Cursor q = cr.query(uri, null, null, null, null)) {
            if (q != null && q.moveToFirst()) {
                int ni = q.getColumnIndex(OpenableColumns.DISPLAY_NAME), si = q.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) name = q.getString(ni);
                if (si >= 0 && !q.isNull(si)) total = q.getLong(si);
            }
        } catch (RuntimeException ignored) { }
        if (name == null || name.isEmpty()) name = uri.getLastPathSegment();
        File dest = Attachments.uniqueFile(dir, Attachments.safeName(name));
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("Не удалось открыть выбранный файл");
            copyStream(in, total, dest, p, cancel);
        } catch (IOException | RuntimeException e) {
            dest.delete();
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
        }
        return dest;
    }

    public static void copyFile(File src, File dest, Progress p, CancelToken cancel) throws IOException {
        try (InputStream in = new FileInputStream(src)) { copyStream(in, src.length(), dest, p, cancel); }
    }

    /** Recursive folder copy (used when a move cannot be a rename, and for explicit copies of folders). */
    public static void copyTree(final File src, final File dest, final Progress p, final CancelToken cancel) throws IOException {
        if (cancel != null) cancel.throwIfCancelled();
        if (src.isFile()) { copyFile(src, dest, p, cancel); return; }
        if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("Не удалось создать папку " + dest.getName());
        File[] kids = src.listFiles();
        if (kids == null) return;
        for (File k : kids) copyTree(k, new File(dest, k.getName()), p, cancel);
    }

    public static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /** True if moving {@code src} into {@code destDir} would put a folder inside itself. */
    public static boolean isInside(File dir, File maybeChild) {
        File p = maybeChild.getParentFile();
        while (p != null) {
            if (p.equals(dir)) return true;
            p = p.getParentFile();
        }
        return false;
    }
}
