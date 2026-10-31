package app.jarvis.core.tools;

import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ToolSpec;
import app.jarvis.core.llm.ToolSpec.Param;
import app.jarvis.core.net.Http;
import app.jarvis.core.util.J;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import org.json.JSONObject;

public final class HttpFetchTool {
    private HttpFetchTool() { }

    /** Explicit proxy for tool traffic ("" = system routing); set from settings. */
    public static volatile String proxy = "";

    public static void register(ToolRegistry r) {
        r.add(ToolSpec.of("http_fetch", "HTTP-запрос: статус и текст ответа (до 64 КБ). ok=true при статусе < 400.",
                Param.str("url", "http:// или https:// адрес.", true),
                Param.str("method", "GET (по умолчанию), POST, PUT, DELETE, HEAD.", false),
                Param.str("headers_json", "JSON-объект с заголовками, например {\"Accept\":\"application/json\"}.", false),
                Param.str("body", "Тело (POST/PUT).", false),
                Param.integer("max_bytes", "Сколько байт ответа вернуть (до 65536).", false)),
            "tools", 60_000, true, (a, ctx) -> {
                String url = J.str(a, "url").trim();
                String low = url.toLowerCase(Locale.ROOT);
                if (!(low.startsWith("http://") || low.startsWith("https://"))) return ToolResult.fail("Допустимы только http:// и https:// адреса");
                String method = J.str(a, "method", "GET").trim().toUpperCase(Locale.ROOT);
                int max = Math.max(256, Math.min(65536, J.num(a, "max_bytes", 32768)));
                long t0 = System.nanoTime();
                HttpURLConnection c = Http.open(url, method, Http.parseProxy(proxy), 15_000, 30_000);
                Runnable unhook = ctx.cancel.onCancel(() -> c.disconnect());
                try {
                    c.setRequestProperty("Accept-Encoding", "identity");
                    JSONObject h = J.parseOrNull(J.str(a, "headers_json"));
                    if (h != null) {
                        Iterator<String> it = h.keys();
                        while (it.hasNext()) { String k = it.next(); c.setRequestProperty(k, J.str(h, k)); }
                    }
                    String body = J.str(a, "body");
                    if (!body.isEmpty() && !method.equals("GET") && !method.equals("HEAD")) {
                        byte[] b = body.getBytes(StandardCharsets.UTF_8);
                        c.setDoOutput(true);
                        c.setFixedLengthStreamingMode(b.length);
                        if (c.getRequestProperty("Content-Type") == null) c.setRequestProperty("Content-Type", "application/json");
                        try (OutputStream os = c.getOutputStream()) { os.write(b); }
                    }
                    int st = c.getResponseCode();
                    InputStream in = st >= 400 ? c.getErrorStream() : c.getInputStream();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    boolean truncated = false;
                    if (in != null) {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            if (bo.size() + n > max) { bo.write(buf, 0, max - bo.size()); truncated = true; break; }
                            bo.write(buf, 0, n);
                        }
                    }
                    byte[] data = bo.toByteArray();
                    JSONObject o = new JSONObject();
                    J.put(o, "status", st);
                    J.put(o, "content_type", c.getContentType() == null ? "" : c.getContentType());
                    J.put(o, "final_url", c.getURL().toString());
                    J.put(o, "duration_ms", (System.nanoTime() - t0) / 1_000_000L);
                    if (FsTools.looksBinary(data)) { J.put(o, "binary", true); J.put(o, "bytes_read", data.length); }
                    else {
                        int len = truncated ? FsTools.utf8SafeLength(data, data.length) : data.length;
                        J.put(o, "body", new String(data, 0, len, StandardCharsets.UTF_8));
                    }
                    J.put(o, "truncated", truncated);
                    return st < 400 ? ToolResult.ok(o, method + " " + st) : ToolResult.fail("HTTP " + st, o);
                } finally {
                    unhook.run();
                    c.disconnect();
                }
            });
    }
}
