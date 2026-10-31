package app.jarvis.core.tools;

import app.jarvis.core.agent.ToolContext;
import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ToolSpec;
import app.jarvis.core.util.J;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Beta-test library: the agent writes a short report about a missing capability, a bug or a suspicious behaviour into
 * {@code docs/beta/} WITHOUT telling the user about it. One markdown file per report, named by time and title, so the whole
 * library can be copied or read from the files screen. The tool never fails the turn: a write error is returned as a normal
 * failed result (the model just continues with its task).
 */
public final class DevReport {
    private DevReport() { }

    public static void register(ToolRegistry r, final Sandbox sb) {
        r.add(ToolSpec.of("dev_report",
                "Записать отчёт в библиотеку бета-теста: чего не хватило, найденный баг или странное поведение. Молча, между делом; пользователю об этом не сообщать.",
                ToolSpec.Param.str("title", "Короткий заголовок: о чём отчёт.", true),
                ToolSpec.Param.str("text", "Что случилось, где и что улучшить. Обычный текст или markdown.", true),
                ToolSpec.Param.str("kind", "bug | gap | idea (необязательно).", false)),
            "tools", 20_000, true, (a, ctx) -> run(sb, J.str(a, "title"), J.str(a, "text"), J.str(a, "kind")));
    }

    static ToolResult run(Sandbox sb, String title, String text, String kind) {
        String t = title == null ? "" : title.trim();
        String body = text == null ? "" : text.trim();
        if (t.isEmpty() || body.isEmpty()) return ToolResult.fail("Нужны заголовок и текст отчёта.");
        File dir = new File(sb.docs, "beta");
        if (!dir.isDirectory() && !dir.mkdirs()) return ToolResult.fail("Не удалось создать docs/beta");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
        File f = new File(dir, stamp + "-" + slug(t) + ".md");
        int n = 2;
        while (f.exists()) f = new File(dir, stamp + "-" + slug(t) + "-" + (n++) + ".md");
        String k = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        String md = "# " + t + "\n\n"
            + "- когда: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "\n"
            + (k.isEmpty() ? "" : "- вид: " + k + "\n")
            + "\n" + body + "\n";
        byte[] bytes = md.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(bytes);
            os.getFD().sync();
        } catch (Exception e) {
            return ToolResult.fail("Не удалось записать отчёт: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
        if (!f.isFile() || f.length() != bytes.length) return ToolResult.fail("Отчёт не записался на диск");
        return ToolResult.ok(J.put(J.put(new JSONObject(), "path", sb.rel(f)), "size", f.length()), "отчёт " + sb.rel(f));
    }

    private static String slug(String s) {
        String r = s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+", "-").replaceAll("^-+|-+$", "");
        if (r.isEmpty()) r = "report";
        return r.length() > 40 ? r.substring(0, 40) : r;
    }
}
