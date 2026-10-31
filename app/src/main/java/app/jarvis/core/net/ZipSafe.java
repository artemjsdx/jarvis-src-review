package app.jarvis.core.net;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Integrity check and hostile-archive-safe extraction with atomic switch-over (the previous version survives failure). */
public final class ZipSafe {
    private ZipSafe() { }

    public static final class Limits {
        public long maxTotal = 2L << 30, maxEntry = 1L << 30;
        public int maxEntries = 50_000;
        public int maxRatio = 400;
    }

    /** Reads every byte of every entry: surfaces "Unexpected end of ZLIB input stream" and CRC errors before we trust the file. */
    public static void verify(File zip) throws IOException {
        try (ZipFile z = new ZipFile(zip)) {
            byte[] buf = new byte[64 * 1024];
            Enumeration<? extends ZipEntry> en = z.entries();
            int n = 0;
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                n++;
                if (e.isDirectory()) continue;
                // ZipFile does NOT verify CRC32 itself, so we do: damaged data that still inflates must not pass.
                long read = 0;
                try (CheckedInputStream in = new CheckedInputStream(z.getInputStream(e), new CRC32())) {
                    int r;
                    while ((r = in.read(buf)) > 0) read += r;
                    if (e.getCrc() != -1 && in.getChecksum().getValue() != e.getCrc())
                        throw new ZipException("CRC32 не совпал для «" + e.getName() + "»");
                }
                if (e.getSize() >= 0 && read != e.getSize())
                    throw new ZipException("размер «" + e.getName() + "»: прочитано " + read + ", ожидалось " + e.getSize());
            }
            if (n == 0) throw new ZipException("Архив пуст");
        } catch (ZipException e) {
            throw new IOException("Архив повреждён: " + e.getMessage(), e);
        } catch (java.io.EOFException e) {
            throw new IOException("Архив повреждён (обрыв данных): " + e.getMessage(), e);
        }
    }

    public static String safeName(String name) throws IOException {
        String n = name.replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        for (String seg : n.split("/")) {
            if (seg.equals("..")) throw new IOException("Небезопасный путь в архиве: " + name);
        }
        if (n.indexOf('\0') >= 0) throw new IOException("Небезопасный путь в архиве");
        return n;
    }

    /** Extracts into a temp sibling of destDir, validates, then swaps in atomically. */
    public static void extract(File zip, File destDir, boolean stripSingleRoot, Limits lim) throws IOException {
        if (lim == null) lim = new Limits();
        verify(zip);
        File parent = destDir.getAbsoluteFile().getParentFile();
        parent.mkdirs();
        File tmp = new File(parent, "." + destDir.getName() + ".tmp-" + Long.toString(System.nanoTime(), 36));
        if (!tmp.mkdirs()) throw new IOException("Не удалось создать временный каталог");
        File old = null;
        try {
            String root = stripSingleRoot ? singleRoot(zip) : null;
            String tmpCanon = tmp.getCanonicalPath();
            long total = 0;
            int count = 0;
            try (ZipFile z = new ZipFile(zip)) {
                Enumeration<? extends ZipEntry> en = z.entries();
                byte[] buf = new byte[64 * 1024];
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (++count > lim.maxEntries) throw new IOException("Слишком много файлов в архиве");
                    String name = safeName(e.getName());
                    if (root != null) {
                        if (name.equals(root) || name.equals(root + "/")) continue;
                        if (name.startsWith(root + "/")) name = name.substring(root.length() + 1);
                    }
                    if (name.isEmpty()) continue;
                    File target = new File(tmp, name);
                    String tc = target.getCanonicalPath();
                    if (!tc.equals(tmpCanon) && !tc.startsWith(tmpCanon + File.separator)) throw new IOException("Небезопасный путь в архиве: " + e.getName());
                    if (e.isDirectory()) { target.mkdirs(); continue; }
                    if (e.getSize() > lim.maxEntry) throw new IOException("Файл в архиве слишком большой: " + e.getName());
                    if (e.getCompressedSize() > 0 && e.getSize() > (100L << 20) && e.getSize() / e.getCompressedSize() > lim.maxRatio)
                        throw new IOException("Подозрительная степень сжатия (архивная бомба): " + e.getName());
                    File pp = target.getParentFile();
                    if (pp != null) pp.mkdirs();
                    long written = 0;
                    CRC32 crc = new CRC32();
                    try (InputStream in = z.getInputStream(e); OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            written += n;
                            total += n;
                            if (written > lim.maxEntry || total > lim.maxTotal) throw new IOException("Превышен лимит распакованного размера");
                            crc.update(buf, 0, n);
                            out.write(buf, 0, n);
                        }
                    }
                    if (e.getCrc() != -1 && crc.getValue() != e.getCrc()) throw new IOException("Архив повреждён: CRC32 не совпал для «" + e.getName() + "»");
                }
            }
            if (destDir.exists()) {
                old = new File(parent, "." + destDir.getName() + ".old-" + Long.toString(System.nanoTime(), 36));
                Files.move(destDir.toPath(), old.toPath());
            }
            try {
                Files.move(tmp.toPath(), destDir.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                if (old != null) { try { Files.move(old.toPath(), destDir.toPath()); old = null; } catch (IOException ignored) { } }
                throw ex;
            }
            if (old != null) deleteTree(old);
        } catch (IOException | RuntimeException e) {
            deleteTree(tmp);
            throw e;
        }
    }

    /** Name of the single top-level directory containing every entry, or null. */
    static String singleRoot(File zip) throws IOException {
        String root = null;
        try (ZipFile z = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String n = safeName(en.nextElement().getName());
                int i = n.indexOf('/');
                if (i < 0) return null; // a file at the top level
                String top = n.substring(0, i);
                if (root == null) root = top;
                else if (!root.equals(top)) return null;
            }
        }
        return root;
    }

    public static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) deleteTree(x);
        }
        f.delete();
    }

    public static List<String> list(File zip) throws IOException {
        List<String> l = new ArrayList<>();
        try (ZipFile z = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) l.add(en.nextElement().getName());
        }
        return l;
    }
}
