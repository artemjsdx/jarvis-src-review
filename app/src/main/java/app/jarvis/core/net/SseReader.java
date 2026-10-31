package app.jarvis.core.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Incremental Server-Sent Events parser (WHATWG rules: comments, multi-line data, CR/LF/CRLF). */
public final class SseReader {
    private SseReader() { }

    public interface Handler {
        /** @return false to stop reading. */
        boolean onEvent(String event, String data) throws Exception;
    }

    public static void read(InputStream in, Handler h) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(NetThrottle.in(in), StandardCharsets.UTF_8), 4096);
        String event = null;
        StringBuilder data = new StringBuilder();
        boolean hasData = false;
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isEmpty()) {
                if (hasData) {
                    if (!h.onEvent(event, data.toString())) return;
                }
                event = null;
                data.setLength(0);
                hasData = false;
                continue;
            }
            if (line.charAt(0) == ':') continue; // comment / keep-alive
            int c = line.indexOf(':');
            String field = c < 0 ? line : line.substring(0, c);
            String value = c < 0 ? "" : line.substring(c + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            if ("data".equals(field)) {
                if (hasData) data.append('\n');
                data.append(value);
                hasData = true;
            } else if ("event".equals(field)) {
                event = value;
            }
            // id / retry are irrelevant for one-shot LLM streams
        }
        if (hasData) h.onEvent(event, data.toString());
    }
}
