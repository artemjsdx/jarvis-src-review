package app.jarvis.core.tools;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The agent's private working area. All file tools resolve paths here; "..", absolute escapes and symlinks that leave
 * the root are rejected. (The shell is NOT confined by this class: it runs with the app's own Android permissions.)
 */
public final class Sandbox {
    public final File root, work, inbox, downloads, music, docs, tmp, bin;
    private final List<File> extra = new CopyOnWriteArrayList<>();

    public Sandbox(File rootDir) throws IOException {
        root = rootDir.getCanonicalFile();
        work = new File(root, "work");
        inbox = new File(root, "inbox");
        downloads = new File(root, "downloads");
        music = new File(root, "music");
        docs = new File(root, "docs");
        tmp = new File(root, "tmp");
        bin = new File(root, "bin");
        for (File d : new File[]{root, work, inbox, downloads, music, docs, tmp, bin}) {
            if (!d.isDirectory() && !d.mkdirs()) throw new IOException("Не удалось создать " + d);
        }
    }

    /** Permit a directory outside the root (e.g. shared storage) when the user explicitly enables it. */
    public void allowExternal(File dir) {
        try { extra.add(dir.getCanonicalFile()); } catch (IOException ignored) { }
    }

    public void clearExternal() { extra.clear(); }

    public boolean isCore(File f) {
        return f.equals(root) || f.equals(work) || f.equals(inbox) || f.equals(downloads) || f.equals(music)
            || f.equals(docs) || f.equals(tmp) || f.equals(bin);
    }

    public File resolve(String path) throws IOException {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        if (p.startsWith("sandbox:")) p = p.substring(8);
        File f;
        if (p.isEmpty() || p.equals("/") || p.equals("~")) {
            f = root;
        } else if (p.startsWith("~/")) {
            f = new File(root, p.substring(2));
        } else if (p.startsWith("/")) {
            File abs = new File(p);
            f = isInsideAny(abs) ? abs : new File(root, p.substring(1));   // "/work/x" means root-relative
        } else {
            f = new File(root, p);
        }
        File c = f.getCanonicalFile();
        if (!isInsideAny(c)) throw new IOException("Путь вне песочницы: " + path);
        return c;
    }

    private boolean isInsideAny(File f) throws IOException {
        File c = f.getCanonicalFile();
        if (within(c, root)) return true;
        for (File e : extra) if (within(c, e)) return true;
        return false;
    }

    private static boolean within(File f, File dir) {
        String fp = f.getPath(), dp = dir.getPath();
        return fp.equals(dp) || fp.startsWith(dp.endsWith(File.separator) ? dp : dp + File.separator);
    }

    /** Path as shown to the model: relative to the root when inside it. */
    public String rel(File f) {
        String fp = f.getPath(), rp = root.getPath();
        if (fp.equals(rp)) return "/";
        if (fp.startsWith(rp + File.separator)) return fp.substring(rp.length() + 1);
        return fp;
    }
}
