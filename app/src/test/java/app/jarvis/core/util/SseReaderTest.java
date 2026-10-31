package app.jarvis.core.util;

import static org.junit.Assert.assertEquals;

import app.jarvis.core.net.SseReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class SseReaderTest {
    private static List<String> parse(String raw) throws Exception {
        final List<String> out = new ArrayList<>();
        SseReader.read(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)), new SseReader.Handler() {
            @Override public boolean onEvent(String event, String data) { out.add((event == null ? "" : event + ":") + data); return true; }
        });
        return out;
    }

    @Test public void multiLineDataAndComments() throws Exception {
        List<String> e = parse(": keep-alive\n\ndata: a\ndata: b\n\nevent: x\ndata: {\"k\":1}\n\n");
        assertEquals(2, e.size());
        assertEquals("a\nb", e.get(0));
        assertEquals("x:{\"k\":1}", e.get(1));
    }

    @Test public void crlfAndMissingFinalBlankLine() throws Exception {
        List<String> e = parse("data: one\r\n\r\ndata: two");
        assertEquals(2, e.size());
        assertEquals("one", e.get(0));
        assertEquals("two", e.get(1));
    }

    @Test public void utf8AndNoSpaceAfterColon() throws Exception {
        List<String> e = parse("data:Привет, мир\n\n");
        assertEquals("Привет, мир", e.get(0));
    }

    @Test public void handlerCanStopEarly() throws Exception {
        final int[] n = {0};
        SseReader.read(new ByteArrayInputStream("data: 1\n\ndata: 2\n\ndata: 3\n\n".getBytes(StandardCharsets.UTF_8)), new SseReader.Handler() {
            @Override public boolean onEvent(String ev, String d) { n[0]++; return n[0] < 2; }
        });
        assertEquals(2, n[0]);
    }
}
