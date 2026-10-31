package app.jarvis.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.MockServer;
import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ToolCall;
import app.jarvis.core.log.Journal;
import app.jarvis.core.util.CancelToken;
import com.sun.net.httpserver.HttpExchange;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class WebToolsTest {

    private static ToolResult call(ToolRegistry reg, String name, String args) {
        return reg.execute(new ToolCall("c1", name, args), "run", new CancelToken(), Journal.NOP, null);
    }

    private static java.util.function.Function<String, String> map(String... kv) {
        final Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return new java.util.function.Function<String, String>() {
            @Override public String apply(String k) { return m.get(k); }
        };
    }

    private static String read(String name) throws IOException {
        File dir = new File("").getAbsoluteFile();
        while (dir != null && !new File(dir, "app/src/test/resources/web/" + name).isFile()) dir = dir.getParentFile();
        return new String(Files.readAllBytes(new File(dir, "app/src/test/resources/web/" + name).toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void webSearchRunsQueriesThroughSearxngAndReportsBackend() throws Exception {
        final String searx = read("searxng.json");
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                MockServer.json(x, 200, searx);
            }
        })) {
            Sandbox sb = new Sandbox(Files.createTempDirectory("jv-ws").toFile());
            ToolRegistry reg = new ToolRegistry();
            WebTools.register(reg, sb, map("web.searxng.url", s.url("")), map(), new String[]{""});
            ToolResult r = call(reg, "web_search", "{\"queries\":[\"погода гомель\",\"гомель\"],\"limit\":2}");
            assertTrue(r.error, r.ok);
            assertEquals("searxng", r.data.getString("backend"));
            assertEquals(2, r.data.getJSONArray("results").length());
            assertEquals(2, r.data.getJSONArray("results").getJSONObject(0).getJSONArray("hits").length());
            assertEquals(0, r.data.getJSONArray("results").getJSONObject(1).getJSONArray("hits").length());
        }
    }

    @Test
    public void webSearchValidatesArgumentsInRussian() throws Exception {
        Sandbox sb = new Sandbox(Files.createTempDirectory("jv-ws2").toFile());
        ToolRegistry reg = new ToolRegistry();
        WebTools.register(reg, sb, map(), map(), new String[]{""});
        ToolResult none = call(reg, "web_search", "{\"queries\":[]}");
        assertFalse(none.ok);
        ToolResult many = call(reg, "web_search", "{\"queries\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\"]}");
        assertFalse(many.ok);
        assertTrue(many.error, many.error.contains("6"));
        ToolResult bad = call(reg, "web_search", "{\"queries\":[\"a\"],\"recency\":\"decade\"}");
        assertFalse(bad.ok);
    }

    @Test
    public void webSearchExplainsMissingKeyForNamedBackend() throws Exception {
        Sandbox sb = new Sandbox(Files.createTempDirectory("jv-ws3").toFile());
        ToolRegistry reg = new ToolRegistry();
        WebTools.register(reg, sb, map("web.search.backend", "brave"), map(), new String[]{""});
        ToolResult r = call(reg, "web_search", "{\"queries\":[\"x\"]}");
        assertFalse(r.ok);
        assertTrue(r.error, r.error.contains("не настроен"));
    }

    @Test
    public void webFetchReturnsMarkdownWithPaginationAndLinks() throws Exception {
        final String html = "<html><body><article><h1>Документация</h1><p>См. <a href='/api/v2'>API v2</a>.</p>"
            + "<p>" + repeat("Абзац текста. ", 200) + "</p></article></body></html>";
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                x.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                byte[] b = html.getBytes(StandardCharsets.UTF_8);
                x.sendResponseHeaders(200, b.length);
                x.getResponseBody().write(b);
                x.close();
            }
        })) {
            Sandbox sb = new Sandbox(Files.createTempDirectory("jv-wf").toFile());
            ToolRegistry reg = new ToolRegistry();
            WebTools.register(reg, sb, map(), map(), new String[]{""});
            ToolResult r = call(reg, "web_fetch", "{\"url\":\"" + s.url("/docs") + "\",\"max_chars\":1000}");
            assertTrue(r.error, r.ok);
            assertEquals("Документация", r.data.getString("title"));
            assertTrue(r.data.getString("text"), r.data.getString("text").startsWith("# Документация"));
            assertTrue(r.data.getBoolean("truncated"));
            assertEquals(1000, r.data.getInt("nextOffset"));
            assertEquals(s.url("/api/v2"), r.data.getJSONArray("links").getJSONObject(0).getString("url"));
            ToolResult next = call(reg, "web_fetch", "{\"url\":\"" + s.url("/docs") + "\",\"max_chars\":1000,\"offset\":1000}");
            assertTrue(next.ok);
            assertTrue(next.data.getString("text").length() > 0);
        }
    }

    @Test
    public void webFetchRejectsNonHttpAndReportsHttpErrors() throws Exception {
        try (MockServer s = new MockServer(new MockServer.H() {
            @Override public void handle(HttpExchange x, String body) throws IOException {
                MockServer.json(x, 500, "{\"error\":\"boom\"}");
            }
        })) {
            Sandbox sb = new Sandbox(Files.createTempDirectory("jv-wf2").toFile());
            ToolRegistry reg = new ToolRegistry();
            WebTools.register(reg, sb, map(), map(), new String[]{""});
            assertFalse(call(reg, "web_fetch", "{\"url\":\"ftp://x.org/a\"}").ok);
            ToolResult err = call(reg, "web_fetch", "{\"url\":\"" + s.url("/x") + "\"}");
            assertFalse(err.ok);
            assertEquals("HTTP 500", err.error);
        }
    }

    @Test
    public void docsSearchFindsFilesInSandbox() throws Exception {
        Sandbox sb = new Sandbox(Files.createTempDirectory("jv-ds").toFile());
        Files.write(new File(sb.docs, "guide.md").toPath(), "Install with pip install requests.".getBytes(StandardCharsets.UTF_8));
        ToolRegistry reg = new ToolRegistry();
        WebTools.register(reg, sb, map(), map(), new String[]{""});
        ToolResult r = call(reg, "docs_search", "{\"query\":\"pip requests\"}");
        assertTrue(r.error, r.ok);
        assertEquals("docs/guide.md", r.data.getJSONArray("hits").getJSONObject(0).getString("path"));
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
