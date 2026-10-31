package app.jarvis.wake;

import app.jarvis.core.log.Journal;
import app.jarvis.core.net.Downloader;
import app.jarvis.core.net.ZipSafe;
import app.jarvis.core.util.CancelToken;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Properties;

/**
 * Manager of the downloadable offline model pack (vosk-model-small-ru-0.22, Apache-2.0, ~44 MB zip /
 * ~91 MB unpacked) used by {@link LocalWake} and {@link LocalStt}.
 *
 * <p>Lifecycle: {@link #install} downloads the zip through the shared {@link Downloader} (temp file,
 * size + SHA-256 + MD5 verification, resume) and unpacks it with {@link ZipSafe#extract}, which validates the
 * whole archive first and switches the target directory atomically — a failed or interrupted install keeps the
 * previously installed version intact (this closes the "Unexpected end of ZLIB input stream" class of bugs where
 * a truncated download ended up on disk as a usable model). The caller owns the directories and the thread:
 * install blocks, so run it off the main thread. All user-facing messages are Russian.</p>
 */
public final class WakePack {
    public static final String MODEL_NAME = "vosk-model-small-ru-0.22";
    public static final String MODEL_VERSION = "0.22";
    public static final String MODEL_URL = "https://alphacephei.com/vosk/models/" + MODEL_NAME + ".zip";
    /** SHA-256 computed over a download whose MD5 matches the publisher's model-list.json (d1759dc83eb8fd87850129afbd9f4b7b). */
    public static final String MODEL_SHA256 = "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4";
    public static final String MODEL_MD5 = "d1759dc83eb8fd87850129afbd9f4b7b";
    public static final long MODEL_SIZE = 46_236_750L;
    public static final String ZIP_NAME = MODEL_NAME + ".zip";
    private static final String MARKER = "jarvis-pack.properties";

    public enum PackState { NOT_INSTALLED, DOWNLOADING, VERIFYING, INSTALLED, ERROR }

    /** Immutable snapshot; {@code bytesDone/bytesTotal} are meaningful while DOWNLOADING. */
    public static final class PackStatus {
        public final PackState state;
        /** User-safe Russian description of the last state change or failure ("" when nothing to say). */
        public final String message;
        public final long bytesDone, bytesTotal;
        /** Installed model version per the marker file ("" unknown / not installed). */
        public final String modelVersion;

        PackStatus(PackState state, String message, long bytesDone, long bytesTotal, String modelVersion) {
            this.state = state; this.message = message == null ? "" : message;
            this.bytesDone = bytesDone; this.bytesTotal = bytesTotal; this.modelVersion = modelVersion == null ? "" : modelVersion;
        }
    }

    public interface Progress { void onStatus(PackStatus status); }

    private final File targetDir;
    private final File workDir;
    private final Journal journal;
    // overridable for tests: the production pack is the pinned Russian small model
    private final String url, sha256, md5, version;
    private final long zipSize;
    private volatile PackStatus status;

    public WakePack(File targetDir, File workDir, Journal journal) {
        this(targetDir, workDir, journal, MODEL_URL, MODEL_SHA256, MODEL_MD5, MODEL_SIZE, MODEL_VERSION);
    }

    WakePack(File targetDir, File workDir, Journal journal, String url, String sha256, String md5, long zipSize, String version) {
        this.targetDir = targetDir;
        this.workDir = workDir;
        this.journal = journal == null ? Journal.NOP : journal;
        this.url = url; this.sha256 = sha256; this.md5 = md5; this.zipSize = zipSize; this.version = version;
        this.status = new PackStatus(looksInstalled(targetDir) ? PackState.INSTALLED : PackState.NOT_INSTALLED, "",
            0, -1, looksInstalled(targetDir) ? installedVersion() : "");
    }

    public synchronized PackStatus status() { return status; }

    public boolean isInstalled() { return looksInstalled(targetDir); }

    /** Directory to pass to Vosk {@code Model}, or null when not installed. */
    public File installedPath() { return looksInstalled(targetDir) ? targetDir : null; }

    /** Total size of the unpacked model on disk, -1 when not installed. */
    public long sizeBytes() { return isInstalled() ? treeSize(targetDir) : -1; }

