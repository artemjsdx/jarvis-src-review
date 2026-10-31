package app.jarvis.core.net;

import app.jarvis.core.util.J;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Network measurements for the agent (MCP net_probe): per host DNS, TCP connect, TLS handshake, time to first response byte and
 * total time of a small GET; and upload throughput to a POST endpoint. Mirrors tools/bench/net_probe.py and upload_probe.py.
 * Traffic goes through {@link NetThrottle}, so a throttle set with net_throttle shows up in the numbers (reported as throttled=true).
 */
public final class NetLab {
    private NetLab() { }

    public static final int MAX_HOSTS = 16;
    private static final int MAX_BODY_READ = 64 * 1024;

    /** Parsed target: "host", "host:port", "http://host[:port][/path]" or "https://...". */
    static final class Target {
        final String scheme, host, path;
        final int port;

        Target(String scheme, String host, int port, String path) {
            this.scheme = scheme; this.host = host; this.port = port; this.path = path;
        }

        boolean tls() { return "https".equals(scheme); }

        static Target parse(String spec) {
            String s = spec == null ? "" : spec.trim();
            if (s.isEmpty()) throw new IllegalArgumentException("пустой хост");
            String scheme = "https";
            int i = s.indexOf("://");
            if (i >= 0) { scheme = s.substring(0, i).toLowerCase(java.util.Locale.ROOT); s = s.substring(i + 3); }
            if (!"http".equals(scheme) && !"https".equals(scheme)) throw new IllegalArgumentException("схема должна быть http или https: " + scheme);
            String path = "/";
            int slash = s.indexOf('/');
            if (slash >= 0) { path = s.substring(slash); s = s.substring(0, slash); }
            int port = "https".equals(scheme) ? 443 : 80;
            int colon = s.lastIndexOf(':');
            if (colon > 0 && s.indexOf(':') == colon) {
                try { port = Integer.parseInt(s.substring(colon + 1)); } catch (NumberFormatException e) { throw new IllegalArgumentException("порт: " + s); }
                s = s.substring(0, colon);
            }
            if (s.isEmpty() || port <= 0 || port > 65535) throw new IllegalArgumentException("хост или порт некорректны");
            return new Target(scheme, s, port, path);
        }
    }

    private static double ms(long nanos) { return Math.round(nanos / 1e4) / 100.0; }

