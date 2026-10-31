package app.jarvis.core.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.jarvis.core.MockServer;
import app.jarvis.core.util.CancelToken;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;

public class WebFetcherTest {

    static void send(HttpExchange x, int status, String contentType, byte[] body) throws IOException {
        x.getResponseHeaders().set("Content-Type", contentType);
        x.sendResponseHeaders(status, body.length);
        try (OutputStream os = x.getResponseBody()) { os.write(body); }
    }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static WebFetcher.Page fetch(MockServer s, String path, String format, int max, int offset) throws WebException {
        return new WebFetcher(null).fetch(s.url(path), format, max, offset, new CancelToken());
    }

    @Test
    public void htmlBecomesMarkdownWithAbsoluteLinksAndTitle() throws Exception {
        final String html = "<html><head><title>Док &mdash; Гомель</title></head><body><nav>menu</nav>"
            + "<main><h1>Гомель</h1><p>Город с <a href='/wiki/Немига'>мостом</a>.</p>"
            + "<ul><li>раз<li>два</ul></main><footer>подвал</footer></body></html>";
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                send(x, 200, "text/html; charset=utf-8", utf8(html));
            }
        })) {
            WebFetcher.Page p = fetch(s, "/doc.html", "markdown", 12000, 0);
            assertEquals("Док — Гомель", p.title);
            assertEquals("markdown", p.format);
            assertTrue(p.text, p.text.contains("# Гомель"));
            assertTrue(p.text, p.text.contains("[мостом](" + s.url("/wiki/Немига") + ")"));
            assertTrue(p.text, p.text.contains("- раз"));
            assertFalse(p.text, p.text.contains("menu"));
            assertFalse(p.text, p.text.contains("подвал"));
            assertEquals(1, p.links.size());
            assertEquals(-1, p.nextOffset);
            assertFalse(p.truncated);
        }
    }

    @Test
    public void formatTextAndRawAreHonoured() throws Exception {
        final String html = "<p>Текст <b>жирный</b></p>";
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                send(x, 200, "text/html", utf8(html));
            }
        })) {
            assertEquals("Текст жирный", fetch(s, "/a", "text", 1000, 0).text);
            assertEquals(html, fetch(s, "/a", "raw", 1000, 0).text);
        }
    }

    @Test
    public void jsonIsPrettyPrintedAndPlainTextPassesThrough() throws Exception {
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                if (x.getRequestURI().getPath().endsWith(".json")) send(x, 200, "application/json", utf8("{\"a\":1,\"b\":[true]}"));
                else send(x, 200, "text/plain; charset=utf-8", utf8("простой текст"));
            }
        })) {
            WebFetcher.Page j = fetch(s, "/x.json", "markdown", 1000, 0);
            assertEquals("json", j.format);
            assertTrue(j.text, j.text.contains("\"a\": 1"));
            WebFetcher.Page t = fetch(s, "/x.txt", "markdown", 1000, 0);
            assertEquals("простой текст", t.text);
        }
    }

    @Test
    public void binaryTypesAreNotParsedAndGiveAHint() throws Exception {
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                send(x, 200, "application/pdf", new byte[]{'%', 'P', 'D', 'F', 0, 1, 2});
            }
        })) {
            WebFetcher.Page p = fetch(s, "/manual.pdf", "markdown", 1000, 0);
            assertTrue(p.binary);
            assertEquals("", p.text);
            assertTrue(p.hint, p.hint.contains("download"));
        }
    }

    @Test
    public void redirectsAreFollowedAndCappedAtFive() throws Exception {
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                String path = x.getRequestURI().getPath();
                if (path.startsWith("/r/")) {
                    int n = Integer.parseInt(path.substring(3));
                    if (n > 0) {
                        x.getResponseHeaders().set("Location", "/r/" + (n - 1));
                        x.sendResponseHeaders(302, -1);
                        x.close();
                        return;
                    }
                    send(x, 200, "text/html", utf8("<title>end</title><p>landed</p>"));
                    return;
                }
                send(x, 200, "text/html", utf8("<p>ok</p>"));
            }
        })) {
            WebFetcher.Page p = fetch(s, "/r/3", "markdown", 1000, 0);
            assertEquals("landed", p.text);
            assertEquals(s.url("/r/0"), p.finalUrl);
            try {
                fetch(s, "/r/6", "markdown", 1000, 0);
                fail("six hops must fail");
            } catch (WebException e) {
                assertTrue(e.userMessage, e.userMessage.contains("перенаправлений"));
            }
        }
    }

    @Test
    public void httpsToHttpDowngradeIsRefused() {
        assertTrue(WebFetcher.isDowngrade("https://a.org/x", "http://a.org/y"));
        assertFalse(WebFetcher.isDowngrade("http://a.org/x", "https://a.org/y"));
        assertEquals("https://b.org/z", WebFetcher.resolve("https://a.org/x/y", "https://b.org/z"));
        assertEquals("https://a.org/x/z", WebFetcher.resolve("https://a.org/x/y", "z"));
        assertEquals(null, WebFetcher.resolve("https://a.org/", "javascript:alert(1)"));
    }

    @Test
    public void errorStatusIsReportedNotThrown() throws Exception {
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                send(x, 404, "text/html", utf8("<h1>Not here</h1>"));
            }
        })) {
            WebFetcher.Page p = fetch(s, "/missing", "markdown", 1000, 0);
            assertFalse(p.ok);
            assertEquals(404, p.status);
            assertEquals("HTTP 404", p.error);
        }
    }

    @Test
    public void gzipBodyIsDecoded() throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bo)) { gz.write(utf8("<p>сжато и читаемо</p>")); }
        final byte[] gzipped = bo.toByteArray();
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                x.getResponseHeaders().set("Content-Encoding", "gzip");
                send(x, 200, "text/html; charset=utf-8", gzipped);
            }
        })) {
            assertEquals("сжато и читаемо", fetch(s, "/gz", "markdown", 1000, 0).text);
        }
    }

    @Test
    public void windows1251PageWithoutHeaderCharsetIsDecoded() throws Exception {
        final byte[] page = ("<html><head><meta charset=\"windows-1251\"></head><body><p>Привет из Гомеля</p></body></html>")
            .getBytes(Charset.forName("windows-1251"));
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                send(x, 200, "text/html", page);
            }
        })) {
            assertEquals("Привет из Гомеля", fetch(s, "/ru", "text", 1000, 0).text);
        }
    }

    @Test
    public void paginationWalksTheWholePageWithoutLosingCharacters() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) sb.append("Строка номер ").append(i).append(" с текстом 😀.\n");
        final String plain = sb.toString();
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                send(x, 200, "text/plain; charset=utf-8", utf8(plain));
            }
        })) {
            StringBuilder got = new StringBuilder();
            int offset = 0, pages = 0;
            while (true) {
                WebFetcher.Page p = fetch(s, "/long", "text", 1000, offset);
                got.append(p.text);
                pages++;
                assertTrue(p.truncated || p.nextOffset < 0);
                if (p.nextOffset < 0) break;
                offset = p.nextOffset;
            }
            assertTrue(pages > 5);
            assertEquals(plain, got.toString());
        }
    }

    @Test
    public void hugeSourceIsCappedAtThreeMiB() throws Exception {
        final byte[] big = new byte[(int) WebFetcher.MAX_SOURCE_BYTES + 500_000];
        java.util.Arrays.fill(big, (byte) 'a');
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                send(x, 200, "text/plain", big);
            }
        })) {
            WebFetcher.Page p = fetch(s, "/huge", "text", 2000, 0);
            assertTrue(p.sourceTruncated);
            assertTrue(p.truncated);
            assertTrue(p.hint, p.hint.contains("3 МБ"));
            assertEquals(2000, p.text.length());
            assertTrue(p.nextOffset > 0);
        }
    }

    @Test
    public void cancellationAbortsASlowPagePromptly() throws Exception {
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                try { Thread.sleep(10_000); } catch (InterruptedException ignored) { }
                send(x, 200, "text/plain", utf8("late"));
            }
        })) {
            final CancelToken c = new CancelToken();
            new Thread(new Runnable() { @Override public void run() {
                try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                c.cancel("user");
            } }).start();
            long t0 = System.nanoTime();
            try {
                new WebFetcher(null).fetch(s.url("/slow"), "markdown", 1000, 0, c);
                fail();
            } catch (CancelToken.CancelledException expected) {
                assertEquals("user", expected.getMessage());
            }
            assertTrue(System.nanoTime() - t0 < 5_000_000_000L);
        }
    }

    @Test
    public void nonHttpSchemeIsRejected() {
        try {
            new WebFetcher(null).fetch("file:///etc/passwd", "markdown", 1000, 0, new CancelToken());
            fail();
        } catch (WebException e) {
            assertTrue(e.userMessage, e.userMessage.contains("http"));
        }
    }
}
