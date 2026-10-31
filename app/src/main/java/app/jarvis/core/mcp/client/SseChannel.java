package app.jarvis.core.mcp.client;

import app.jarvis.core.net.Http;
import app.jarvis.core.net.SseReader;
import app.jarvis.core.util.CancelToken;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.Map;

/**
 * A GET event stream with reconnect: the stream is read on a daemon thread; when it ends or breaks, it is reopened with
 * exponential backoff. Gives up after {@link TransportOptions#maxAttempts} attempts that delivered no event.
 */
final class SseChannel {
    interface Headers { Map<String, String> get(); }
    interface Handler { void onEvent(String event, String data); }
    interface Listener { void onLost(String technical); }

    private final String url;
    private final Headers headers;
    private final TransportOptions opt;
    private final Handler handler;
    private final Listener listener;
    private final CancelToken stop = new CancelToken();
    private volatile HttpURLConnection conn;
    private InputStream first;

    SseChannel(String url, Headers headers, TransportOptions opt, Handler handler, Listener listener) {
        this.url = url;
        this.headers = headers;
        this.opt = opt;
        this.handler = handler;
        this.listener = listener;
    }

    /**
     * Opens the first stream synchronously. Returns false when the server offers no stream (HTTP 405); throws
     * {@link McpException} for other failures.
     */
    boolean connect() throws McpException {
        first = openStream();
        return first != null;
    }

    void start() {
        Thread t = new Thread(new Runnable() { @Override public void run() { pump(); } }, "jv-mcp-sse");
        t.setDaemon(true);
        t.start();
    }

    private void pump() {
        InputStream in = first;
        first = null;
        int failures = 0;
        while (!stop.isCancelled()) {
            if (in == null) {
                try {
                    in = openStream();
                    if (in == null) return;
                } catch (McpException e) {
                    if (stop.isCancelled()) return;
                    failures++;
                    if (failures >= opt.maxAttempts) { listener.onLost(e.technical); return; }
                    if (!stop.sleep(opt.backoff(failures))) return;
                    continue;
                }
            }
            final boolean[] progressed = {false};
            String ended;
            try {
                SseReader.read(in, new SseReader.Handler() {
                    @Override public boolean onEvent(String event, String data) {
                        progressed[0] = true;
                        handler.onEvent(event, data);
                        return !stop.isCancelled();
                    }
                });
                ended = "поток закрыт сервером";
            } catch (Exception e) {
                ended = e.toString();
            }
            closeConn();
            in = null;
            if (stop.isCancelled()) return;
            failures = progressed[0] ? 0 : failures + 1;
            if (failures >= opt.maxAttempts) { listener.onLost(ended); return; }
            if (!stop.sleep(opt.backoff(failures))) return;
        }
    }

    private InputStream openStream() throws McpException {
        HttpURLConnection c;
        try {
            c = Http.open(url, "GET", null, opt.connectMs, opt.readMs);
        } catch (IOException e) {
            throw new McpException(McpException.Kind.TRANSPORT, "Не удалось открыть поток сервера", e.toString(), 0, e);
        }
        conn = c;
        for (Map.Entry<String, String> h : headers.get().entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        c.setRequestProperty("Accept", "text/event-stream");
        try {
            int code = c.getResponseCode();
            if (code == 405) { closeConn(); return null; }
            if (code < 200 || code >= 300) {
                String tech = "GET " + code;
                closeConn();
                throw new McpException(McpException.Kind.HTTP, "Сервер ответил ошибкой " + code + " на открытие потока", tech, code, null);
            }
            conn = c;
            return c.getInputStream();
        } catch (IOException e) {
            closeConn();
            throw new McpException(McpException.Kind.TRANSPORT, "Соединение с сервером прервалось", e.toString(), 0, e);
        }
    }

    private void closeConn() {
        HttpURLConnection c = conn;
        conn = null;
        if (c != null) c.disconnect();
    }

    void close() {
        stop.cancel("closed");
        closeConn();
    }
}