    /** Probes one host. Never throws: failures are reported in the result with the stage that failed. */
    public static JSONObject probeHost(String spec, int timeoutMs) {
        JSONObject o = new JSONObject();
        String stage = "parse";
        Target t = null;
        try {
            t = Target.parse(spec);
            J.put(o, "host", t.host);
            J.put(o, "port", t.port);
            J.put(o, "scheme", t.scheme);
            J.put(o, "throttled", NetThrottle.active() != null);
            stage = "dns";
            long t0 = System.nanoTime();
            InetAddress addr = InetAddress.getByName(t.host);
            J.put(o, "dnsMs", ms(System.nanoTime() - t0));
            J.put(o, "ip", addr.getHostAddress());
            stage = "tcp";
            Socket raw = new Socket();
            try {
                t0 = System.nanoTime();
                raw.connect(new InetSocketAddress(addr, t.port), timeoutMs);
                raw.setSoTimeout(timeoutMs);
                J.put(o, "tcpMs", ms(System.nanoTime() - t0));
                Socket s = raw;
                if (t.tls()) {
                    stage = "tls";
                    t0 = System.nanoTime();
                    SSLSocket ss = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, t.host, t.port, true);
                    ss.setSoTimeout(timeoutMs);
                    ss.startHandshake();
                    J.put(o, "tlsMs", ms(System.nanoTime() - t0));
                    s = ss;
                }
                stage = "request";
                exchange(s, t, o, timeoutMs);
                s.close();
            } finally {
                raw.close();
            }
            J.put(o, "ok", true);
        } catch (Exception e) {
            J.put(o, "ok", false);
            J.put(o, "stage", stage);
            J.put(o, "error", userMessage(stage, e));
            J.put(o, "technical", String.valueOf(e));
        }
        return o;
    }

    private static void exchange(Socket s, Target t, JSONObject o, int timeoutMs) throws IOException {
        OutputStream out = NetThrottle.out(s.getOutputStream());
        boolean defaultPort = t.tls() ? t.port == 443 : t.port == 80;
        String hostHeader = defaultPort ? t.host : t.host + ":" + t.port;
        String req = "GET " + t.path + " HTTP/1.1\r\nHost: " + hostHeader + "\r\nUser-Agent: " + Http.UA + "\r\nAccept: */*\r\nConnection: close\r\n\r\n";
        long t0 = System.nanoTime();
        out.write(req.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        InputStream in = NetThrottle.in(s.getInputStream());
        byte[] buf = new byte[4096];
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int n = in.read(buf);
        if (n < 0) throw new IOException("соединение закрыто без ответа");
        J.put(o, "firstByteMs", ms(System.nanoTime() - t0));
        head.write(buf, 0, n);
        long total = n;
        while (total < MAX_BODY_READ) {
            n = in.read(buf);
            if (n < 0) break;
            total += n;
        }
        String text = new String(head.toByteArray(), StandardCharsets.ISO_8859_1);
        int eol = text.indexOf("\r\n");
        String status = eol > 0 ? text.substring(0, eol) : text.substring(0, Math.min(40, text.length()));
        J.put(o, "status", status);
        J.put(o, "bytesRead", total);
        J.put(o, "totalMs", ms(System.nanoTime() - t0));
    }

    /** Probes several hosts in order. */
    public static JSONObject probe(JSONArray hosts, int timeoutMs) {
        JSONArray arr = new JSONArray();
        int ok = 0;
        for (int i = 0; i < hosts.length() && i < MAX_HOSTS; i++) {
            JSONObject r = probeHost(hosts.optString(i, ""), timeoutMs);
            if (r.optBoolean("ok")) ok++;
            arr.put(r);
        }
        JSONObject o = new JSONObject();
        J.put(o, "hosts", arr);
        J.put(o, "okCount", ok);
        J.put(o, "count", arr.length());
        J.put(o, "throttle", NetThrottle.state());
        return o;
    }

    /** POSTs {@code bytes} of data to url (http or https) and reports how long the upload and the response took. */
    public static JSONObject upload(String url, int bytes, int timeoutMs) {
        JSONObject o = new JSONObject();
        J.put(o, "url", url);
        J.put(o, "bytes", bytes);
        J.put(o, "throttled", NetThrottle.active() != null);
        String stage = "connect";
        HttpURLConnection c = null;
        try {
            c = Http.open(url, "POST", null, timeoutMs, timeoutMs);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(bytes);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            OutputStream raw = c.getOutputStream();
            stage = "upload";
            OutputStream out = NetThrottle.out(raw);
            byte[] chunk = new byte[8192];
            for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) (i * 31 + 7);
            long t0 = System.nanoTime();
            int left = bytes;
            while (left > 0) {
                int n = Math.min(left, chunk.length);
                out.write(chunk, 0, n);
                left -= n;
            }
            out.close();
            long sent = System.nanoTime() - t0;
            stage = "response";
            int code = c.getResponseCode();
            long all = System.nanoTime() - t0;
            J.put(o, "status", code);
            J.put(o, "sendMs", ms(sent));
            J.put(o, "responseMs", ms(all));
            double secs = Math.max(1e-6, all / 1e9);
            J.put(o, "kbytesPerSec", Math.round(bytes / 1024.0 / secs * 10) / 10.0);
            J.put(o, "ok", true);
        } catch (Exception e) {
            J.put(o, "ok", false);
            J.put(o, "stage", stage);
            J.put(o, "error", userMessage(stage, e));
            J.put(o, "technical", String.valueOf(e));
        } finally {
            if (c != null) c.disconnect();
        }
        return o;
    }

    static String userMessage(String stage, Exception e) {
        String what;
        switch (stage) {
            case "parse": what = "Некорректный адрес"; break;
            case "dns": what = "Имя хоста не найдено"; break;
            case "tcp": what = "Не удалось установить соединение"; break;
            case "tls": what = "Ошибка TLS-рукопожатия"; break;
            case "request": what = "Запрос не получил ответа"; break;
            case "upload": what = "Отправка данных прервана"; break;
            case "response": what = "Ответ сервера не получен"; break;
            default: what = "Ошибка сети";
        }
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return what + ": " + detail;
    }
}
