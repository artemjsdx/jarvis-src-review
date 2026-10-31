package app.jarvis.core.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Small HttpURLConnection helpers shared by the LLM client, downloader and tools. */
public final class Http {
    private Http() { }

    public static final String UA = "Jarvis/0.1 (Android)";

    /**
     * Parses "http://host:port", "socks5://host:port" (optional user:pass@). Blank means "no explicit proxy".
     */
    public static Proxy parseProxy(String spec) {
        if (spec == null || spec.trim().isEmpty()) return null;
        String s = spec.trim();
        Proxy.Type type = Proxy.Type.HTTP;
        if (s.startsWith("socks5://") || s.startsWith("socks://") || s.startsWith("socks5h://")) {
            type = Proxy.Type.SOCKS;
            s = s.substring(s.indexOf("://") + 3);
        } else if (s.startsWith("http://") || s.startsWith("https://")) {
            s = s.substring(s.indexOf("://") + 3);
        }
        String cred = null;
        int at = s.lastIndexOf('@');
        if (at >= 0) { cred = s.substring(0, at); s = s.substring(at + 1); }
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int colon = s.lastIndexOf(':');
        if (colon <= 0) throw new IllegalArgumentException("Прокси: ожидается host:port");
        String host = s.substring(0, colon);
        int port = Integer.parseInt(s.substring(colon + 1));
        if (cred != null && cred.contains(":")) {
            final String user = cred.substring(0, cred.indexOf(':'));
            final String pass = cred.substring(cred.indexOf(':') + 1);
            final String h = host;
            final int p = port;
            Authenticator.setDefault(new Authenticator() {
                @Override protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() == RequestorType.PROXY && h.equalsIgnoreCase(getRequestingHost()) && p == getRequestingPort()) {
                        return new PasswordAuthentication(user, pass.toCharArray());
                    }
                    return null;
                }
            });
        }
        return new Proxy(type, InetSocketAddress.createUnresolved(host, port));
    }

    public static HttpURLConnection open(String url, String method, Proxy proxy, int connectMs, int readMs) throws IOException {
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection) (proxy == null ? u.openConnection() : u.openConnection(proxy));
        c.setRequestMethod(method);
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        return c;
    }

    /** Reads at most max bytes, decodes as UTF-8. Never throws on truncation. */
    public static String readText(InputStream in, int maxBytes) throws IOException {
        if (in == null) return "";
        in = NetThrottle.in(in);
        ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buf = new byte[4096];
        int n;
        while (bo.size() < maxBytes && (n = in.read(buf, 0, Math.min(buf.length, maxBytes - bo.size()))) > 0) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    public static String basic(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }
}