    /**
     * Downloads (unless a fully verified zip is already cached in workDir) and installs the pack. Idempotent:
     * returns immediately when this exact version is already installed. Blocking; call off the main thread.
     *
     * @throws IOException network failure, checksum mismatch, corrupt archive; the ERROR state with a
     *         user-safe Russian message is set before throwing
     */
    public void install(Progress progress, CancelToken cancel) throws IOException {
        if (version.equals(installedVersion()) && looksInstalled(targetDir)) {
            update(progress, new PackStatus(PackState.INSTALLED, "", 0, -1, version));
            return;
        }
        File zip = new File(workDir, ZIP_NAME);
        try {
            if (!useCachedZip(zip)) {
                update(progress, new PackStatus(PackState.DOWNLOADING, "Загрузка модели (" + MODEL_SIZE / (1024 * 1024) + " МБ)", 0, zipSize, version));
                Downloader.Request rq = new Downloader.Request();
                rq.url = url;
                rq.dest = zip;
                rq.sha256 = sha256;
                rq.md5 = md5;
                rq.expectedSize = zipSize;
                rq.maxBytes = zipSize + (1L << 20);
                new Downloader(journal).download(rq, (done, total) ->
                    update(progress, new PackStatus(PackState.DOWNLOADING, "Загрузка модели", done, total, version)), cancel);
            }
            cancel.throwIfCancelled();
            update(progress, new PackStatus(PackState.VERIFYING, "Проверка и распаковка модели", 0, -1, version));
            // ZipSafe.extract validates every entry (CRC), unpacks into a temp sibling and swaps atomically:
            // on any failure the previously installed version stays in place untouched.
            ZipSafe.Limits lim = new ZipSafe.Limits();
            lim.maxTotal = Math.max(lim.maxTotal, zipSize * 4);
            ZipSafe.extract(zip, targetDir, true, lim);
            if (!looksInstalled(targetDir)) throw new IOException("В архиве нет ожидаемых файлов модели");
            writeMarker();
            zip.delete();
            update(progress, new PackStatus(PackState.INSTALLED, "", 0, -1, version));
            journal.log(Journal.Cat.DOWNLOADS, Journal.Level.INFO, "Модель офлайн-распознавания установлена (" + version + ")", null, null, -1);
        } catch (CancelToken.CancelledException e) {
            update(progress, new PackStatus(PackState.NOT_INSTALLED, "Установка отменена", 0, -1, installedVersion()));
        } catch (IOException e) {
            update(progress, new PackStatus(PackState.ERROR, "Установка модели не удалась: " + e.getMessage(), 0, -1, installedVersion()));
            journal.e(Journal.Cat.DOWNLOADS, "Установка модели не удалась: " + e.getMessage(), e);
            throw e;
        }
    }

    /** Removes the installed model and the cached zip. */
    public synchronized void uninstall() {
        ZipSafe.deleteTree(targetDir);
        new File(workDir, ZIP_NAME).delete();
        status = new PackStatus(PackState.NOT_INSTALLED, "", 0, -1, "");
        journal.log(Journal.Cat.DOWNLOADS, Journal.Level.INFO, "Модель офлайн-распознавания удалена", null, null, -1);
    }

    /** True when the directory holds a structurally complete model (the files Vosk actually opens). */
    static boolean looksInstalled(File dir) {
        return dir != null && new File(dir, "am/final.mdl").isFile()
            && new File(dir, "conf/model.conf").isFile()
            && new File(dir, "graph/HCLr.fst").isFile();
    }

    private boolean useCachedZip(File zip) throws IOException {
        if (!zip.isFile() || zip.length() != zipSize) return false;
        update(null, new PackStatus(PackState.VERIFYING, "Найден уже скачанный архив, проверяю", 0, zipSize, version));
        if (Downloader.hash(zip, "SHA-256").equalsIgnoreCase(sha256)) return true;
        zip.delete();
        return false;
    }

    private String installedVersion() {
        try {
            Properties p = new Properties();
            try (FileInputStream in = new FileInputStream(new File(targetDir, MARKER))) { p.load(in); }
            return p.getProperty("version", "");
        } catch (IOException e) {
            return "";
        }
    }

    private void writeMarker() throws IOException {
        Properties p = new Properties();
        p.setProperty("version", version);
        p.setProperty("sha256", sha256);
        File f = new File(targetDir, MARKER);
        try (OutputStream out = new FileOutputStream(f)) { p.store(out, "installed by app.jarvis.wake"); }
    }

    private void update(Progress p, PackStatus s) {
        status = s;
        if (p != null) { try { p.onStatus(s); } catch (RuntimeException ignored) { } }
    }

    private static long treeSize(File f) {
        if (f.isFile()) return f.length();
        File[] c = f.listFiles();
        if (c == null) return 0;
        long n = 0;
        for (File x : c) n += treeSize(x);
        return n;
    }
}
