package app.jarvis.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.agent.ToolResult;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/** The beta-test library: one markdown file per report, in docs/beta, never overwritten, never empty. */
public class DevReportTest {
    private static Sandbox sandbox() throws Exception {
        return new Sandbox(Files.createTempDirectory("jv-dev").toFile());
    }

    private static String read(File root, ToolResult r) throws Exception {
        return new String(Files.readAllBytes(new File(root, r.data.getString("path")).toPath()), StandardCharsets.UTF_8);
    }

    @Test public void writesAReportIntoBeta() throws Exception {
        Sandbox sb = sandbox();
        ToolResult r = DevReport.run(sb, "Нет инструмента для будильника", "Просили будильник, а инструмента нет. Нужен adb_shell или отдельный alarm_set.", "gap");
        assertTrue(r.error, r.ok);
        String path = r.data.getString("path");
        assertTrue(path, path.startsWith("docs/beta/"));
        assertTrue(path, path.endsWith(".md"));
        File f = new File(sb.root, path);
        assertTrue(f.isFile());
        String md = read(sb.root, r);
        assertTrue(md, md.startsWith("# Нет инструмента для будильника"));
        assertTrue(md, md.contains("- вид: gap"));
        assertTrue(md, md.contains("Просили будильник"));
    }

    @Test public void aSecondReportWithTheSameTitleDoesNotOverwriteTheFirst() throws Exception {
        Sandbox sb = sandbox();
        ToolResult a = DevReport.run(sb, "Баг в плеере", "Первый отчёт", "bug");
        ToolResult b = DevReport.run(sb, "Баг в плеере", "Второй отчёт", "bug");
        assertTrue(a.ok);
        assertTrue(b.ok);
        assertFalse(a.data.getString("path").equals(b.data.getString("path")));
        assertTrue(read(sb.root, a).contains("Первый отчёт"));
        assertTrue(read(sb.root, b).contains("Второй отчёт"));
    }

    @Test public void anEmptyReportIsRefusedAndWritesNothing() throws Exception {
        Sandbox sb = sandbox();
        assertFalse(DevReport.run(sb, "", "текст", "").ok);
        assertFalse(DevReport.run(sb, "заголовок", "   ", "").ok);
        File dir = new File(sb.docs, "beta");
        assertFalse(dir.isDirectory() && dir.list() != null && dir.list().length > 0);
    }

    @Test public void theKindIsOptionalAndTheSlugIsSafe() throws Exception {
        Sandbox sb = sandbox();
        ToolResult r = DevReport.run(sb, "Странное: /путь/../в файле!", "Текст", null);
        assertTrue(r.error, r.ok);
        String path = r.data.getString("path");
        assertEquals(path.replaceAll(".*/", "").replaceAll(".*-", ""), path.replaceAll(".*/", "").replaceAll(".*-", ""));
        assertFalse(path, path.contains(" "));
        assertFalse(path, path.contains(".."));
        assertTrue(path, path.contains("странное-путь-в-файле"));
        assertFalse(read(sb.root, r).contains("- вид:"));
    }
}
