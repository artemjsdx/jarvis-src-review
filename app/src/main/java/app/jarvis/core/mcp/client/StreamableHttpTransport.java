package app.jarvis.core.mcp.client;

import app.jarvis.core.net.Http;
import app.jarvis.core.net.SseReader;
import app.jarvis.core.util.CancelToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * MCP Streamable HTTP: every client message is a POST; the answer is a JSON body or an SSE stream that ends with the
 * matching response. The session id from the initialize answer is echoed on every later request. After
 * notifications/initialized the optional GET stream is opened for server-initiated messages (405 = not offered).
 */
public final class StreamableHttpTransport implements McpTransport {
    static final String SESSION_HEADER = "Mcp-Session-Id";

    private final String url;
    private final Map<String, String> headers;
    private final TransportOptions opt;
    private volatile McpTransport.Sink sink;
    private volatile String sessionId = "";
    private volatile String protocolVersion = "";
    private volatile SseChannel channel;
    private volatile boolean closed = true;

    public StreamableHttpTransport(String url, Map<String, String> headers, TransportOptions opt) {
        this.url = url;
        this.headers = headers == null ? new HashMap<String, String>() : new HashMap<>(headers);
        this.opt = opt == null ? new TransportOptions() : opt;
    }

    @Override public void open(Sink s) {
        sink = s;
        closed = false;
    }

    @Override public void resetSession() {
        sessionId = "";
        SseChannel c = channel;
        channel = null;
        if (c != null) c.close();
    }

    @Override public void setProtocolVersion(String version) { protocolVersion = version == null ? "" : version; }

    String sessionId() { return sessionId; }

    private Map<String, String> requestHeaders() {
        Map<String, String> h = new HashMap<>(headers);
        if (!protocolVersion.isEmpty()) h.put("MCP-Protocol-Version", protocolVersion);
        if (!sessionId.isEmpty()) h.put(SESSION_HEADER, sessionId);
        return h;
    }

    @Override public void post(JSONObject message, CancelToken cancel) throws McpException {
        if (closed) throw new McpException(McpException.Kind.TRANSPORT, "Соединение закрыто", "closed");
        final String reqId = message.has("method") && message.has("id") ? McpWire.idKey(message.opt("id")) : null;
        final String sessionBefore = sessionId;
        if (cancel.isCancelled()) throw new McpException(McpException.Kind.CANCELLED, "Отменено", "cancelled");
        final HttpURLConnection c;
        try {
            c = open("POST");
        } catch (IOException e) {
            throw new McpException(McpException.Kind.TRANSPORT, "Не удалось открыть соединение с сервером", e.toString(), 0, e);
        }
        Runnable unhook = cancel.onCancel(new Runnable() { @Override public void run() { c.disconnect(); } });
        try {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json, text/event-stream");
            for (Map.Entry<String, String> h : requestHeaders().entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            byte[] body = message.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
            int code = c.getResponseCode();
            String sid = c.getHeaderField(SESSION_HEADER);
            if (sid != null && !sid.trim().isEmpty()) sessionId = sid.trim();
            if (code == 404 && !sessionBefore.isEmpty()) throw new McpException(McpException.Kind.SESSION_GONE, "Сессия с сервером истекла", "HTTP 404 for session " + sessionBefore);
            if (code == 202 || code == 204) {
                afterPost(message);
                return;
            }
            if (code >= 400) {
                String snippet = McpWire.clip(McpWire.readBody(c.getErrorStream(), 512), 200);
                throw httpError(code, snippet);
            }
            String ct = c.getContentType() == null ? "" : c.getContentType().toLowerCase(java.util.Locale.ROOT);
            boolean gotResponse;
            if (ct.startsWith("text/event-stream")) {
                gotResponse = readSse(c.getInputStream(), reqId);
                if (!gotResponse) c.disconnect();
            } else {
                String text = McpWire.readBody(c.getInputStream(), McpWire.MAX_BODY);
                gotResponse = false;
                for (JSONObject m : McpWire.parseMessages(text)) {
                    deliver(m);
                    if (reqId != null && McpWire.isResponse(m) && reqId.equals(McpWire.idKey(m.opt("id")))) gotResponse = true;
                }
            }
            if (reqId != null && !gotResponse) {
                throw new McpException(McpException.Kind.PROTOCOL, "Сервер не вернул ответ на запрос", "no response for id " + reqId);
            }
            afterPost(message);
        } catch (IOException e) {
            if (cancel.isCancelled()) throw new McpException(McpException.Kind.CANCELLED, "Отменено", "cancelled", 0, e);
            throw new McpException(McpException.Kind.TRANSPORT, "Соединение с сервером прервалось", e.toString(), 0, e);
        } finally {
            unhook.run();
        }
    }

    private static McpException httpError(int code, String snippet) {
        String user = code == 401 || code == 403 ? "Сервер отклонил токен (HTTP " + code + ")" : "Сервер ответил ошибкой HTTP " + code;
        return new McpException(McpException.Kind.HTTP, user, "HTTP " + code + (snippet.isEmpty() ? "" : ": " + snippet), code, null);
    }

    private HttpURLConnection open(String method) throws IOException {
        return Http.open(url, method, null, opt.connectMs, opt.readMs);
    }

    private boolean readSse(InputStream in, final String reqId) throws IOException {
        final boolean[] got = {false};
        try {
            SseReader.read(in, new SseReader.Handler() {
                @Override public boolean onEvent(String event, String data) {
                    if (event != null && !event.isEmpty() && !"message".equals(event)) return true;
                    for (JSONObject m : McpWire.parseMessages(data)) {
                        deliver(m);
                        if (reqId != null && McpWire.isResponse(m) && reqId.equals(McpWire.idKey(m.opt("id")))) got[0] = true;
                    }
                    return !got[0];
                }
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        return got[0];
    }

    private void deliver(JSONObject m) {
        McpTransport.Sink s = sink;
        if (s != null) s.onMessage(m);
    }

    /** The GET stream is opened once, right after notifications/initialized; failures there are not fatal. */
    private void afterPost(JSONObject message) {
        if (channel != null || !"notifications/initialized".equals(message.optString("method"))) return;
        SseChannel ch = new SseChannel(url, new SseChannel.Headers() {
            @Override public Map<String, String> get() { return requestHeaders(); }
        }, opt, new SseChannel.Handler() {
            @Override public void onEvent(String event, String data) {
                if (event != null && !event.isEmpty() && !"message".equals(event)) return;
                List<JSONObject> ms = McpWire.parseMessages(data);
                for (JSONObject m : ms) deliver(m);
            }
        }, new SseChannel.Listener() {
            @Override public void onLost(String technical) {
                McpTransport.Sink s = sink;
                if (s != null) s.onChannelLost(technical);
            }
        });
        try {
            if (ch.connect()) {
                channel = ch;
                ch.start();
            }
        } catch (McpException ignored) {
            ch.close();
        }
    }

    @Override public void close() {
        closed = true;
        SseChannel c = channel;
        channel = null;
        if (c != null) c.close();
        final String sid = sessionId;
        if (!sid.isEmpty()) {
            HttpURLConnection d = null;
            try {
                d = open("DELETE");
                d.setConnectTimeout(3000);
                d.setReadTimeout(3000);
                d.setRequestProperty(SESSION_HEADER, sid);
                for (Map.Entry<String, String> h : headers.entrySet()) d.setRequestProperty(h.getKey(), h.getValue());
                d.getResponseCode();
            } catch (Exception ignored) {
                // the server expires idle sessions on its own
            } finally {
                if (d != null) d.disconnect();
            }
        }
        sessionId = "";
    }
}
