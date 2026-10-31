package app.jarvis.core.web;

import app.jarvis.core.net.Http;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.J;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.json.JSONObject;

/** One HTTP exchange with a byte cap, optional gzip decoding and cancellation. Status codes are returned, not thrown. */
public final class WebHttp {
    private WebHttp() { }

    public static final class Reply {
        public final int status;
        public final String contentType;
        public final String finalUrl;
        public final byte[] body;
        /** True when the body had more bytes than the cap; the rest was not read. */
        public final boolean truncated;
        public final String location;

        Reply(int status, String contentType, String finalUrl, byte[] body, boolean truncated, String location) {
            this.status = status;
            this.contentType = contentType;
            this.finalUrl = finalUrl;
            this.body = body;
            this.truncated = truncated;
            this.location = location;
        }
    }

    public static Reply send(String service, String method, String url, Map<String, String> headers, byte[] body, Proxy proxy,
                             int connectMs, int readMs, boolean followRedirects, long maxBytes, CancelToken cancel) throws WebException {
        HttpURLConnection c;
        try {
            c = Http.open(url, method, proxy, connectMs, readMs);
        } catch (IOException e) {
            throw new WebException("«" + service + "»: не удалось открыть адрес", service + " open: " + e, 0, false);
        }
        c.setInstanceFollowRedirects(followRedirects);
        Runnable unhook = cancel.onCancel(c::disconnect);
        try {
            if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = c.getOutputStream()) { os.write(body); }
            }
            int status = c.getResponseCode();
            InputStream raw = status >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] data = new byte[0];
            boolean truncated = false;
            if (raw != null) {
                InputStream in = "gzip".equalsIgnoreCase(c.getContentEncoding()) ? new GZIPInputStream(raw) : raw;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (bo.size() + n > maxBytes) {
                        bo.write(buf, 0, (int) (maxBytes - bo.size()));
                        truncated = true;
                        break;
                    }
                    bo.write(buf, 0, n);
                }
                data = bo.toByteArray();
                in.close();
            }
            return new Reply(status, c.getContentType(), c.getURL().toString(), data, truncated, c.getHeaderField("Location"));
        } catch (SocketTimeoutException e) {
            cancel.throwIfCancelled();
            throw WebException.timeout(service);
        } catch (IOException e) {
            cancel.throwIfCancelled();
            throw new WebException("«" + service + "»: нет соединения", service + " io: " + e, 0, false);
        } finally {
            unhook.run();
            c.disconnect();
        }
    }

    public static JSONObject json(String service, String body) throws WebException {
        JSONObject o = J.parseOrNull(body);
        if (o == null) throw WebException.parse(service, "не JSON");
        return o;
    }

    public static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String text(byte[] body) {
        return new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }
}
