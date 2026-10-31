package app.jarvis.core.mcp;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/** Raw-socket HTTP/1.1 client: lets tests send any Host header, keep connections alive and send broken requests. */
final class McpTestClient implements AutoCloseable {
    static final class Resp {
        int status;
        final Map<String, String> headers = new HashMap<>();
        String body = "";
        JSONObject json() { return new JSONObject(body); }
    }

    final Socket s;
    private final InputStream in;
    private final OutputStream out;

    McpTestClient(int port) throws Exception {
        s = new Socket();
        s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 2000);
        s.setSoTimeout(10000);
        in = s.getInputStream();
        out = s.getOutputStream();
    }

    Resp post(int port, String token, String body) throws Exception { return post(port, "127.0.0.1:" + port, token, body); }

    Resp post(int port, String host, String token, String body) throws Exception {
        return request("POST", "/mcp", host, token, body == null ? null : body.getBytes(StandardCharsets.UTF_8), null);
    }

    Resp request(String method, String path, String host, String token, byte[] body, String extraHeaders) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        if (host != null) sb.append("Host: ").append(host).append("\r\n");
        if (token != null) sb.append("Authorization: Bearer ").append(token).append("\r\n");
        sb.append("Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n");
        if (extraHeaders != null) sb.append(extraHeaders);
        if (body != null) sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (body != null) out.write(body);
        out.flush();
        return read();
    }

    void sendRaw(String s) throws Exception { out.write(s.getBytes(StandardCharsets.ISO_8859_1)); out.flush(); }

    Resp read() throws Exception {
        Resp r = new Resp();
        String line = line();
        if (line == null) throw new java.io.EOFException("closed before response");
        r.status = Integer.parseInt(line.split(" ")[1]);
        int len = 0;
        while (true) {
            String h = line();
            if (h == null || h.isEmpty()) break;
            int c = h.indexOf(':');
            r.headers.put(h.substring(0, c).toLowerCase(), h.substring(c + 1).trim());
        }
        String cl = r.headers.get("content-length");
        if (cl != null) len = Integer.parseInt(cl);
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) { int n = in.read(b, off, len - off); if (n < 0) break; off += n; }
        r.body = new String(b, 0, off, StandardCharsets.UTF_8);
        return r;
    }

    private String line() throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (true) {
            int c = in.read();
            if (c < 0) return b.size() == 0 ? null : b.toString("ISO-8859-1");
            if (c == '\n') { String x = b.toString("ISO-8859-1"); return x.endsWith("\r") ? x.substring(0, x.length() - 1) : x; }
            b.write(c);
        }
    }

    /** True when the server closed the connection (EOF) or reset it. */
    boolean closedByPeer() {
        try { return in.read() < 0; } catch (java.net.SocketTimeoutException e) { return false; } catch (Exception e) { return true; }
    }

    @Override public void close() { try { s.close(); } catch (Exception ignored) { } }
}
