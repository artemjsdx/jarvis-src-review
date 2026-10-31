package app.jarvis.core.mcp.client;

import app.jarvis.core.net.Http;
import app.jarvis.core.util.CancelToken;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * MCP 2024-11-05 HTTP+SSE: a GET event stream whose first "endpoint" event names the POST URL; every answer arrives
 * on the stream. The stream is reopened by {@link SseChannel} if it drops (the endpoint is sent again).
 */
public final class LegacySseTransport implements McpTransport {
    private final String url;
    private final Map<String, String> headers;
    private final TransportOptions opt;
    private volatile McpTransport.Sink sink;
    private volatile String postUrl;
    private volatile String protocolVersion = "";
    private volatile SseChannel channel;
    private volatile CountDownLatch endpointReady = new CountDownLatch(1);

    public LegacySseTransport(String url, Map<String, String> headers, TransportOptions opt) {
        this.url = url;
        this.headers = headers == null ? new HashMap<String, String>() : new HashMap<>(headers);
        this.opt = opt == null ? new TransportOptions() : opt;
    }

    @Override public void open(Sink s) throws McpException {
        sink = s;
        postUrl = null;
        endpointReady = new CountDownLatch(1);
        SseChannel ch = new SseChannel(url, new SseChannel.Headers() {
            @Override public Map<String, String> get() { return new HashMap<>(headers); }
        }, opt, new SseChannel.Handler() {
            @Override public void onEvent(String event, String data) {
                if ("endpoint".equals(event)) {
                    postUrl = resolve(data);
                    endpointReady.countDown();
                    return;
                }
                if (event != null && !event.isEmpty() && !"message".equals(event)) return;
                for (JSONObject m : McpWire.parseMessages(data)) {
                    McpTransport.Sink k = sink;
                    if (k != null) k.onMessage(m);
                }
            }
        }, new SseChannel.Listener() {
            @Override public void onLost(String technical) {
                McpTransport.Sink k = sink;
                if (k != null) k.onChannelLost(technical);
            }
        });
        if (!ch.connect()) throw new McpException(McpException.Kind.UNSUPPORTED, "Сервер не открыл поток событий", "GET answered 405");
        channel = ch;
        ch.start();
        try {
            if (!endpointReady.await(opt.connectMs, TimeUnit.MILLISECONDS)) {
                ch.close();
                throw new McpException(McpException.Kind.TRANSPORT, "Сервер не сообщил адрес для запросов", "no endpoint event within " + opt.connectMs + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ch.close();
            throw new McpException(McpException.Kind.CANCELLED, "Отменено", "interrupted", 0, e);
        }
    }

    private String resolve(String data) {
        String d = data == null ? "" : data.trim();
        try {
            return URI.create(url).resolve(URI.create(d)).toString();
        } catch (RuntimeException e) {
            return d;
        }
    }

    @Override public void post(JSONObject message, CancelToken cancel) throws McpException {
        String target = postUrl;
        if (target == null) throw new McpException(McpException.Kind.TRANSPORT, "Соединение не открыто", "no endpoint");
        if (cancel.isCancelled()) throw new McpException(McpException.Kind.CANCELLED, "Отменено", "cancelled");
        final HttpURLConnection c;
        try {
            c = Http.open(target, "POST", null, opt.connectMs, opt.readMs);
        } catch (IOException e) {
            throw new McpException(McpException.Kind.TRANSPORT, "Не удалось открыть соединение с сервером", e.toString(), 0, e);
        }
        Runnable unhook = cancel.onCancel(new Runnable() { @Override public void run() { c.disconnect(); } });
        try {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            if (!protocolVersion.isEmpty()) c.setRequestProperty("MCP-Protocol-Version", protocolVersion);
            byte[] body = message.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
            int code = c.getResponseCode();
            if (code >= 400) {
                String snippet = McpWire.clip(McpWire.readBody(c.getErrorStream(), 512), 200);
                String user = code == 401 || code == 403 ? "Сервер отклонил токен (HTTP " + code + ")" : "Сервер ответил ошибкой HTTP " + code;
                throw new McpException(McpException.Kind.HTTP, user, "HTTP " + code + (snippet.isEmpty() ? "" : ": " + snippet), code, null);
            }
            String text = McpWire.readBody(c.getInputStream(), McpWire.MAX_BODY);
            for (JSONObject m : McpWire.parseMessages(text)) {
                McpTransport.Sink k = sink;
                if (k != null) k.onMessage(m);
            }
        } catch (IOException e) {
            if (cancel.isCancelled()) throw new McpException(McpException.Kind.CANCELLED, "Отменено", "cancelled", 0, e);
            throw new McpException(McpException.Kind.TRANSPORT, "Соединение с сервером прервалось", e.toString(), 0, e);
        } finally {
            unhook.run();
        }
    }

    @Override public void resetSession() { }

    @Override public void setProtocolVersion(String version) { protocolVersion = version == null ? "" : version; }

    @Override public void close() {
        SseChannel ch = channel;
        channel = null;
        if (ch != null) ch.close();
    }
}
