package app.jarvis.core.net;

import app.jarvis.core.log.Journal;
import app.jarvis.core.util.CancelToken;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Reliable download: temp file + HTTP status check + length check + optional checksum + resume + retries, then an
 * atomic rename. A truncated or corrupted transfer can never be mistaken for a finished file.
 */
public final class Downloader {
    public static final class Request {
        public String url;
        public File dest;
        public String sha256 = "", md5 = "";
        public long expectedSize = -1;
        public long maxBytes = 4L << 30;
        public final Map<String, String> headers = new LinkedHashMap<>();
        public String proxy = "";
        public int maxAttempts = 3;
        public boolean resume = true;
    }

    public static final class Result {
        public File file;
        public long bytes;
        public String sha256 = "";
        public long durationMs;
        public int attempts;
        public boolean resumed;
        public int httpStatus;
    }

    public interface Progress { void onProgress(long done, long total); }

    public static final class DownloadException extends IOException {
        public final boolean retryable;
        public DownloadException(String m, boolean retryable, Throwable cause) { super(m, cause); this.retryable = retryable; }
    }

    private final Journal journal;

    public Downloader(Journal j) { this.journal = j == null ? Journal.NOP : j; }

    public Result download(Request rq, Progress progress, CancelToken cancel) throws IOException {
        long t0 = System.nanoTime();
        File dir = rq.dest.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Не удалось создать каталог " + dir);
        File part = new File(dir, "." + rq.dest.getName() + ".part");
        if (!rq.resume) part.delete();
        Result res = new Result();
        IOException last = null;
        for (int attempt = 1; attempt <= Math.max(1, rq.maxAttempts); attempt++) {
            cancel.throwIfCancelled();
            res.attempts = attempt;
            try {
                oneAttempt(rq, part, res, progress, cancel);
                verify(rq, part, res);
                moveAtomic(part, rq.dest);
                res.file = rq.dest;
                res.bytes = rq.dest.length();
                res.durationMs = (System.nanoTime() - t0) / 1_000_000L;
                journal.log(Journal.Cat.DOWNLOADS, Journal.Level.INFO, "Загружено " + rq.dest.getName() + " (" + res.bytes + " Б)",
                    null, app.jarvis.core.util.J.put(app.jarvis.core.util.J.put(new org.json.JSONObject(), "attempts", attempt), "resumed", res.resumed), res.durationMs);
                return res;
            } catch (DownloadException e) {
                last = e;
                journal.w(Journal.Cat.DOWNLOADS, "Попытка " + attempt + "/" + rq.maxAttempts + " не удалась: " + e.getMessage(), e, null);
                if (!e.retryable) break;
            } catch (java.net.SocketTimeoutException | java.net.SocketException | java.io.EOFException e) {
                last = e;
                journal.w(Journal.Cat.DOWNLOADS, "Сетевая ошибка (попытка " + attempt + "): " + e.getMessage(), e, null);
            } catch (IOException e) {
                if (cancel.isCancelled()) throw e;
                last = e;
                journal.w(Journal.Cat.DOWNLOADS, "Ошибка загрузки (попытка " + attempt + "): " + e.getMessage(), e, null);
            }
            if (attempt < rq.maxAttempts && !cancel.sleep(1000L << (attempt - 1))) break;
        }
        cancel.throwIfCancelled();
        throw last != null ? last : new IOException("Загрузка не удалась");
    }

    private void oneAttempt(Request rq, File part, Result res, Progress progress, CancelToken cancel) throws IOException {
        long have = rq.resume && part.isFile() ? part.length() : 0;
        HttpURLConnection c = Http.open(rq.url, "GET", Http.parseProxy(rq.proxy), 15_000, 30_000);
        Runnable unhook = cancel.onCancel(() -> c.disconnect());
        try {
            c.setRequestProperty("Accept-Encoding", "identity");
            for (Map.Entry<String, String> h : rq.headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
            int st = c.getResponseCode();
            res.httpStatus = st;
            if (st == 416) { part.delete(); throw new DownloadException("Сервер отклонил докачку, начинаю заново", true, null); }
            if (st != 200 && st != 206)
                throw new DownloadException("HTTP " + st + " " + (c.getResponseMessage() == null ? "" : c.getResponseMessage()),
                    st == 408 || st == 429 || st >= 500, null);
            long total;
            boolean append;
            if (st == 206) {
                String cr = c.getHeaderField("Content-Range"); // bytes start-end/total
                long start = -1;
                total = -1;
                try {
                    String r = cr.substring(cr.indexOf(' ') + 1);
                    start = Long.parseLong(r.substring(0, r.indexOf('-')));
                    String t = r.substring(r.indexOf('/') + 1);
                    total = t.equals("*") ? -1 : Long.parseLong(t);
                } catch (Exception ignored) { }
                if (start != have) { part.delete(); throw new DownloadException("Некорректный Content-Range, начинаю заново", true, null); }
                append = true;
                res.resumed = true;
            } else {
                total = c.getContentLengthLong();
                append = false;
                have = 0;
            }
            if (rq.expectedSize >= 0 && total >= 0 && total != rq.expectedSize)
                throw new DownloadException("Размер на сервере (" + total + ") не совпадает с ожидаемым (" + rq.expectedSize + ")", false, null);
            if (total > rq.maxBytes) throw new DownloadException("Файл больше допустимого (" + total + " Б)", false, null);
            InputStream in = c.getInputStream();
            String enc = c.getContentEncoding();
            if (enc != null && enc.toLowerCase(Locale.ROOT).contains("gzip") && !rq.url.toLowerCase(Locale.ROOT).endsWith(".gz")) {
                in = new GZIPInputStream(in); // server compressed despite identity; length checks no longer apply
                total = -1;
                append = false;
                have = 0;
            }
            long done = have;
            long lastTick = 0;
            try (OutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    cancel.throwIfCancelled();
                    out.write(buf, 0, n);
                    done += n;
                    if (done > rq.maxBytes) throw new DownloadException("Превышен лимит размера " + rq.maxBytes, false, null);
                    long now = System.nanoTime();
                    if (progress != null && now - lastTick > 250_000_000L) { lastTick = now; progress.onProgress(done, total); }
                }
                out.flush();
            }
            if (progress != null) progress.onProgress(done, total);
            if (total >= 0 && part.length() != total)
                throw new DownloadException("Загрузка оборвана: получено " + part.length() + " из " + total + " Б", true, null);
        } finally {
            unhook.run();
            c.disconnect();
        }
    }

    private void verify(Request rq, File part, Result res) throws IOException {
        if (rq.expectedSize >= 0 && part.length() != rq.expectedSize) {
            part.delete();
            throw new DownloadException("Размер файла " + part.length() + " ≠ ожидаемому " + rq.expectedSize, true, null);
        }
        res.sha256 = hash(part, "SHA-256");
        if (!rq.sha256.isEmpty() && !rq.sha256.equalsIgnoreCase(res.sha256)) {
            part.delete();
            throw new DownloadException("Контрольная сумма SHA-256 не совпала (получено " + res.sha256.substring(0, 12) + "…): файл повреждён", true, null);
        }
        if (!rq.md5.isEmpty() && !rq.md5.equalsIgnoreCase(hash(part, "MD5"))) {
            part.delete();
            throw new DownloadException("Контрольная сумма MD5 не совпала: файл повреждён", true, null);
        }
    }

    public static String hash(File f, String algo) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    static void moveAtomic(File from, File to) throws IOException {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
