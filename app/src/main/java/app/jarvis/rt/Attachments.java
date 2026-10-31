package app.jarvis.rt;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import app.jarvis.core.tools.Sandbox;
import app.jarvis.core.util.Mime;
import app.jarvis.data.ChatItem;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Streams a picked/shared document into the sandbox inbox/ (64 KB buffer: a multi-GB file never sits in RAM). */
public final class Attachments {
    private Attachments() { }

    public interface Progress { void onBytes(long copied, long total); }

    public static String safeName(String n) {
        String s = n == null ? "" : n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (s.startsWith(".")) s = "_" + s;
        return s.isEmpty() ? "file" : (s.length() > 120 ? s.substring(s.length() - 120) : s);
    }

    public static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int d = name.lastIndexOf('.');
        String base = d > 0 ? name.substring(0, d) : name, ext = d > 0 ? name.substring(d) : "";
        for (int i = 1; i < 10000; i++) { f = new File(dir, base + " (" + i + ")" + ext); if (!f.exists()) return f; }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    public static ChatItem.Attachment importUri(Context c, Sandbox sb, Uri uri, Progress p) throws IOException {
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
        if (name == null || name.isEmpty()) { name = uri.getLastPathSegment(); }
        name = safeName(name);
        String type = cr.getType(uri);
        if (!name.contains(".") && type != null) {
            if (type.startsWith("audio/")) name += type.contains("mpeg") ? ".mp3" : type.contains("mp4") ? ".m4a" : type.contains("ogg") ? ".ogg" : type.contains("wav") ? ".wav" : ".audio";
        }
        File free = sb.inbox;
        if (total > 0 && free.getUsableSpace() < total + 50L * 1024 * 1024) throw new IOException("Недостаточно места: нужно " + (total >> 20) + " МБ");
        File dest = uniqueFile(sb.inbox, name);
        File part = new File(sb.inbox, "." + dest.getName() + ".part");
        long copied = 0;
        try (InputStream in = cr.openInputStream(uri); OutputStream out = new FileOutputStream(part)) {
            if (in == null) throw new IOException("Не удалось открыть выбранный файл");
            byte[] buf = new byte[64 * 1024];
            int n;
            long last = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                copied += n;
                long now = System.nanoTime();
                if (p != null && now - last > 200_000_000L) { last = now; p.onBytes(copied, total); }
            }
            out.flush();
        } catch (IOException | RuntimeException e) {
            part.delete();
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
        }
        if (total > 0 && copied != total) { part.delete(); throw new IOException("Файл скопирован не полностью: " + copied + " из " + total + " Б"); }
        if (!part.renameTo(dest)) { part.delete(); throw new IOException("Не удалось сохранить файл во входящие"); }
        if (p != null) p.onBytes(copied, copied);
        ChatItem.Attachment a = new ChatItem.Attachment();
        a.path = sb.rel(dest);
        a.name = dest.getName();
        a.mime = type != null ? type : Mime.guess(dest.getName());
        a.size = dest.length();
        if (Mime.isAudio(dest.getName())) a.durationText = MusicLibrary.describe(sb, dest, true).durationText();
        return a;
    }
}
