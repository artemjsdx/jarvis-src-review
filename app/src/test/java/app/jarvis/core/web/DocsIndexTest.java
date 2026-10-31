package app.jarvis.core.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.tools.Sandbox;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class DocsIndexTest {

    private static void write(File f, String text) throws Exception {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        f.setLastModified(System.currentTimeMillis() + 2000);
    }

    @Test
    public void ranksDocumentsLazilyRefreshesChangesAndDropsDeletedFiles() throws Exception {
        File root = Files.createTempDirectory("jv-docs").toFile();
        Sandbox sb = new Sandbox(root);
        write(new File(sb.downloads, "asyncio.md"), "# Asyncio\nThe event loop runs coroutines. Use asyncio.run for the event loop.\n");
        write(new File(sb.docs, "notes.txt"), "Погода в Гомеле: солнечно, +12.\n");
        write(new File(sb.docs, "page.html"), "<html><head><script>var secretTokenX=1;</script></head><body><p>Gradle build tool</p></body></html>");
        write(new File(sb.docs, "huge.txt"), repeat("filler ", 100_000));
        write(new File(sb.docs, "skip.pdf"), "event loop in a pdf");

        DocsIndex idx = new DocsIndex(sb);
        DocsIndex.Result r = idx.search("event loop", 5);
        assertEquals(1, r.hits.size());
        assertEquals("downloads/asyncio.md", r.hits.get(0).path);
        assertTrue(r.hits.get(0).snippet, r.hits.get(0).snippet.contains("event loop"));
        assertEquals(3, r.indexed);

        assertEquals("downloads/asyncio.md", idx.search("ЛУЧШИЙ asyncio", 5).hits.get(0).path);
        assertEquals("docs/notes.txt", idx.search("гомеле", 5).hits.get(0).path);
        assertEquals("docs/page.html", idx.search("gradle", 5).hits.get(0).path);
        assertTrue(idx.search("secrettokenx", 5).hits.isEmpty());
        assertTrue(idx.search("pdf", 5).hits.isEmpty());

        write(new File(sb.docs, "notes.txt"), "Совсем другой текст про кошек.\n");
        assertTrue(idx.search("гомеле", 5).hits.isEmpty());
        assertEquals("docs/notes.txt", idx.search("кошек", 5).hits.get(0).path);

        assertTrue(new File(sb.docs, "page.html").delete());
        assertEquals(2, idx.search("event", 5).indexed);
        assertFalse(idx.search("event", 5).hits.isEmpty());
    }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
