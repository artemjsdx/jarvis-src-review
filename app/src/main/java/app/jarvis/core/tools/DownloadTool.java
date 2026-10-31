package app.jarvis.core.tools;

import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ToolSpec;
import app.jarvis.core.llm.ToolSpec.Param;
import app.jarvis.core.log.Journal;
import app.jarvis.core.net.Downloader;
import app.jarvis.core.util.J;
import java.io.File;
import java.net.URLDecoder;
import java.util.Locale;
import org.json.JSONObject;

public final class DownloadTool {
    private DownloadTool() { }

    public static String fileNameFromUrl(String url) {
        String p = url;
        int q = p.indexOf('?'); if (q >= 0) p = p.substring(0, q);
        int h = p.indexOf('#'); if (h >= 0) p = p.substring(0, h);
        int s = p.lastIndexOf('/');
        String n = s >= 0 ? p.substring(s + 1) : p;
        try { n = URLDecoder.decode(n, "UTF-8"); } catch (Exception ignored) { }
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return n.isEmpty() ? "download.bin" : (n.length() > 120 ? n.substring(n.length() - 120) : n);
    }

    public static void register(ToolRegistry r, final Sandbox sb, final Journal journal, final String[] proxyRef) {
        r.add(ToolSpec.of("download",
                "Скачать файл по ссылке в песочницу (проверка длины и SHA-256, докачка). Возвращает путь и размер.",
                Param.str("url", "http:// или https:// ссылка.", true),
                Param.str("path", "Куда (файл или каталог); по умолчанию downloads/.", false),
                Param.str("sha256", "Ожидаемый SHA-256 (необязательно).", false)),
            "downloads", 31 * 60_000L, true, (a, ctx) -> {
                String url = J.str(a, "url").trim();
                String low = url.toLowerCase(Locale.ROOT);
                if (!(low.startsWith("http://") || low.startsWith("https://"))) return ToolResult.fail("Допустимы только http:// и https:// ссылки");
                File dest;
                String p = J.str(a, "path").trim();
                if (p.isEmpty()) dest = new File(sb.downloads, fileNameFromUrl(url));
                else {
                    File f = sb.resolve(p);
                    dest = f.isDirectory() || p.endsWith("/") ? new File(f, fileNameFromUrl(url)) : f;
                }
                if (sb.isCore(dest)) return ToolResult.fail("Укажите имя файла, а не основной каталог");
                Downloader.Request rq = new Downloader.Request();
                rq.url = url;
                rq.dest = dest;
                rq.sha256 = J.str(a, "sha256").trim();
                rq.proxy = proxyRef != null && proxyRef.length > 0 && proxyRef[0] != null ? proxyRef[0] : "";
                final long[] last = {0};
                Downloader.Result res = new Downloader(journal).download(rq, (done, total) -> {
                    long now = System.currentTimeMillis();
                    if (now - last[0] > 700) {
                        last[0] = now;
                        ctx.progress.progress(String.format(Locale.US, "%.1f / %s МБ", done / 1048576.0, total > 0 ? String.format(Locale.US, "%.1f", total / 1048576.0) : "?"));
                    }
                }, ctx.cancel);
                JSONObject o = new JSONObject();
                J.put(o, "path", sb.rel(res.file));
                J.put(o, "bytes", res.bytes);
                J.put(o, "sha256", res.sha256);
                J.put(o, "duration_ms", res.durationMs);
                J.put(o, "attempts", res.attempts);
                if (res.resumed) J.put(o, "resumed", true);
                return ToolResult.ok(o, sb.rel(res.file) + " (" + res.bytes + " Б)");
            });
    }
}
